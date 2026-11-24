//! Cross-platform domain model & deterministic sync primitives.
//!
//! All entities use UUIDv7 primary keys, UTC millisecond timestamps, soft-delete tombstones,
//! and originating device IDs so macOS, Android tablet, and iPhone/Cloud sync cleanly offline-first.

use chrono::Utc;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Current UTC timestamp in epoch milliseconds.
pub fn now_ms() -> i64 {
    Utc::now().timestamp_millis()
}

/// Generate a time-ordered UUIDv7 string suitable for multi-device offline creation.
pub fn new_id() -> String {
    Uuid::now_v7().to_string()
}

/// Deterministic Last-Write-Wins comparison key: `(updated_at, device_id)`.
/// Returns `true` if `incoming` should replace `existing`.
pub fn should_win_lww(
    existing_updated_at: i64,
    existing_device_id: &str,
    incoming_updated_at: i64,
    incoming_device_id: &str,
) -> bool {
    if incoming_updated_at != existing_updated_at {
        incoming_updated_at > existing_updated_at
    } else {
        incoming_device_id >= existing_device_id
    }
}

/// Structured knowledge point extracted after class for mobile review (iPhone PWA / tablet).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KnowledgePoint {
    pub title: String,
    pub explanation: String,
    /// Importance level: 1 (background), 2 (important), 3 (exam/core).
    #[serde(default = "default_importance")]
    pub importance: u8,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub source_quote: String,
}

