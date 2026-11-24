//! Unified LLM & ASR inference abstraction shared across all platforms:
//! - Language detection & target resolution (`Langs`)
//! - Thinking-model `<think>` full & streaming tag filters
//! - Translation, Knowledge Point extraction, Recap, Glossary, and Q&A prompt builders
//! - Pooled OpenAI-compatible SSE streaming client with bounded buffers & cancellation
//! - Resumable GGUF / Whisper offline model catalog & downloader

pub mod models;

use std::sync::OnceLock;
use std::time::Duration;

use async_trait::async_trait;
use core_model::GlossaryEntry;
use futures_util::StreamExt;
use regex::Regex;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio_util::sync::CancellationToken;

#[derive(Debug, Error)]
pub enum InferenceError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("api error ({status}): {body}")]
    Api { status: u16, body: String },
    #[error("cancelled")]
    Cancelled,
    #[error("invalid response: {0}")]
    InvalidResponse(String),
    #[error("engine unavailable: {0}")]
    Unavailable(String),
}

pub type Result<T> = std::result::Result<T, InferenceError>;

// ---------------------------------------------------------------------- Langs

pub struct Langs;

impl Langs {
    pub fn display_name(code: &str) -> &'static str {
        match code.trim().to_ascii_lowercase().as_str() {
            "" | "auto" => "自动",
            "zh" | "zh-cn" | "zh-hans" => "中文",
            "en" => "英文",
            "ja" => "日文",
            "ko" => "韩文",
            "fr" => "法文",
            "de" => "德文",
            "es" => "西班牙文",
            "ru" => "俄文",
            _ => "英文",
        }
    }

    /// Lightweight script heuristic for blank/auto ASR language tags.
    pub fn guess_language(text: &str) -> &'static str {
        let mut cjk = 0usize;
        let mut kana = 0usize;
        let mut hangul = 0usize;
        let mut latin = 0usize;
        for ch in text.chars() {
            match ch as u32 {
                0x3040..=0x30FF => kana += 1,
                0xAC00..=0xD7AF => hangul += 1,
                0x4E00..=0x9FFF => cjk += 1,
                _ if ch.is_ascii_alphabetic() => latin += 1,
                _ => {}
            }
        }
        if kana > 0 && kana * 4 >= cjk {
            "ja"
        } else if hangul > 0 && hangul >= cjk {
            "ko"
        } else if cjk > 0 && cjk * 4 >= latin {
            "zh"
        } else {
            "en"
        }
    }

    /// Resolve effective `(src_lang, tgt_lang)` when either side is `"auto"` or blank.
    pub fn resolve_pair(src_hint: &str, target_pref: &str, text: &str) -> (String, String) {
        let src = if src_hint.trim().is_empty() || src_hint.eq_ignore_ascii_case("auto") {
            Self::guess_language(text).to_string()
        } else {
            src_hint.trim().to_ascii_lowercase()
        };
        let tgt = if target_pref.trim().is_empty() || target_pref.eq_ignore_ascii_case("auto") {
            if src.starts_with("zh") {
                "en".to_string()
            } else {
                "zh".to_string()
            }
        } else {
            target_pref.trim().to_ascii_lowercase()
        };
        (src, tgt)
    }
}

// ---------------------------------------------------------------------- Think filter

pub struct ThinkFilter;

