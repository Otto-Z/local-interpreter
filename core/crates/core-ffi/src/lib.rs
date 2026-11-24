//! UniFFI bindings exposing `local_interpreter_core` to Swift (macOS / iOS) and Kotlin (Android).

use std::sync::{Arc, Mutex};

use core_dsp::{
    rms_level, AdaptiveEnergyVad, SegmenterConfig, SpeechSegment, SpeechSegmenter,
    StreamResampler, TARGET_SAMPLE_RATE,
};
use core_inference::{AsrEngine, Langs, OpenAiCompatClient, OpenAiWhisperAsrClient, ThinkFilter};
use core_model::{
    Bookmark, Course, GlossaryEntry, KnowledgePoint, Question, SearchHit, Segment, Session,
};
use core_pipeline::{export_session_markdown, looks_like_question, PipelineCoordinator};
use core_storage::legacy::import_legacy_db;
use core_storage::Store;
use core_sync::{CloudRunTransport, FolderTransport, GcsS3Transport, SyncManager, SyncStats};
use thiserror::Error;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

uniffi::setup_scaffolding!();

#[derive(Debug, Error, uniffi::Error)]
pub enum CoreError {
    #[error("storage error: {msg}")]
    Storage { msg: String },
    #[error("sync error: {msg}")]
    Sync { msg: String },
    #[error("inference error: {msg}")]
    Inference { msg: String },
    #[error("runtime error: {msg}")]
    Runtime { msg: String },
}

impl From<core_storage::StorageError> for CoreError {
    fn from(e: core_storage::StorageError) -> Self {
        Self::Storage { msg: e.to_string() }
    }
}

impl From<core_sync::SyncError> for CoreError {
    fn from(e: core_sync::SyncError) -> Self {
        Self::Sync { msg: e.to_string() }
    }
}

impl From<core_pipeline::PipelineError> for CoreError {
    fn from(e: core_pipeline::PipelineError) -> Self {
        Self::Inference { msg: e.to_string() }
    }
}

// ---------------------------------------------------------------------- FFI DTOs

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiKnowledgePoint {
    pub title: String,
    pub explanation: String,
    pub importance: u8,
    pub tags: Vec<String>,
    pub source_quote: String,
}

