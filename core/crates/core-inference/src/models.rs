//! Offline GGUF/Whisper model catalog and resumable HTTP Range downloader with HuggingFace mirror support.

use std::path::{Path, PathBuf};
use std::time::Duration;

use futures_util::StreamExt;
use reqwest::header::RANGE;
use serde::{Deserialize, Serialize};
use tokio::io::AsyncWriteExt;
use tokio_util::sync::CancellationToken;

use crate::{shared_http_client, InferenceError, Result};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModelSpec {
    pub id: String,
    pub name: String,
    pub role: String, // "translator" | "assistant" | "asr"
    pub filename: String,
    pub repo_path: String, // e.g. "owner/repo/resolve/main/file.gguf"
    pub approx_size_mb: u64,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModelStatus {
    pub spec: ModelSpec,
    pub local_path: String,
    pub downloaded_bytes: u64,
    pub is_ready: bool,
}

/// Curated offline models tuned for Apple Silicon Mac (Metal) & Android Tablet.
pub fn recommended_models() -> Vec<ModelSpec> {
    vec![
        ModelSpec {
            id: "hy-mt2-1.8b-q4".into(),
            name: "HY-MT2 1.8B (Q4_K_M)".into(),
            role: "translator".into(),
            filename: "HY-MT2-1.8B-Q4_K_M.gguf".into(),
            repo_path: "tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf".into(),
            approx_size_mb: 1120,
            description: "主力离线同传翻译模型：低延迟、中英互译自然、显存占用低".into(),
        },
        ModelSpec {
            id: "qwen2.5-3b-q4".into(),
            name: "Qwen2.5 3B Instruct (Q4_K_M)".into(),
            role: "assistant".into(),
            filename: "qwen2.5-3b-instruct-q4_k_m.gguf".into(),
            repo_path: "Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
                .into(),
            approx_size_mb: 1930,
            description: "课堂助教与知识点提炼模型：总结、答疑、术语提取与出复习题".into(),
        },
        ModelSpec {
            id: "whisper-large-v3-turbo-q5".into(),
            name: "Whisper Large-v3-Turbo (Q5_0)".into(),
            role: "asr".into(),
            filename: "ggml-large-v3-turbo-q5_0.bin".into(),
            repo_path: "ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin".into(),
            approx_size_mb: 574,
            description: "whisper.cpp 本地多语种高精度语音识别模型（配合 whisper-server）".into(),
        },
    ]
}

pub struct ModelDownloader {
    models_dir: PathBuf,
}

impl ModelDownloader {
    pub fn new(models_dir: impl AsRef<Path>) -> Self {
        Self {
            models_dir: models_dir.as_ref().to_path_buf(),
        }
    }

    pub fn models_dir(&self) -> &Path {
        &self.models_dir
    }

    pub fn model_path(&self, spec: &ModelSpec) -> PathBuf {
        self.models_dir.join(&spec.filename)
    }

    pub fn list_status(&self) -> Vec<ModelStatus> {
        recommended_models()
            .into_iter()
            .map(|spec| {
                let path = self.model_path(&spec);
                let part = self.models_dir.join(format!("{}.part", spec.filename));
                let (is_ready, downloaded_bytes) = if let Ok(meta) = std::fs::metadata(&path) {
                    (meta.len() > 1024 * 1024, meta.len())
                } else if let Ok(meta) = std::fs::metadata(&part) {
                    (false, meta.len())
                } else {
                    (false, 0)
                };
                ModelStatus {
                    spec,
                    local_path: path.to_string_lossy().to_string(),
                    downloaded_bytes,
                    is_ready,
                }
            })
            .collect()
    }

    pub fn delete_model(&self, model_id: &str) -> Result<bool> {
        let Some(spec) = recommended_models().into_iter().find(|m| m.id == model_id) else {
            return Ok(false);
        };
        let path = self.model_path(&spec);
        let part = self.models_dir.join(format!("{}.part", spec.filename));
        let mut removed = false;
        if path.exists() {
            let _ = std::fs::remove_file(&path);
            removed = true;
        }
        if part.exists() {
            let _ = std::fs::remove_file(&part);
            removed = true;
        }
        Ok(removed)
    }

    pub fn resolve_url(spec: &ModelSpec, use_hf_mirror: bool) -> String {
        let host = if use_hf_mirror {
            "https://hf-mirror.com"
        } else {
            "https://huggingface.co"
        };
        format!("{host}/{}", spec.repo_path.trim_start_matches('/'))
    }

    /// Resumable download with HTTP `Range`, atomic `.part` -> final rename, and cancellation support.
    pub async fn download_by_id<F>(
        &self,
        model_id: &str,
        use_hf_mirror: bool,
        cancel: CancellationToken,
        mut on_progress: F,
    ) -> Result<PathBuf>
    where
        F: FnMut(u64, u64) + Send,
    {
        let spec = recommended_models()
            .into_iter()
            .find(|m| m.id == model_id)
            .ok_or_else(|| InferenceError::Unavailable(format!("unknown model id: {model_id}")))?;
        self.download_spec(&spec, use_hf_mirror, cancel, &mut on_progress)
            .await
    }

    pub async fn download_spec<F>(
        &self,
        spec: &ModelSpec,
        use_hf_mirror: bool,
        cancel: CancellationToken,
        on_progress: &mut F,
    ) -> Result<PathBuf>
    where
        F: FnMut(u64, u64) + Send,
    {
        let url = Self::resolve_url(spec, use_hf_mirror);
        self.download_url(&url, &spec.filename, cancel, on_progress)
            .await
    }

    pub async fn download_url<F>(
        &self,
        url: &str,
        filename: &str,
        cancel: CancellationToken,
        on_progress: &mut F,
    ) -> Result<PathBuf>
    where
        F: FnMut(u64, u64) + Send,
    {
        tokio::fs::create_dir_all(&self.models_dir)
            .await
            .map_err(|e| InferenceError::Unavailable(e.to_string()))?;

        let final_path = self.models_dir.join(filename);
        let part_path = self.models_dir.join(format!("{filename}.part"));

        let existing_len = tokio::fs::metadata(&part_path)
            .await
            .map(|m| m.len())
            .unwrap_or(0);

        let client = shared_http_client();
        let mut req = client.get(url).timeout(Duration::from_secs(3600));
        if existing_len > 0 {
            req = req.header(RANGE, format!("bytes={existing_len}-"));
        }

        let resp = tokio::select! {
            _ = cancel.cancelled() => return Err(InferenceError::Cancelled),
            r = req.send() => r?,
        };
        let status = resp.status();
        if !status.is_success() && status.as_u16() != 206 {
            let body = resp.text().await.unwrap_or_default();
            return Err(InferenceError::Api {
                status: status.as_u16(),
                body: body.chars().take(256).collect(),
            });
        }

        let resumed = status.as_u16() == 206 && existing_len > 0;
        let start_offset = if resumed { existing_len } else { 0 };
        let content_len = resp.content_length().unwrap_or(0);
        let total_bytes = if content_len > 0 {
            start_offset + content_len
        } else {
            0
        };

        let mut file = if resumed {
            tokio::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&part_path)
                .await
                .map_err(|e| InferenceError::Unavailable(e.to_string()))?
        } else {
            tokio::fs::File::create(&part_path)
                .await
                .map_err(|e| InferenceError::Unavailable(e.to_string()))?
        };

        let mut downloaded = start_offset;
        on_progress(downloaded, total_bytes);

        let mut stream = resp.bytes_stream();
        while let Some(item) = tokio::select! {
            _ = cancel.cancelled() => return Err(InferenceError::Cancelled),
            next = stream.next() => next,
        } {
            let chunk = item?;
            file.write_all(&chunk)
                .await
                .map_err(|e| InferenceError::Unavailable(e.to_string()))?;
            downloaded += chunk.len() as u64;
            on_progress(downloaded, total_bytes);
        }

        file.flush()
            .await
            .map_err(|e| InferenceError::Unavailable(e.to_string()))?;
        drop(file);

        tokio::fs::rename(&part_path, &final_path)
            .await
            .map_err(|e| InferenceError::Unavailable(e.to_string()))?;

        Ok(final_path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recommended_models_resolve_mirror_and_hf_urls() {
        let models = recommended_models();
        assert_eq!(models.len(), 3);
        let mirror_url = ModelDownloader::resolve_url(&models[0], true);
        let hf_url = ModelDownloader::resolve_url(&models[0], false);
        assert!(mirror_url.starts_with("https://hf-mirror.com/"));
        assert!(hf_url.starts_with("https://huggingface.co/"));
    }
}