impl ThinkFilter {
    /// Strip `<think>…</think>` blocks as well as orphan closing tags (`思考内容</think>正文`).
    pub fn strip(text: &str) -> String {
        static BLOCK_RE: OnceLock<Regex> = OnceLock::new();
        static ORPHAN_RE: OnceLock<Regex> = OnceLock::new();
        static TAG_RE: OnceLock<Regex> = OnceLock::new();

        let block_re = BLOCK_RE.get_or_init(|| {
            Regex::new(r"(?s)<think[^>]*>.*?(?:</think[^>]*>|<｜end▁of▁thinking｜>|$)").unwrap()
        });
        let orphan_re = ORPHAN_RE.get_or_init(|| {
            Regex::new(r"(?s)^.*?(?:</think[^>]*>|<｜end▁of▁thinking｜>)").unwrap()
        });
        let tag_re =
            TAG_RE.get_or_init(|| Regex::new(r"</?think[^>]*>|<｜end▁of▁thinking｜>").unwrap());

        let mut s = block_re.replace_all(text, "").into_owned();
        if s.contains("</think") || s.contains("<｜end▁of▁thinking｜>") {
            s = orphan_re.replace(&s, "").into_owned();
        }
        tag_re.replace_all(&s, "").trim().to_string()
    }
}

/// Streaming `<think>` filter that suppresses reasoning tokens without breaking split markers.
#[derive(Default)]
pub struct ThinkStreamFilter {
    buffer: String,
    in_think: bool,
}

impl ThinkStreamFilter {
    const END_MARKERS: [&'static str; 2] = ["</think", "<｜end▁of▁thinking｜>"];

    pub fn new() -> Self {
        Self::default()
    }

    fn longest_marker_prefix(text: &str, markers: &[&str]) -> usize {
        // Check by char boundary so multibyte UTF-8 markers (`｜`, `▁`) never split mid-codepoint.
        let max_bytes = markers.iter().map(|m| m.len()).max().unwrap_or(0);
        let start_search = text.len().saturating_sub(max_bytes);
        for (idx, _) in text.char_indices() {
            if idx < start_search {
                continue;
            }
            let tail = &text[idx..];
            if markers.iter().any(|m| m.starts_with(tail)) {
                return tail.len();
            }
        }
        0
    }

    pub fn feed(&mut self, chunk: &str) -> String {
        self.buffer.push_str(chunk);
        let mut out = String::new();
        while !self.buffer.is_empty() {
            if self.in_think {
                let end1 = self.buffer.find("</think");
                let end2 = self.buffer.find("<｜end▁of▁thinking｜>");
                let end = match (end1, end2) {
                    (Some(a), Some(b)) => Some(a.min(b)),
                    (Some(a), None) => Some(a),
                    (None, Some(b)) => Some(b),
                    (None, None) => None,
                };
                match end {
                    None => {
                        let keep = Self::longest_marker_prefix(&self.buffer, &Self::END_MARKERS);
                        if keep > 0 {
                            let drain_to = self.buffer.len() - keep;
                            self.buffer.drain(..drain_to);
                        } else {
                            self.buffer.clear();
                        }
                        break;
                    }
                    Some(pos) => {
                        if let Some(rel_close) = self.buffer[pos..].find('>') {
                            self.buffer.drain(..(pos + rel_close + 1));
                            self.in_think = false;
                        } else {
                            self.buffer.drain(..pos);
                            break;
                        }
                    }
                }
            } else if let Some(start) = self.buffer.find("<think") {
                out.push_str(&self.buffer[..start]);
                if let Some(rel_close) = self.buffer[start..].find('>') {
                    self.buffer.drain(..(start + rel_close + 1));
                    self.in_think = true;
                } else {
                    self.buffer.drain(..start);
                    break;
                }
            } else if let Some(lt) = self.buffer.rfind('<') {
                let tail = &self.buffer[lt..];
                if tail.len() <= 12 && "<think".starts_with(tail) {
                    out.push_str(&self.buffer[..lt]);
                    self.buffer.drain(..lt);
                    break;
                }
                out.push_str(&self.buffer);
                self.buffer.clear();
            } else {
                out.push_str(&self.buffer);
                self.buffer.clear();
            }
        }
        out
    }

    pub fn flush(&mut self) -> String {
        if self.in_think {
            self.buffer.clear();
            String::new()
        } else {
            std::mem::take(&mut self.buffer)
        }
    }
}

// ---------------------------------------------------------------------- Prompts

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

impl ChatMessage {
    pub fn system(content: impl Into<String>) -> Self {
        Self {
            role: "system".into(),
            content: content.into(),
        }
    }
    pub fn user(content: impl Into<String>) -> Self {
        Self {
            role: "user".into(),
            content: content.into(),
        }
    }
}

pub struct PromptBuilder;

impl PromptBuilder {
    pub fn translation_messages(
        text: &str,
        src_lang: &str,
        tgt_lang: &str,
        glossary: &[GlossaryEntry],
        recent_context: &[String],
    ) -> Vec<ChatMessage> {
        let tgt_name = Langs::display_name(tgt_lang);
        let src_name = Langs::display_name(src_lang);
        let mut sys = format!(
            "你是专业实时同传译员。将{src_name}准确、自然地翻译成{tgt_name}。\
             只输出译文正文，不要解释、不要加引号、不要重复原文。"
        );
        if !glossary.is_empty() {
            sys.push_str("\n课程术语表（优先遵守）：");
            for g in glossary.iter().take(40) {
                if !g.term.is_empty() && !g.translation.is_empty() {
                    sys.push_str(&format!("\n- {} => {}", g.term, g.translation));
                }
            }
        }
        let mut user = String::new();
        if !recent_context.is_empty() {
            user.push_str("前文语境（仅供指代参考，不要翻译）：\n");
            for line in recent_context.iter().rev().take(3).rev() {
                user.push_str(line);
                user.push('\n');
            }
            user.push_str("\n请翻译本句：\n");
        }
        user.push_str(text.trim());
        vec![ChatMessage::system(sys), ChatMessage::user(user)]
    }