impl From<KnowledgePoint> for FfiKnowledgePoint {
    fn from(k: KnowledgePoint) -> Self {
        Self {
            title: k.title,
            explanation: k.explanation,
            importance: k.importance,
            tags: k.tags,
            source_quote: k.source_quote,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiCourse {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub summary: String,
    pub outline: String,
    pub notes: String,
    pub created_at: i64,
    pub updated_at: i64,
}

impl From<Course> for FfiCourse {
    fn from(c: Course) -> Self {
        Self {
            id: c.id,
            name: c.name,
            kind: c.kind,
            summary: c.summary,
            outline: c.outline,
            notes: c.notes,
            created_at: c.created_at,
            updated_at: c.updated_at,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSession {
    pub id: String,
    pub course_id: Option<String>,
    pub started_at: i64,
    pub ended_at: Option<i64>,
    pub duration_s: f64,
    pub audio_source: String,
    pub source_lang: String,
    pub target_lang: String,
    pub title: String,
    pub summary: String,
    pub knowledge_points: Vec<FfiKnowledgePoint>,
    pub user_note: String,
    pub status: String,
    pub updated_at: i64,
}

impl From<Session> for FfiSession {
    fn from(s: Session) -> Self {
        Self {
            id: s.id,
            course_id: s.course_id,
            started_at: s.started_at,
            ended_at: s.ended_at,
            duration_s: s.duration_s,
            audio_source: s.audio_source,
            source_lang: s.source_lang,
            target_lang: s.target_lang,
            title: s.title,
            summary: s.summary,
            knowledge_points: s.knowledge_points.into_iter().map(Into::into).collect(),
            user_note: s.user_note,
            status: s.status,
            updated_at: s.updated_at,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSegment {
    pub id: String,
    pub session_id: String,
    pub t0: f64,
    pub t1: f64,
    pub src_lang: String,
    pub src_text: String,
    pub tgt_lang: String,
    pub tgt_text: String,
    pub low_conf: bool,
}

impl From<Segment> for FfiSegment {
    fn from(s: Segment) -> Self {
        Self {
            id: s.id,
            session_id: s.session_id,
            t0: s.t0,
            t1: s.t1,
            src_lang: s.src_lang,
            src_text: s.src_text,
            tgt_lang: s.tgt_lang,
            tgt_text: s.tgt_text,
            low_conf: s.low_conf,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiGlossaryEntry {
    pub id: String,
    pub course_id: String,
    pub term: String,
    pub translation: String,
    pub note: String,
}

impl From<GlossaryEntry> for FfiGlossaryEntry {
    fn from(g: GlossaryEntry) -> Self {
        Self {
            id: g.id,
            course_id: g.course_id,
            term: g.term,
            translation: g.translation,
            note: g.note,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiBookmark {
    pub id: String,
    pub session_id: String,
    pub segment_id: String,
    pub note: String,
    pub created_at: i64,
}

impl From<Bookmark> for FfiBookmark {
    fn from(b: Bookmark) -> Self {
        Self {
            id: b.id,
            session_id: b.session_id,
            segment_id: b.segment_id,
            note: b.note,
            created_at: b.created_at,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiQuestion {
    pub id: String,
    pub session_id: String,
    pub question: String,
    pub answer: String,
    pub created_at: i64,
}

impl From<Question> for FfiQuestion {
    fn from(q: Question) -> Self {
        Self {
            id: q.id,
            session_id: q.session_id,
            question: q.question,
            answer: q.answer,
            created_at: q.created_at,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSearchHit {
    pub kind: String,
    pub id: String,
    pub course_id: Option<String>,
    pub session_id: Option<String>,
    pub title: String,
    pub snippet: String,
    pub updated_at: i64,
}

impl From<SearchHit> for FfiSearchHit {
    fn from(h: SearchHit) -> Self {
        Self {
            kind: h.kind,
            id: h.id,
            course_id: h.course_id,
            session_id: h.session_id,
            title: h.title,
            snippet: h.snippet,
            updated_at: h.updated_at,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSyncStats {
    pub pushed_items: u64,
    pub pulled_items: u64,
    pub skipped_stale: u64,
    pub last_pushed_at: i64,
    pub last_pulled_at: i64,
}

impl From<SyncStats> for FfiSyncStats {
    fn from(s: SyncStats) -> Self {
        Self {
            pushed_items: s.pushed_items as u64,
            pulled_items: s.pulled_items as u64,
            skipped_stale: s.skipped_stale as u64,
            last_pushed_at: s.last_pushed_at,
            last_pulled_at: s.last_pulled_at,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiLlmEndpoint {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiMigrationSummary {
    pub courses: u64,
    pub sessions: u64,
    pub segments: u64,
    pub glossary: u64,
    pub bookmarks: u64,
}

// ---------------------------------------------------------------------- Stateless helpers

#[uniffi::export]
pub fn strip_think_tags(text: String) -> String {
    ThinkFilter::strip(&text)
}

#[uniffi::export]
pub fn guess_language(text: String) -> String {
    Langs::guess_language(&text).to_string()
}

#[uniffi::export]
pub fn display_language_name(code: String) -> String {
    Langs::display_name(&code).to_string()
}

#[uniffi::export]
pub fn is_classroom_question(src: String, tgt: String) -> bool {
    looks_like_question(&src, &tgt)
}

#[uniffi::export]
pub fn compute_audio_rms(samples: Vec<f32>) -> f32 {
    rms_level(&samples)
}

// ---------------------------------------------------------------------- Stateful DSP Resampler

#[derive(uniffi::Object)]
pub struct AudioResamplerFfi {
    inner: Mutex<StreamResampler>,
}

#[uniffi::export]
impl AudioResamplerFfi {
    #[uniffi::constructor]
    pub fn new(in_rate: u32, out_rate: u32) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(StreamResampler::new(in_rate, out_rate)),
        })
    }

    #[uniffi::constructor]
    pub fn new_to_16k(in_rate: u32) -> Arc<Self> {
        Self::new(in_rate, TARGET_SAMPLE_RATE)
    }

    pub fn process_f32(&self, samples: Vec<f32>) -> Vec<f32> {
        self.inner
            .lock()
            .map(|mut r| r.process_f32(&samples))
            .unwrap_or_default()
    }

    pub fn reset(&self) {
        if let Ok(mut r) = self.inner.lock() {
            r.reset();
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAsrEndpoint {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSpeechSegment {
    pub t0_s: f64,
    pub t1_s: f64,
    pub samples: Vec<f32>,
}

impl From<SpeechSegment> for FfiSpeechSegment {
    fn from(s: SpeechSegment) -> Self {
        Self {
            t0_s: s.t0_s,
            t1_s: s.t1_s,
            samples: s.samples,
        }
    }
}

/// Stateful 16 kHz real-time VAD speech segmenter exposed over UniFFI to macOS/Android.
#[derive(uniffi::Object)]
pub struct SpeechSegmenterFfi {
    inner: Mutex<SpeechSegmenter<AdaptiveEnergyVad>>,
}

#[uniffi::export]
impl SpeechSegmenterFfi {
    #[uniffi::constructor]
    pub fn new(min_silence_ms: u32, max_segment_ms: u32) -> Arc<Self> {
        let cfg = SegmenterConfig {
            min_silence_ms: min_silence_ms.clamp(200, 3000),
            max_segment_ms: max_segment_ms.clamp(2000, 30_000),
            ..SegmenterConfig::default()
        };
        Arc::new(Self {
            inner: Mutex::new(SpeechSegmenter::new(cfg, AdaptiveEnergyVad::default())),
        })
    }

    pub fn push_samples(&self, pcm_16k: Vec<f32>) -> Vec<FfiSpeechSegment> {
        self.inner
            .lock()
            .map(|mut s| {
                s.push_samples(&pcm_16k)
                    .into_iter()
                    .map(Into::into)
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn flush(&self) -> Option<FfiSpeechSegment> {
        self.inner
            .lock()
            .ok()
            .and_then(|mut s| s.flush().map(Into::into))
    }

    pub fn reset(&self) {
        if let Ok(mut s) = self.inner.lock() {
            s.reset();
        }
    }
}

// ---------------------------------------------------------------------- Main Cross-Platform Facade

#[derive(uniffi::Object)]
pub struct InterpreterCore {
    store: Store,
    rt: Runtime,
}

#[uniffi::export]
impl InterpreterCore {
    #[uniffi::constructor]
    pub fn open(db_path: String, device_id: String) -> Result<Arc<Self>, CoreError> {
        let store = Store::open(db_path, device_id)?;
        let rt = Runtime::new().map_err(|e| CoreError::Runtime { msg: e.to_string() })?;
        Ok(Arc::new(Self { store, rt }))
    }

    #[uniffi::constructor]
    pub fn open_in_memory(device_id: String) -> Result<Arc<Self>, CoreError> {
        let store = Store::open_in_memory(device_id)?;
        let rt = Runtime::new().map_err(|e| CoreError::Runtime { msg: e.to_string() })?;
        Ok(Arc::new(Self { store, rt }))
    }

    pub fn device_id(&self) -> String {
        self.store.device_id().to_string()
    }

    pub fn create_course(&self, name: String, kind: String) -> Result<FfiCourse, CoreError> {
        Ok(self.store.create_course(&name, &kind)?.into())
    }

    pub fn list_courses(&self) -> Result<Vec<FfiCourse>, CoreError> {
        Ok(self
            .store
            .list_courses()?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn create_session(
        &self,
        course_id: Option<String>,
        audio_source: String,
        source_lang: String,
        target_lang: String,
    ) -> Result<FfiSession, CoreError> {
        Ok(self
            .store
            .create_session(course_id, &audio_source, &source_lang, &target_lang)?
            .into())
    }

    pub fn list_sessions(&self, course_id: Option<String>) -> Result<Vec<FfiSession>, CoreError> {
        Ok(self
            .store
            .list_sessions(course_id.as_deref())?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn get_session(&self, session_id: String) -> Result<Option<FfiSession>, CoreError> {
        Ok(self.store.get_session(&session_id)?.map(Into::into))
    }

    #[allow(clippy::too_many_arguments)]
    pub fn add_segment(
        &self,
        session_id: String,
        t0: f64,
        t1: f64,
        src_lang: String,
        src_text: String,
        tgt_lang: String,
        tgt_text: String,
        low_conf: bool,
    ) -> Result<FfiSegment, CoreError> {
        Ok(self
            .store
            .add_segment(
                &session_id,
                t0,
                t1,
                &src_lang,
                &src_text,
                &tgt_lang,
                &tgt_text,
                low_conf,
            )?
            .into())
    }

    pub fn list_segments(&self, session_id: String) -> Result<Vec<FfiSegment>, CoreError> {
        Ok(self
            .store
            .list_segments(&session_id)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn upsert_glossary_term(
        &self,
        course_id: String,
        term: String,
        translation: String,
        note: String,
    ) -> Result<FfiGlossaryEntry, CoreError> {
        Ok(self
            .store
            .upsert_glossary_term(&course_id, &term, &translation, &note, "manual")?
            .into())
    }

    pub fn list_glossary(
        &self,
        course_id: Option<String>,
    ) -> Result<Vec<FfiGlossaryEntry>, CoreError> {
        Ok(self
            .store
            .list_glossary(course_id.as_deref())?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn toggle_bookmark(
        &self,
        session_id: String,
        segment_id: String,
        note: String,
    ) -> Result<bool, CoreError> {
        let (active, _) = self.store.toggle_bookmark(&session_id, &segment_id, &note)?;
        Ok(active)
    }

    pub fn list_bookmarks(&self, session_id: Option<String>) -> Result<Vec<FfiBookmark>, CoreError> {
        Ok(self
            .store
            .list_bookmarks(session_id.as_deref())?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn list_questions(&self, session_id: Option<String>) -> Result<Vec<FfiQuestion>, CoreError> {
        Ok(self
            .store
            .list_questions(session_id.as_deref())?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn update_session_user_note(
        &self,
        session_id: String,
        user_note: String,
    ) -> Result<FfiSession, CoreError> {
        Ok(self
            .store
            .update_session_user_note(&session_id, &user_note)?
            .into())
    }

    pub fn search(&self, query: String, limit: u32) -> Result<Vec<FfiSearchHit>, CoreError> {
        Ok(self
            .store
            .search(&query, limit as usize)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn export_session_markdown(&self, session_id: String) -> Result<String, CoreError> {
        Ok(export_session_markdown(&self.store, &session_id)?)
    }

    pub fn import_legacy_database(
        &self,
        legacy_db_path: String,
        source_tag: String,
    ) -> Result<FfiMigrationSummary, CoreError> {
        let rep = import_legacy_db(&self.store, legacy_db_path, &source_tag)?;
        Ok(FfiMigrationSummary {
            courses: rep.courses as u64,
            sessions: rep.sessions as u64,
            segments: rep.segments as u64,
            glossary: rep.glossary as u64,
            bookmarks: rep.bookmarks as u64,
        })
    }

    /// Translate and persist a recognized utterance using the configured OpenAI-compatible/local LLM endpoint.
    #[allow(clippy::too_many_arguments)]
    pub fn process_utterance(
        &self,
        endpoint: FfiLlmEndpoint,
        session_id: String,
        course_id: Option<String>,
        t0: f64,
        t1: f64,
        src_lang_hint: String,
        target_pref: String,
        src_text: String,
        low_conf: bool,
    ) -> Result<FfiSegment, CoreError> {
        let client = Arc::new(OpenAiCompatClient::new(
            endpoint.base_url,
            endpoint.api_key,
            endpoint.model,
        ));
        let pipe = PipelineCoordinator::new(self.store.clone(), client.clone(), client);
        let seg = self.rt.block_on(pipe.process_utterance(
            &session_id,
            course_id.as_deref(),
            t0,
            t1,
            &src_lang_hint,
            &target_pref,
            &src_text,
            low_conf,
            CancellationToken::new(),
        ))?;
        Ok(seg.into())
    }

    /// End-to-end audio segment processing: transcribe 16 kHz mono PCM via ASR endpoint,
    /// then translate and persist the bilingual segment. Returns `None` if speech is empty.
    #[allow(clippy::too_many_arguments)]
    pub fn process_audio_segment(
        &self,
        asr_endpoint: FfiAsrEndpoint,
        llm_endpoint: FfiLlmEndpoint,
        session_id: String,
        course_id: Option<String>,
        t0: f64,
        t1: f64,
        src_lang_hint: String,
        target_pref: String,
        pcm_16k: Vec<f32>,
    ) -> Result<Option<FfiSegment>, CoreError> {
        let asr = OpenAiWhisperAsrClient::new(
            asr_endpoint.base_url,
            asr_endpoint.api_key,
            asr_endpoint.model,
        );
        let (detected_lang, text) = self
            .rt
            .block_on(asr.transcribe(&pcm_16k, &src_lang_hint))
            .map_err(|e| CoreError::Inference { msg: e.to_string() })?;
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return Ok(None);
        }
        let effective_src = if src_lang_hint.trim().is_empty()
            || src_lang_hint.eq_ignore_ascii_case("auto")
        {
            detected_lang
        } else {
            src_lang_hint
        };
        let seg = self.process_utterance(
            llm_endpoint,
            session_id,
            course_id,
            t0,
            t1,
            effective_src,
            target_pref,
            trimmed.to_string(),
            false,
        )?;
        Ok(Some(seg))
    }

    /// Extract post-class title, summary, structured knowledge points, and optional glossary terms.
    pub fn finalize_session_knowledge(
        &self,
        endpoint: FfiLlmEndpoint,
        session_id: String,
        extract_glossary: bool,
    ) -> Result<FfiSession, CoreError> {
        let client = Arc::new(OpenAiCompatClient::new(
            endpoint.base_url,
            endpoint.api_key,
            endpoint.model,
        ));
        let pipe = PipelineCoordinator::new(self.store.clone(), client.clone(), client);
        let res = self.rt.block_on(pipe.finalize_session_knowledge(
            &session_id,
            extract_glossary,
            CancellationToken::new(),
        ))?;
        Ok(res.session.into())
    }

    /// Sync bidirectionally with the Google Cloud Run sync & iPhone PWA server.
    pub fn sync_cloud_run(
        &self,
        base_url: String,
        auth_token: String,
    ) -> Result<FfiSyncStats, CoreError> {
        let mgr = SyncManager::new(
            self.store.clone(),
            CloudRunTransport::new(base_url, auth_token),
        );
        let stats = self.rt.block_on(mgr.sync_now())?;
        Ok(stats.into())
    }

    /// Sync bidirectionally straight to a Google Cloud Storage bucket via S3-compatible HMAC keys.
    #[allow(clippy::too_many_arguments)]
    pub fn sync_gcs_s3(
        &self,
        endpoint: String,
        bucket: String,
        region: String,
        access_key: String,
        secret_key: String,
        prefix: String,
        passphrase: Option<String>,
    ) -> Result<FfiSyncStats, CoreError> {
        let mgr = SyncManager::new(
            self.store.clone(),
            GcsS3Transport::new(
                endpoint, bucket, region, access_key, secret_key, prefix, passphrase,
            ),
        );
        let stats = self.rt.block_on(mgr.sync_now())?;
        Ok(stats.into())
    }

    /// Sync bidirectionally via a local sync folder (Google Drive for Desktop / iCloud Drive / Syncthing).
    pub fn sync_folder(
        &self,
        folder_path: String,
        passphrase: Option<String>,
    ) -> Result<FfiSyncStats, CoreError> {
        let mgr = SyncManager::new(
            self.store.clone(),
            FolderTransport::new(folder_path, passphrase),
        );
        let stats = self.rt.block_on(mgr.sync_now())?;
        Ok(stats.into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ffi_facade_smoke_test() {
        let core = InterpreterCore::open_in_memory("mac-ffi-test".into()).unwrap();
        let c = core
            .create_course("Distributed Systems".into(), "课堂".into())
            .unwrap();
        let s = core
            .create_session(Some(c.id), "system".into(), "en".into(), "zh".into())
            .unwrap();
        let seg = core
            .add_segment(
                s.id.clone(),
                0.0,
                3.0,
                "en".into(),
                "Raft achieves consensus via leader election.".into(),
                "zh".into(),
                "Raft 通过领导者选举达成共识。".into(),
                false,
            )
            .unwrap();
        assert!(core
            .toggle_bookmark(s.id.clone(), seg.id, "考点".into())
            .unwrap());
        let md = core.export_session_markdown(s.id).unwrap();
        assert!(md.contains("Raft"));
        assert_eq!(display_language_name("auto".into()), "自动");
    }
}