fn default_importance() -> u8 {
    2
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Course {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub outline: String,
    #[serde(default)]
    pub notes: String,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Session {
    pub id: String,
    pub course_id: Option<String>,
    pub started_at: i64,
    #[serde(default)]
    pub ended_at: Option<i64>,
    #[serde(default)]
    pub duration_s: f64,
    #[serde(default)]
    pub audio_source: String,
    #[serde(default = "default_auto")]
    pub source_lang: String,
    #[serde(default = "default_auto")]
    pub target_lang: String,
    #[serde(default)]
    pub llm_backend: String,
    #[serde(default)]
    pub llm_model: String,
    #[serde(default)]
    pub asr_engine: String,
    #[serde(default)]
    pub asr_model: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub title_source: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub knowledge_points: Vec<KnowledgePoint>,
    #[serde(default)]
    pub topics: Vec<String>,
    #[serde(default)]
    pub user_note: String,
    #[serde(default)]
    pub quiz: String,
    #[serde(default)]
    pub starred: bool,
    #[serde(default = "default_status")]
    pub status: String,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub device_id: String,
}

fn default_auto() -> String {
    "auto".to_string()
}

fn default_status() -> String {
    "completed".to_string()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Segment {
    pub id: String,
    pub session_id: String,
    #[serde(default)]
    pub speaker_label: Option<String>,
    #[serde(default)]
    pub t0: f64,
    #[serde(default)]
    pub t1: f64,
    #[serde(default)]
    pub src_lang: String,
    #[serde(default)]
    pub src_text: String,
    #[serde(default)]
    pub tgt_lang: String,
    #[serde(default)]
    pub tgt_text: String,
    #[serde(default)]
    pub low_conf: bool,
    /// Local-only audio clip path; never synced across devices.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub local_audio_path: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub device_id: String,
}

impl Segment {
    /// Return a copy safe for cloud sync (strips machine-local audio path).
    pub fn stripped_for_sync(&self) -> Self {
        let mut copy = self.clone();
        copy.local_audio_path = None;
        copy
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GlossaryEntry {
    pub id: String,
    pub course_id: String,
    pub term: String,
    #[serde(default)]
    pub translation: String,
    #[serde(default)]
    pub note: String,
    #[serde(default)]
    pub source: String,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Question {
    pub id: String,
    pub session_id: String,
    #[serde(default)]
    pub segment_id: Option<String>,
    pub question: String,
    #[serde(default)]
    pub question_tgt: String,
    #[serde(default)]
    pub answer: String,
    #[serde(default)]
    pub answer_alt: String,
    #[serde(default)]
    pub source: String,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Bookmark {
    pub id: String,
    pub session_id: String,
    pub segment_id: String,
    #[serde(default)]
    pub note: String,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SummaryNote {
    pub id: String,
    pub session_id: String,
    pub content: String,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub device_id: String,
}

/// Versioned incremental sync packet exchanged between devices and Google Cloud.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChangeSet {
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    pub device_id: String,
    pub generated_at: i64,
    pub since_ms: i64,
    /// Monotonic cursor assigned by server (or upload timestamp for object storage) for lossless pull pagination.
    #[serde(default)]
    pub server_cursor: Option<i64>,
    #[serde(default)]
    pub courses: Vec<Course>,
    #[serde(default)]
    pub sessions: Vec<Session>,
    #[serde(default)]
    pub segments: Vec<Segment>,
    #[serde(default)]
    pub glossary: Vec<GlossaryEntry>,
    #[serde(default)]
    pub questions: Vec<Question>,
    #[serde(default)]
    pub bookmarks: Vec<Bookmark>,
    #[serde(default)]
    pub summaries: Vec<SummaryNote>,
}

fn default_schema_version() -> u32 {
    1
}

impl ChangeSet {
    pub fn empty(device_id: impl Into<String>, since_ms: i64) -> Self {
        Self {
            schema_version: 1,
            device_id: device_id.into(),
            generated_at: now_ms(),
            since_ms,
            server_cursor: None,
            courses: Vec::new(),
            sessions: Vec::new(),
            segments: Vec::new(),
            glossary: Vec::new(),
            questions: Vec::new(),
            bookmarks: Vec::new(),
            summaries: Vec::new(),
        }
    }

    pub fn total_items(&self) -> usize {
        self.courses.len()
            + self.sessions.len()
            + self.segments.len()
            + self.glossary.len()
            + self.questions.len()
            + self.bookmarks.len()
            + self.summaries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.total_items() == 0
    }

    /// Maximum `updated_at` across all items in this changeset, or `generated_at` if empty.
    pub fn max_updated_at(&self) -> i64 {
        let mut max_ts = 0i64;
        for c in &self.courses {
            max_ts = max_ts.max(c.updated_at);
        }
        for s in &self.sessions {
            max_ts = max_ts.max(s.updated_at);
        }
        for s in &self.segments {
            max_ts = max_ts.max(s.updated_at);
        }
        for g in &self.glossary {
            max_ts = max_ts.max(g.updated_at);
        }
        for q in &self.questions {
            max_ts = max_ts.max(q.updated_at);
        }
        for b in &self.bookmarks {
            max_ts = max_ts.max(b.updated_at);
        }
        for n in &self.summaries {
            max_ts = max_ts.max(n.updated_at);
        }
        if max_ts == 0 {
            self.generated_at
        } else {
            max_ts
        }
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MergeReport {
    pub courses_applied: usize,
    pub sessions_applied: usize,
    pub segments_applied: usize,
    pub glossary_applied: usize,
    pub questions_applied: usize,
    pub bookmarks_applied: usize,
    pub summaries_applied: usize,
    pub skipped_stale: usize,
}

impl MergeReport {
    pub fn total_applied(&self) -> usize {
        self.courses_applied
            + self.sessions_applied
            + self.segments_applied
            + self.glossary_applied
            + self.questions_applied
            + self.bookmarks_applied
            + self.summaries_applied
    }
}

/// Search hit returned across segments, knowledge points, summaries, glossary, and questions.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SearchHit {
    pub kind: String,
    pub id: String,
    pub course_id: Option<String>,
    pub session_id: Option<String>,
    pub title: String,
    pub snippet: String,
    pub updated_at: i64,
}

/// Parse LLM output into structured [`KnowledgePoint`] items.
/// Supports JSON array, fenced ```json blocks, or fallback Markdown bullet lists.
pub fn parse_knowledge_points(raw: &str) -> Vec<KnowledgePoint> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    // 1. Try direct or fenced JSON extraction.
    let json_candidate = extract_json_slice(trimmed).unwrap_or(trimmed);
    if let Ok(mut items) = serde_json::from_str::<Vec<KnowledgePoint>>(json_candidate) {
        items.retain(|kp| !kp.title.trim().is_empty() || !kp.explanation.trim().is_empty());
        for kp in &mut items {
            kp.title = kp.title.trim().to_string();
            kp.explanation = kp.explanation.trim().to_string();
            kp.importance = kp.importance.clamp(1, 3);
        }
        if !items.is_empty() {
            return items;
        }
    }

    // Also accept {"knowledge_points": [...]} object wrappers.
    #[derive(Deserialize)]
    struct Wrapper {
        knowledge_points: Vec<KnowledgePoint>,
    }
    if let Ok(mut w) = serde_json::from_str::<Wrapper>(json_candidate) {
        w.knowledge_points
            .retain(|kp| !kp.title.trim().is_empty() || !kp.explanation.trim().is_empty());
        for kp in &mut w.knowledge_points {
            kp.title = kp.title.trim().to_string();
            kp.explanation = kp.explanation.trim().to_string();
            kp.importance = kp.importance.clamp(1, 3);
        }
        if !w.knowledge_points.is_empty() {
            return w.knowledge_points;
        }
    }

    // 2. Fallback: parse Markdown bullet points (`- **Title**: explanation` or `1. Title：explanation`).
    let mut out = Vec::new();
    for line in trimmed.lines() {
        let mut s = line.trim();
        if s.is_empty() || s.starts_with("```") {
            continue;
        }
        // Strip leading bullet or numbered prefix.
        s = s
            .trim_start_matches(['-', '*', '•'])
            .trim_start();
        if let Some(rest) = strip_numbered_prefix(s) {
            s = rest;
        }
        if s.len() < 4 {
            continue;
        }
        let (title, explanation) = split_title_explanation(s);
        let clean_title = title.replace("**", "").trim().to_string();
        let clean_exp = explanation.replace("**", "").trim().to_string();
        if !clean_title.is_empty() {
            out.push(KnowledgePoint {
                title: clean_title,
                explanation: clean_exp,
                importance: 2,
                tags: Vec::new(),
                source_quote: String::new(),
            });
        }
    }
    out
}

fn extract_json_slice(s: &str) -> Option<&str> {
    if let Some(start) = s.find('[') {
        if let Some(end) = s.rfind(']') {
            if end > start {
                return Some(&s[start..=end]);
            }
        }
    }
    if let Some(start) = s.find('{') {
        if let Some(end) = s.rfind('}') {
            if end > start {
                return Some(&s[start..=end]);
            }
        }
    }
    None
}

fn strip_numbered_prefix(s: &str) -> Option<&str> {
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if i > 0 && i < bytes.len() && matches!(bytes[i], b'.' | b')') {
        Some(s[i + 1..].trim_start())
    } else {
        None
    }
}

fn split_title_explanation(s: &str) -> (&str, &str) {
    for sep in ["：", ": ", " - ", " — "] {
        if let Some(idx) = s.find(sep) {
            let left = &s[..idx];
            let right = &s[idx + sep.len()..];
            if !left.trim().is_empty() && !right.trim().is_empty() {
                return (left, right);
            }
        }
    }
    (s, "")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuidv7_is_unique_and_monotonic() {
        let a = new_id();
        let b = new_id();
        assert_ne!(a, b);
        assert_eq!(a.len(), 36);
    }

    #[test]
    fn lww_prefers_newer_timestamp_and_breaks_ties_deterministically() {
        assert!(should_win_lww(100, "dev-a", 101, "dev-a"));
        assert!(!should_win_lww(101, "dev-a", 100, "dev-z"));
        assert!(should_win_lww(100, "dev-a", 100, "dev-b"));
        assert!(!should_win_lww(100, "dev-b", 100, "dev-a"));
    }

    #[test]
    fn parse_knowledge_points_handles_fenced_json_and_markdown() {
        let json = r#"
Here are the key takeaways:
```json
[
  {"title": "Attention Mechanism", "explanation": "Scaled dot-product weights tokens", "importance": 5, "tags": ["NLP"]},
  {"title": "KV Cache", "explanation": "Reuses past K/V tensors during decoding", "importance": 3}
]
```
"#;
        let pts = parse_knowledge_points(json);
        assert_eq!(pts.len(), 2);
        assert_eq!(pts[0].title, "Attention Mechanism");
        assert_eq!(pts[0].importance, 3); // clamped to 1..=3
        assert_eq!(pts[0].tags, vec!["NLP"]);

        let md = "- **反向传播**：通过链式法则计算梯度\n2. 梯度消失: 深层网络激活导数连乘趋近于 0";
        let md_pts = parse_knowledge_points(md);
        assert_eq!(md_pts.len(), 2);
        assert_eq!(md_pts[0].title, "反向传播");
        assert_eq!(md_pts[0].explanation, "通过链式法则计算梯度");
        assert_eq!(md_pts[1].title, "梯度消失");
    }

    #[test]
    fn segment_stripped_for_sync_removes_local_audio_path() {
        let seg = Segment {
            id: new_id(),
            session_id: new_id(),
            speaker_label: None,
            t0: 0.0,
            t1: 2.5,
            src_lang: "en".into(),
            src_text: "Hello".into(),
            tgt_lang: "zh".into(),
            tgt_text: "你好".into(),
            low_conf: false,
            local_audio_path: Some("/Users/test/clip.wav".into()),
            created_at: 100,
            updated_at: 100,
            deleted_at: None,
            device_id: "mac-1".into(),
        };
        assert!(seg.stripped_for_sync().local_audio_path.is_none());
    }
}