    pub fn knowledge_points_messages(transcript: &str, course_name: &str) -> Vec<ChatMessage> {
        let sys = "你是高效课堂学习助教。请从课堂转录中提炼最核心的 4~8 个知识点，供学生在手机上快速复习。\
                   严格输出 JSON 数组，每个元素包含：title（简短知识点标题）、explanation（2~3 句清晰解释）、\
                   importance（1=了解, 2=重点, 3=核心考点）、tags（字符串数组）、source_quote（课堂原话摘录）。\
                   不要输出任何多余文字。";
        let user = format!("课程：{course_name}\n\n课堂内容：\n{transcript}");
        vec![ChatMessage::system(sys), ChatMessage::user(user)]
    }

    pub fn summary_and_title_messages(transcript: &str) -> Vec<ChatMessage> {
        let sys = "你是课堂笔记助教。请输出 JSON 对象：{\"title\": \"12字以内课程主题标题\", \"summary\": \"3~5句简明课堂总结\"}。只输出 JSON。";
        vec![
            ChatMessage::system(sys),
            ChatMessage::user(transcript.to_string()),
        ]
    }

    pub fn glossary_extract_messages(transcript: &str) -> Vec<ChatMessage> {
        let sys = "请从课堂内容提取最多 10 个值得记住的专业术语或英文缩写。\
                   严格输出 JSON 数组：[{\"term\":\"...\",\"translation\":\"...\",\"note\":\"一句话解释\"}]。";
        vec![
            ChatMessage::system(sys),
            ChatMessage::user(transcript.to_string()),
        ]
    }

    pub fn question_answer_messages(question: &str, context: &str) -> Vec<ChatMessage> {
        let sys = "你是课堂实时助教。结合近期课堂语境，用 2~4 句准确、易懂的中文回答老师或学生提出的问题。";
        let user = format!("课堂语境：\n{context}\n\n问题：{question}");
        vec![ChatMessage::system(sys), ChatMessage::user(user)]
    }

