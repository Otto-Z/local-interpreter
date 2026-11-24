//! Real-time interpretation pipeline, bilingual question detector,
//! post-class knowledge-point extraction, and Markdown knowledge exporter.

use std::sync::{Arc, OnceLock};

use core_inference::{GenerateOptions, Langs, LlmClient, PromptBuilder, ThinkFilter};
use core_model::{parse_knowledge_points, GlossaryEntry, KnowledgePoint, Segment, Session};
use core_storage::Store;
use regex::Regex;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio_util::sync::CancellationToken;

#[derive(Debug, Error)]
pub enum PipelineError {
    #[error("storage error: {0}")]
    Storage(#[from] core_storage::StorageError),
    #[error("inference error: {0}")]
    Inference(#[from] core_inference::InferenceError),
}

pub type Result<T> = std::result::Result<T, PipelineError>;

/// Detect whether either the source or translated utterance looks like a real classroom question.
pub fn looks_like_question(src: &str, tgt: &str) -> bool {
    static EN_Q_RE: OnceLock<Regex> = OnceLock::new();
    let en_re = EN_Q_RE.get_or_init(|| {
        Regex::new(r"(?i)^(what|why|how|when|where|who|which|can|could|would|should|do|does|did|is|are|was|were|have|has|will)\b").unwrap()
    });

    for candidate in [src.trim(), tgt.trim()] {
        if candidate.chars().count() < 4 {
            continue;
        }
        if candidate.ends_with('?') || candidate.ends_with('？') {
            return true;
        }
        if en_re.is_match(candidate) && candidate.split_whitespace().count() >= 4 {
            return true;
        }
        for kw in ["为什么", "怎么", "如何", "什么意思", "区别是什么", "吗？", "呢？"] {
            if candidate.contains(kw) {
                return true;
            }
        }
    }
    false
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FinalizeResult {
    pub session: Session,
    pub knowledge_points: Vec<KnowledgePoint>,
    pub glossary_added: usize,
}

/// High-level coordinator binding storage and LLM inference for live interpretation & post-class review.
pub struct PipelineCoordinator {
    store: Store,
    translator: Arc<dyn LlmClient>,
    assistant: Arc<dyn LlmClient>,
}

impl PipelineCoordinator {
    pub fn new(
        store: Store,
        translator: Arc<dyn LlmClient>,
        assistant: Arc<dyn LlmClient>,
    ) -> Self {
        Self {
            store,
            translator,
            assistant,
        }
    }

    /// Translate and persist a newly recognized utterance with course glossary + rolling context.
    #[allow(clippy::too_many_arguments)]
    pub async fn process_utterance(
        &self,
        session_id: &str,
        course_id: Option<&str>,
        t0: f64,
        t1: f64,
        src_lang_hint: &str,
        target_pref: &str,
        src_text: &str,
        low_conf: bool,
        cancel: CancellationToken,
    ) -> Result<Segment> {
        let clean_src = src_text.trim();
        let (src_lang, tgt_lang) = Langs::resolve_pair(src_lang_hint, target_pref, clean_src);
        let glossary = match course_id {
            Some(cid) => self.store.list_glossary(Some(cid)).unwrap_or_default(),
            None => Vec::new(),
        };
        let recent_segs = self.store.list_segments(session_id).unwrap_or_default();
        let recent_ctx: Vec<String> = recent_segs
            .iter()
            .rev()
            .take(3)
            .rev()
            .map(|s| s.src_text.clone())
            .collect();

        let tgt_text = if clean_src.is_empty() {
            String::new()
        } else {
            let msgs = PromptBuilder::translation_messages(
                clean_src,
                &src_lang,
                &tgt_lang,
                &glossary,
                &recent_ctx,
            );
            let opts = GenerateOptions {
                temperature: 0.0,
                max_tokens: 384,
            };
            self.translator
                .complete(&msgs, &opts, cancel)
                .await
                .map(|s| ThinkFilter::strip(&s))
                .unwrap_or_default()
        };

        let seg = self.store.add_segment(
            session_id,
            t0,
            t1,
            &src_lang,
            clean_src,
            &tgt_lang,
            &tgt_text,
            low_conf,
        )?;
        Ok(seg)
    }

    /// Post-class knowledge extraction: generates title, concise summary, structured knowledge points
    /// (for iPhone review), and optionally extracts new course glossary terms.
    pub async fn finalize_session_knowledge(
        &self,
        session_id: &str,
        extract_glossary: bool,
        cancel: CancellationToken,
    ) -> Result<FinalizeResult> {
        let session = self
            .store
            .get_session(session_id)?
            .ok_or_else(|| core_storage::StorageError::NotFound(session_id.to_string()))?;
        let segments = self.store.list_segments(session_id)?;
        let course_name = match &session.course_id {
            Some(cid) => self
                .store
                .get_course(cid)?
                .map(|c| c.name)
                .unwrap_or_else(|| "未分类课程".to_string()),
            None => "未分类课程".to_string(),
        };

        let transcript = build_compact_transcript(&segments, 12_000);
        if transcript.is_empty() {
            let updated = self.store.update_session_knowledge(
                session_id,
                None,
                None,
                None,
                Some("completed"),
            )?;
            return Ok(FinalizeResult {
                session: updated,
                knowledge_points: Vec::new(),
                glossary_added: 0,
            });
        }

        let opts = GenerateOptions {
            temperature: 0.2,
            max_tokens: 1024,
        };

        // 1. Knowledge points
        let kp_msgs = PromptBuilder::knowledge_points_messages(&transcript, &course_name);
        let kp_raw = self
            .assistant
            .complete(&kp_msgs, &opts, cancel.clone())
            .await
            .unwrap_or_default();
        let knowledge_points = parse_knowledge_points(&ThinkFilter::strip(&kp_raw));

        // 2. Title & summary
        let sum_msgs = PromptBuilder::summary_and_title_messages(&transcript);
        let sum_raw = self
            .assistant
            .complete(&sum_msgs, &opts, cancel.clone())
            .await
            .unwrap_or_default();
        let (title, summary) = parse_title_summary(&ThinkFilter::strip(&sum_raw));

        // 3. Optional course glossary auto-extraction
        let mut glossary_added = 0usize;
        if extract_glossary {
            if let Some(cid) = &session.course_id {
                let g_msgs = PromptBuilder::glossary_extract_messages(&transcript);
                if let Ok(g_raw) = self.assistant.complete(&g_msgs, &opts, cancel).await {
                    let terms = parse_glossary_items(&ThinkFilter::strip(&g_raw));
                    for (term, translation, note) in terms {
                        if self
                            .store
                            .upsert_glossary_term(cid, &term, &translation, &note, "ai")
                            .is_ok()
                        {
                            glossary_added += 1;
                        }
                    }
                }
            }
        }

        let updated = self.store.update_session_knowledge(
            session_id,
            title.as_deref(),
            summary.as_deref(),
            Some(knowledge_points.clone()),
            Some("completed"),
        )?;

        Ok(FinalizeResult {
            session: updated,
            knowledge_points,
            glossary_added,
        })
    }
}

/// Export a single session (knowledge points, summary, bookmarks, Q&A, glossary, transcript) as clean Markdown.
pub fn export_session_markdown(store: &Store, session_id: &str) -> Result<String> {
    let session = store
        .get_session(session_id)?
        .ok_or_else(|| core_storage::StorageError::NotFound(session_id.to_string()))?;
    let segments = store.list_segments(session_id)?;
    let bookmarks = store.list_bookmarks(Some(session_id))?;
    let questions = store.list_questions(Some(session_id))?;
    let glossary: Vec<GlossaryEntry> = match &session.course_id {
        Some(cid) => store.list_glossary(Some(cid))?,
        None => Vec::new(),
    };

    let mut md = String::new();
    let heading = if session.title.is_empty() {
        "未命名课次"
    } else {
        &session.title
    };
    md.push_str(&format!("# {heading}\n\n"));

    if !session.summary.is_empty() {
        md.push_str("## 课堂总结\n\n");
        md.push_str(&session.summary);
        md.push_str("\n\n");
    }

    if !session.knowledge_points.is_empty() {
        md.push_str("## 核心知识点\n\n");
        for (idx, kp) in session.knowledge_points.iter().enumerate() {
            let stars = "★".repeat(kp.importance.clamp(1, 3) as usize);
            md.push_str(&format!("### {}. {} ({stars})\n\n", idx + 1, kp.title));
            if !kp.explanation.is_empty() {
                md.push_str(&kp.explanation);
                md.push_str("\n\n");
            }
            if !kp.source_quote.is_empty() {
                md.push_str(&format!("> 原文摘录：{}\n\n", kp.source_quote));
            }
        }
    }

    if !bookmarks.is_empty() {
        md.push_str("## 收藏重点句\n\n");
        for bm in &bookmarks {
            if let Some(seg) = segments.iter().find(|s| s.id == bm.segment_id) {
                md.push_str(&format!("- **{}**\n  - {}\n", seg.src_text, seg.tgt_text));
                if !bm.note.is_empty() {
                    md.push_str(&format!("  - 备注：{}\n", bm.note));
                }
            }
        }
        md.push('\n');
    }

    if !questions.is_empty() {
        md.push_str("## 课堂问答\n\n");
        for q in &questions {
            md.push_str(&format!("- **Q**：{}\n  - **A**：{}\n", q.question, q.answer));
        }
        md.push('\n');
    }

    if !glossary.is_empty() {
        md.push_str("## 课程术语表\n\n| 术语 | 译文 | 备注 |\n| --- | --- | --- |\n");
        for g in &glossary {
            md.push_str(&format!("| {} | {} | {} |\n", g.term, g.translation, g.note));
        }
        md.push('\n');
    }

    if !session.user_note.is_empty() {
        md.push_str("## 个人笔记\n\n");
        md.push_str(&session.user_note);
        md.push_str("\n\n");
    }

    if !segments.is_empty() {
        md.push_str("## 双语转录\n\n");
        for seg in &segments {
            let mins = (seg.t0 / 60.0).floor() as u64;
            let secs = (seg.t0 % 60.0).floor() as u64;
            md.push_str(&format!(
                "- `[{mins:02}:{secs:02}]` {}\n  - {}\n",
                seg.src_text, seg.tgt_text
            ));
        }
    }

    Ok(md)
}

fn build_compact_transcript(segments: &[Segment], max_chars: usize) -> String {
    let mut out = String::new();
    for seg in segments {
        let line = if seg.tgt_text.is_empty() {
            format!("{}\n", seg.src_text.trim())
        } else {
            format!("{} | {}\n", seg.src_text.trim(), seg.tgt_text.trim())
        };
        if out.chars().count() + line.chars().count() > max_chars {
            break;
        }
        out.push_str(&line);
    }
    out
}

fn parse_title_summary(raw: &str) -> (Option<String>, Option<String>) {
    #[derive(Deserialize)]
    struct TsObj {
        title: Option<String>,
        summary: Option<String>,
    }
    if let (Some(s), Some(e)) = (raw.find('{'), raw.rfind('}')) {
        if e > s {
            if let Ok(obj) = serde_json::from_str::<TsObj>(&raw[s..=e]) {
                return (
                    obj.title.filter(|t| !t.trim().is_empty()),
                    obj.summary.filter(|s| !s.trim().is_empty()),
                );
            }
        }
    }
    if raw.trim().is_empty() {
        (None, None)
    } else {
        (None, Some(raw.trim().to_string()))
    }
}

fn parse_glossary_items(raw: &str) -> Vec<(String, String, String)> {
    #[derive(Deserialize)]
    struct Item {
        term: String,
        #[serde(default)]
        translation: String,
        #[serde(default)]
        note: String,
    }
    if let (Some(s), Some(e)) = (raw.find('['), raw.rfind(']')) {
        if e > s {
            if let Ok(items) = serde_json::from_str::<Vec<Item>>(&raw[s..=e]) {
                return items
                    .into_iter()
                    .filter(|i| !i.term.trim().is_empty() && !i.translation.trim().is_empty())
                    .map(|i| (i.term, i.translation, i.note))
                    .collect();
            }
        }
    }
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use core_inference::ChatMessage;

    struct MockLlm;

    #[async_trait]
    impl LlmClient for MockLlm {
        async fn complete(
            &self,
            messages: &[ChatMessage],
            _opts: &GenerateOptions,
            _cancel: CancellationToken,
        ) -> core_inference::Result<String> {
            let sys = &messages[0].content;
            if sys.contains("同传译员") {
                Ok("反向传播算法利用链式法则计算梯度。".into())
            } else if sys.contains("4~8 个知识点") {
                Ok(r#"[{"title":"反向传播","explanation":"利用链式法则逐层计算损失函数对参数的梯度。","importance":3,"tags":["深度学习"],"source_quote":"Backpropagation computes gradients via chain rule."}]"#.into())
            } else if sys.contains("课程主题标题") {
                Ok(r#"{"title":"深度学习：反向传播","summary":"本节讲解了梯度计算与链式法则。"}"#.into())
            } else {
                Ok(r#"[{"term":"Backpropagation","translation":"反向传播","note":"神经网络梯度求解算法"}]"#.into())
            }
        }
    }

    #[test]
    fn question_detection_covers_zh_and_en() {
        assert!(looks_like_question("What is the difference between L1 and L2", ""));
        assert!(looks_like_question("这节课讲了什么意思", ""));
        assert!(looks_like_question("Some statement", "为什么要用归一化？"));
        assert!(!looks_like_question("Okay", "好的"));
    }

    #[tokio::test]
    async fn full_utterance_and_knowledge_finalization_pipeline() {
        let store = Store::open_in_memory("mac-test").unwrap();
        let course = store.create_course("Deep Learning", "课堂").unwrap();
        let session = store
            .create_session(Some(course.id.clone()), "system", "auto", "auto")
            .unwrap();

        let llm = Arc::new(MockLlm);
        let pipe = PipelineCoordinator::new(store.clone(), llm.clone(), llm);
        let seg = pipe
            .process_utterance(
                &session.id,
                Some(&course.id),
                0.0,
                4.2,
                "auto",
                "auto",
                "Backpropagation computes gradients via chain rule.",
                false,
                CancellationToken::new(),
            )
            .await
            .unwrap();
        assert_eq!(seg.src_lang, "en");
        assert_eq!(seg.tgt_lang, "zh");
        assert!(seg.tgt_text.contains("反向传播"));

        let res = pipe
            .finalize_session_knowledge(&session.id, true, CancellationToken::new())
            .await
            .unwrap();
        assert_eq!(res.session.title, "深度学习：反向传播");
        assert_eq!(res.knowledge_points.len(), 1);
        assert_eq!(res.glossary_added, 1);

        let md = export_session_markdown(&store, &session.id).unwrap();
        assert!(md.contains("# 深度学习：反向传播"));
        assert!(md.contains("## 核心知识点"));
        assert!(md.contains("Backpropagation"));
    }
}