    pub fn qa_with_slides_messages(
        question: &str,
        transcript_context: &str,
        slide_snippets: &str,
    ) -> Vec<ChatMessage> {
        let sys = "你是课堂实时助教。优先结合课堂记录与课件片段用 3~5 句清晰中文回答问题；\
                   若引用了课件片段，请在回答中注明「课件第 X 页」。不知道就诚实说明。";
        let mut user = String::new();
        if !slide_snippets.trim().is_empty() {
            user.push_str("相关课件片段：\n");
            user.push_str(slide_snippets.trim());
            user.push_str("\n\n");
        }
        if !transcript_context.trim().is_empty() {
            user.push_str("近期课堂记录：\n");
            user.push_str(transcript_context.trim());
            user.push_str("\n\n");
        }
        user.push_str("问题：");
        user.push_str(question.trim());
        vec![ChatMessage::system(sys), ChatMessage::user(user)]
    }

    pub fn recap_messages(recent_transcript: &str, minutes: u32) -> Vec<ChatMessage> {
        let sys = format!(
            "你是课堂助教。请用通俗易懂的中文总结最近约 {minutes} 分钟的课堂重点：\
             输出 3~5 条要点（每行以「- 」开头），涉及关键概念用一句大白话解释。不要提「字幕」「转录」。"
        );
        vec![
            ChatMessage::system(sys),
            ChatMessage::user(recent_transcript.to_string()),
        ]
    }

    pub fn quiz_messages(transcript: &str, course_name: &str) -> Vec<ChatMessage> {
        let sys = "你是课堂复习助教。根据本节课堂内容出 5 道高质量课后自测题（概念辨析/理解应用为主）。\
                   每道题格式：\n1. 题目内容\n答：简明参考答案\n用中文输出，条理清晰。";
        let user = format!("课程：{course_name}\n\n课堂内容：\n{transcript}");
        vec![ChatMessage::system(sys), ChatMessage::user(user)]
    }

    pub fn course_outline_messages(material_text: &str, course_name: &str) -> Vec<ChatMessage> {
        let sys = "你是课程助教。请根据课件与讲义生成一份课前预习提纲：\
                   包含 4~6 条核心章节要点，以及 3 个值得带着听课思考的问题。用中文，结构清晰。";
        let user = format!("课程：{course_name}\n\n课件摘录：\n{material_text}");
        vec![ChatMessage::system(sys), ChatMessage::user(user)]
    }
}

// ---------------------------------------------------------------------- Traits & OpenAI client

#[derive(Debug, Clone)]
pub struct GenerateOptions {
    pub temperature: f32,
    pub max_tokens: u32,
}

impl Default for GenerateOptions {
    fn default() -> Self {
        Self {
            temperature: 0.0,
            max_tokens: 512,
        }
    }
}

#[async_trait]
pub trait LlmClient: Send + Sync {
    async fn complete(
        &self,
        messages: &[ChatMessage],
        opts: &GenerateOptions,
        cancel: CancellationToken,
    ) -> Result<String>;

    async fn stream_complete(
        &self,
        messages: &[ChatMessage],
        opts: &GenerateOptions,
        cancel: CancellationToken,
        on_delta: Box<dyn for<'a> FnMut(&'a str) + Send>,
    ) -> Result<String> {
        let full = self.complete(messages, opts, cancel).await?;
        let mut cb = on_delta;
        cb(&full);
        Ok(full)
    }
}

#[async_trait]
pub trait AsrEngine: Send + Sync {
    /// Transcribe 16 kHz mono float32 PCM samples, returning `(detected_lang, text)`.
    async fn transcribe(&self, pcm_16k: &[f32], lang_hint: &str) -> Result<(String, String)>;
}

/// Shared pooled OpenAI-compatible HTTP client (works with llama-server, Ollama, LM Studio, DeepSeek, OpenAI, Gemini OpenAI endpoint).
#[derive(Clone)]
pub struct OpenAiCompatClient {
    base_url: String,
    api_key: String,
    model: String,
    client: Client,
}

fn shared_http_client() -> Client {
    static CLIENT: OnceLock<Client> = OnceLock::new();
    CLIENT
        .get_or_init(|| {
            Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .pool_max_idle_per_host(6)
                .build()
                .expect("failed to build reqwest client")
        })
        .clone()
}

impl OpenAiCompatClient {
    pub fn new(
        base_url: impl Into<String>,
        api_key: impl Into<String>,
        model: impl Into<String>,
    ) -> Self {
        let mut url = base_url.into().trim().trim_end_matches('/').to_string();
        if url.is_empty() {
            url = "http://127.0.0.1:8080/v1".to_string();
        }
        Self {
            base_url: url,
            api_key: api_key.into(),
            model: model.into(),
            client: shared_http_client(),
        }
    }

    fn chat_url(&self) -> String {
        if self.base_url.ends_with("/chat/completions") {
            self.base_url.clone()
        } else if self.base_url.ends_with("/v1") {
            format!("{}/chat/completions", self.base_url)
        } else {
            format!("{}/v1/chat/completions", self.base_url)
        }
    }

    /// Probe `/models` and `/v1/models` safely (falls through if `/models` returns HTML 200).
    pub async fn list_models(&self) -> Result<Vec<String>> {
        let candidates = if self.base_url.ends_with("/v1") {
            vec![format!("{}/models", self.base_url)]
        } else {
            vec![
                format!("{}/models", self.base_url),
                format!("{}/v1/models", self.base_url),
            ]
        };

        for url in candidates {
            let mut req = self.client.get(&url).timeout(Duration::from_secs(10));
            if !self.api_key.is_empty() {
                req = req.bearer_auth(&self.api_key);
            }
            let Ok(resp) = req.send().await else {
                continue;
            };
            if !resp.status().is_success() {
                continue;
            }
            let Ok(text) = resp.text().await else {
                continue;
            };
            if let Ok(val) = serde_json::from_str::<serde_json::Value>(&text) {
                if let Some(arr) = val.get("data").and_then(|d| d.as_array()) {
                    let ids: Vec<String> = arr
                        .iter()
                        .filter_map(|item| item.get("id").and_then(|i| i.as_str()))
                        .map(String::from)
                        .collect();
                    if !ids.is_empty() {
                        return Ok(ids);
                    }
                }
            }
        }
        Ok(Vec::new())
    }
}

#[async_trait]
impl LlmClient for OpenAiCompatClient {
    async fn complete(
        &self,
        messages: &[ChatMessage],
        opts: &GenerateOptions,
        cancel: CancellationToken,
    ) -> Result<String> {
        let body = serde_json::json!({
            "model": self.model,
            "messages": messages,
            "temperature": opts.temperature,
            "max_tokens": opts.max_tokens,
            "stream": false
        });
        let mut req = self
            .client
            .post(self.chat_url())
            .timeout(Duration::from_secs(60))
            .json(&body);
        if !self.api_key.is_empty() {
            req = req.bearer_auth(&self.api_key);
        }
        let resp = tokio::select! {
            _ = cancel.cancelled() => return Err(InferenceError::Cancelled),
            r = req.send() => r?,
        };
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(InferenceError::Api {
                status: status.as_u16(),
                body: text.chars().take(512).collect(),
            });
        }
        let val: serde_json::Value = serde_json::from_str(&text)
            .map_err(|e| InferenceError::InvalidResponse(e.to_string()))?;
        let raw = val["choices"][0]["message"]["content"]
            .as_str()
            .unwrap_or_default();
        Ok(ThinkFilter::strip(raw))
    }

    async fn stream_complete(
        &self,
        messages: &[ChatMessage],
        opts: &GenerateOptions,
        cancel: CancellationToken,
        mut on_delta: Box<dyn for<'a> FnMut(&'a str) + Send>,
    ) -> Result<String> {
        let body = serde_json::json!({
            "model": self.model,
            "messages": messages,
            "temperature": opts.temperature,
            "max_tokens": opts.max_tokens,
            "stream": true
        });
        let mut req = self
            .client
            .post(self.chat_url())
            .timeout(Duration::from_secs(90))
            .json(&body);
        if !self.api_key.is_empty() {
            req = req.bearer_auth(&self.api_key);
        }
        let resp = tokio::select! {
            _ = cancel.cancelled() => return Err(InferenceError::Cancelled),
            r = req.send() => r?,
        };
        let status = resp.status();
        if !status.is_success() {
            let text = resp.text().await.unwrap_or_default();
            return Err(InferenceError::Api {
                status: status.as_u16(),
                body: text.chars().take(512).collect(),
            });
        }

        let mut stream = resp.bytes_stream();
        let mut line_buf = String::new();
        let mut raw_accum = String::new();
        let mut diag_buf = String::new();
        let mut filter = ThinkStreamFilter::new();

        while let Some(next_item) = tokio::select! {
            _ = cancel.cancelled() => return Err(InferenceError::Cancelled),
            item = stream.next() => item,
        } {
            let chunk = next_item?;
            let text = String::from_utf8_lossy(&chunk);
            if diag_buf.len() < 4096 {
                let remain = 4096 - diag_buf.len();
                diag_buf.push_str(&text.chars().take(remain).collect::<String>());
            }
            line_buf.push_str(&text);
            while let Some(nl) = line_buf.find('\n') {
                let line = line_buf[..nl].trim().to_string();
                line_buf.drain(..=nl);
                if let Some(payload) = line.strip_prefix("data:") {
                    let payload = payload.trim();
                    if payload == "[DONE]" {
                        break;
                    }
                    if let Ok(val) = serde_json::from_str::<serde_json::Value>(payload) {
                        if let Some(delta) = val["choices"][0]["delta"]["content"].as_str() {
                            raw_accum.push_str(delta);
                            let clean = filter.feed(delta);
                            if !clean.is_empty() {
                                on_delta(&clean);
                            }
                        }
                    }
                }
            }
        }
        let rem = filter.flush();
        if !rem.is_empty() {
            on_delta(&rem);
        }
        Ok(ThinkFilter::strip(&raw_accum))
    }
}

/// OpenAI / whisper.cpp compatible HTTP ASR client (`POST /v1/audio/transcriptions` or `/inference`).
/// Works seamlessly with local `whisper-server`, `faster-whisper-server`, `mlx-audio`, SenseVoice HTTP, or OpenAI Whisper.
#[derive(Clone)]
pub struct OpenAiWhisperAsrClient {
    base_url: String,
    api_key: String,
    model: String,
    client: Client,
}

impl OpenAiWhisperAsrClient {
    pub fn new(
        base_url: impl Into<String>,
        api_key: impl Into<String>,
        model: impl Into<String>,
    ) -> Self {
        let mut url = base_url.into().trim().trim_end_matches('/').to_string();
        if url.is_empty() {
            url = "http://127.0.0.1:8178".to_string();
        }
        Self {
            base_url: url,
            api_key: api_key.into(),
            model: model.into(),
            client: shared_http_client(),
        }
    }

    fn transcription_url(&self) -> String {
        if self.base_url.ends_with("/audio/transcriptions")
            || self.base_url.ends_with("/inference")
        {
            self.base_url.clone()
        } else if self.base_url.ends_with("/v1") {
            format!("{}/audio/transcriptions", self.base_url)
        } else {
            format!("{}/v1/audio/transcriptions", self.base_url)
        }
    }
}

#[async_trait]
impl AsrEngine for OpenAiWhisperAsrClient {
    async fn transcribe(&self, pcm_16k: &[f32], lang_hint: &str) -> Result<(String, String)> {
        if pcm_16k.is_empty() {
            return Ok((String::new(), String::new()));
        }
        let wav = core_dsp::encode_wav_16k_mono_pcm16(pcm_16k);
        let file_part = reqwest::multipart::Part::bytes(wav)
            .file_name("segment.wav")
            .mime_str("audio/wav")
            .map_err(|e| InferenceError::InvalidResponse(e.to_string()))?;

        let mut form = reqwest::multipart::Form::new()
            .part("file", file_part)
            .text("response_format", "verbose_json");
        if !self.model.trim().is_empty() {
            form = form.text("model", self.model.clone());
        }
        let clean_hint = lang_hint.trim();
        if !clean_hint.is_empty() && !clean_hint.eq_ignore_ascii_case("auto") {
            form = form.text("language", clean_hint.to_ascii_lowercase());
        }

        let mut req = self
            .client
            .post(self.transcription_url())
            .timeout(Duration::from_secs(30))
            .multipart(form);
        if !self.api_key.is_empty() {
            req = req.bearer_auth(&self.api_key);
        }
        let resp = req.send().await?;
        let status = resp.status();
        let body = resp.text().await?;
        if !status.is_success() {
            return Err(InferenceError::Api {
                status: status.as_u16(),
                body: body.chars().take(512).collect(),
            });
        }
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&body) {
            let text = val["text"].as_str().unwrap_or_default().trim().to_string();
            let detected = val["language"]
                .as_str()
                .map(normalize_lang_code)
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| Langs::guess_language(&text).to_string());
            Ok((detected, text))
        } else {
            let text = body.trim().to_string();
            let detected = Langs::guess_language(&text).to_string();
            Ok((detected, text))
        }
    }
}

fn normalize_lang_code(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "chinese" | "zh" | "zh-cn" | "zh-hans" | "mandarin" => "zh".into(),
        "english" | "en" => "en".into(),
        "japanese" | "ja" => "ja".into(),
        "korean" | "ko" => "ko".into(),
        "french" | "fr" => "fr".into(),
        "german" | "de" => "de".into(),
        "spanish" | "es" => "es".into(),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn langs_guess_and_resolve_never_produce_zh_to_zh_on_auto() {
        assert_eq!(Langs::display_name("auto"), "自动");
        assert_eq!(Langs::guess_language("今天讲Transformer原理"), "zh");
        assert_eq!(Langs::guess_language("Today we cover attention"), "en");
        assert_eq!(Langs::guess_language("こんにちは世界"), "ja");

        let (s1, t1) = Langs::resolve_pair("", "auto", "这是中文句子");
        assert_eq!((s1.as_str(), t1.as_str()), ("zh", "en"));

        let (s2, t2) = Langs::resolve_pair("auto", "auto", "Hello world");
        assert_eq!((s2.as_str(), t2.as_str()), ("en", "zh"));
    }

    #[test]
    fn think_filter_strips_paired_and_orphan_closing_tags() {
        assert_eq!(
            ThinkFilter::strip("<think>internal reasoning</think>\n最终译文"),
            "最终译文"
        );
        assert_eq!(
            ThinkFilter::strip("先思考一下翻译细节</think>正式输出"),
            "正式输出"
        );
        assert_eq!(
            ThinkFilter::strip("reasoning<｜end▁of▁thinking｜>Final answer"),
            "Final answer"
        );
    }

    #[test]
    fn think_stream_filter_handles_split_tags_and_multibyte_markers() {
        let mut f = ThinkStreamFilter::new();
        let mut out = String::new();
        for chunk in ["<th", "ink>secret", " plan</th", "ink>你好", "世界"] {
            out.push_str(&f.feed(chunk));
        }
        out.push_str(&f.flush());
        assert_eq!(out, "你好世界");

        let mut f2 = ThinkStreamFilter::new();
        let mut out2 = String::new();
        for chunk in ["<think>思", "考<｜end", "▁of▁thinking｜>正文"] {
            out2.push_str(&f2.feed(chunk));
        }
        out2.push_str(&f2.flush());
        assert_eq!(out2, "正文");
    }
}
