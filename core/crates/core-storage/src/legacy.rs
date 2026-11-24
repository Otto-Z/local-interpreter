//! Lossless read-only importer for legacy macOS (`app/db.py`) and Android (`Database.kt`) SQLite DBs.
//!
//! Converts integer `AUTOINCREMENT` IDs into deterministic UUIDv5-like / UUIDv7 IDs and normalizes
//! ISO-8601 string timestamps (macOS) and epoch millisecond timestamps (Android) into the unified schema.

use std::collections::HashMap;
use std::path::Path;

use chrono::{DateTime, NaiveDateTime};
use core_model::{
    now_ms, Bookmark, Course, GlossaryEntry, Question, Segment, Session, SummaryNote,
};
use rusqlite::types::ValueRef;
use rusqlite::{Connection, OpenFlags};
use uuid::Uuid;

use crate::{Result, Store};

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct MigrationReport {
    pub courses: usize,
    pub sessions: usize,
    pub segments: usize,
    pub glossary: usize,
    pub questions: usize,
    pub bookmarks: usize,
    pub summaries: usize,
}

/// Deterministic UUID derived from `(namespace, entity, legacy_id)` so re-running migration is idempotent.
fn legacy_uuid(source_tag: &str, entity: &str, legacy_id: i64) -> String {
    let seed = format!("{source_tag}:{entity}:{legacy_id}");
    Uuid::new_v5(&Uuid::NAMESPACE_OID, seed.as_bytes()).to_string()
}

fn parse_timestamp_value(val: ValueRef<'_>, fallback: i64) -> i64 {
    match val {
        ValueRef::Integer(i) => {
            // Heuristic: seconds vs milliseconds
            if i > 0 && i < 10_000_000_000 {
                i * 1000
            } else if i > 0 {
                i
            } else {
                fallback
            }
        }
        ValueRef::Real(f) => {
            let i = f as i64;
            if i > 0 && i < 10_000_000_000 {
                i * 1000
            } else if i > 0 {
                i
            } else {
                fallback
            }
        }
        ValueRef::Text(bytes) => {
            let s = String::from_utf8_lossy(bytes);
            let trimmed = s.trim();
            if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
                return dt.timestamp_millis();
            }
            for fmt in [
                "%Y-%m-%dT%H:%M:%S",
                "%Y-%m-%d %H:%M:%S",
                "%Y-%m-%dT%H:%M:%S%.f",
            ] {
                if let Ok(ndt) = NaiveDateTime::parse_from_str(trimmed, fmt) {
                    return ndt.and_utc().timestamp_millis();
                }
            }
            trimmed.parse::<i64>().unwrap_or(fallback)
        }
        _ => fallback,
    }
}

fn table_columns(conn: &Connection, table: &str) -> HashMap<String, usize> {
    let mut map = HashMap::new();
    let sql = format!("PRAGMA table_info({table})");
    if let Ok(mut stmt) = conn.prepare(&sql) {
        if let Ok(rows) = stmt.query_map([], |r| r.get::<_, String>(1)) {
            for (idx, name) in rows.flatten().enumerate() {
                map.insert(name, idx);
            }
        }
    }
    map
}

fn get_string_opt(row: &rusqlite::Row<'_>, cols: &HashMap<String, usize>, col: &str) -> String {
    cols.get(col)
        .and_then(|&i| row.get::<_, Option<String>>(i).ok().flatten())
        .unwrap_or_default()
}

fn get_i64_opt(row: &rusqlite::Row<'_>, cols: &HashMap<String, usize>, col: &str) -> Option<i64> {
    cols.get(col)
        .and_then(|&i| row.get::<_, Option<i64>>(i).ok().flatten())
}

fn get_f64_or(
    row: &rusqlite::Row<'_>,
    cols: &HashMap<String, usize>,
    col: &str,
    default: f64,
) -> f64 {
    cols.get(col)
        .and_then(|&i| row.get::<_, Option<f64>>(i).ok().flatten())
        .unwrap_or(default)
}

fn get_ts_or(
    row: &rusqlite::Row<'_>,
    cols: &HashMap<String, usize>,
    col: &str,
    fallback: i64,
) -> i64 {
    cols.get(col)
        .and_then(|&i| row.get_ref(i).ok())
        .map(|v| parse_timestamp_value(v, fallback))
        .unwrap_or(fallback)
}

/// Import a legacy `history.db` from either macOS (`app/db.py`) or Android (`Database.kt`).
/// Opens the legacy database read-only and never mutates the original file.
pub fn import_legacy_db(
    target: &Store,
    legacy_path: impl AsRef<Path>,
    source_tag: &str,
) -> Result<MigrationReport> {
    let conn = Connection::open_with_flags(legacy_path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let mut report = MigrationReport::default();
    let fallback_ts = now_ms();
    let device_id = format!("legacy-{source_tag}");

    // 1. Courses
    let c_cols = table_columns(&conn, "courses");
    if !c_cols.is_empty() {
        let mut stmt = conn.prepare("SELECT * FROM courses")?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let Some(old_id) = get_i64_opt(row, &c_cols, "id") else {
                continue;
            };
            let created_at = get_ts_or(row, &c_cols, "created_at", fallback_ts);
            let updated_at = get_ts_or(row, &c_cols, "updated_at", created_at);
            let course = Course {
                id: legacy_uuid(source_tag, "course", old_id),
                name: get_string_opt(row, &c_cols, "name"),
                kind: get_string_opt(row, &c_cols, "kind"),
                summary: get_string_opt(row, &c_cols, "summary"),
                outline: get_string_opt(row, &c_cols, "outline"),
                notes: get_string_opt(row, &c_cols, "notes"),
                created_at,
                updated_at,
                deleted_at: None,
                device_id: device_id.clone(),
            };
            target.upsert_course(&course)?;
            report.courses += 1;
        }
    }

    // 2. Sessions
    let s_cols = table_columns(&conn, "sessions");
    if !s_cols.is_empty() {
        let mut stmt = conn.prepare("SELECT * FROM sessions")?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let Some(old_id) = get_i64_opt(row, &s_cols, "id") else {
                continue;
            };
            let started_at = get_ts_or(row, &s_cols, "started_at", fallback_ts);
            let ended_at = s_cols
                .get("ended_at")
                .and_then(|&i| row.get_ref(i).ok())
                .and_then(|v| match v {
                    ValueRef::Null => None,
                    other => Some(parse_timestamp_value(other, started_at)),
                });
            let course_id = get_i64_opt(row, &s_cols, "course_id")
                .map(|cid| legacy_uuid(source_tag, "course", cid));
            let topics_raw = get_string_opt(row, &s_cols, "topics");
            let topics: Vec<String> = serde_json::from_str(&topics_raw).unwrap_or_else(|_| {
                topics_raw
                    .split([',', '，', '\n'])
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(String::from)
                    .collect()
            });
            let session = Session {
                id: legacy_uuid(source_tag, "session", old_id),
                course_id,
                started_at,
                ended_at,
                duration_s: get_f64_or(row, &s_cols, "duration_s", 0.0),
                audio_source: get_string_opt(row, &s_cols, "audio_source"),
                source_lang: non_empty_or(get_string_opt(row, &s_cols, "source_lang"), "auto"),
                target_lang: non_empty_or(get_string_opt(row, &s_cols, "target_lang"), "auto"),
                llm_backend: get_string_opt(row, &s_cols, "llm_backend"),
                llm_model: get_string_opt(row, &s_cols, "llm_model"),
                asr_engine: get_string_opt(row, &s_cols, "asr_engine"),
                asr_model: get_string_opt(row, &s_cols, "asr_model"),
                title: get_string_opt(row, &s_cols, "title"),
                title_source: get_string_opt(row, &s_cols, "title_source"),
                summary: get_string_opt(row, &s_cols, "summary"),
                knowledge_points: Vec::new(),
                topics,
                user_note: get_string_opt(row, &s_cols, "user_note"),
                quiz: get_string_opt(row, &s_cols, "quiz"),
                starred: get_i64_opt(row, &s_cols, "starred").unwrap_or(0) != 0,
                status: non_empty_or(get_string_opt(row, &s_cols, "status"), "completed"),
                created_at: started_at,
                updated_at: ended_at.unwrap_or(started_at),
                deleted_at: None,
                device_id: device_id.clone(),
            };
            target.upsert_session(&session)?;
            report.sessions += 1;
        }
    }

    // 3. Segments
    let seg_cols = table_columns(&conn, "segments");
    if !seg_cols.is_empty() {
        let mut stmt = conn.prepare("SELECT * FROM segments")?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let Some(old_id) = get_i64_opt(row, &seg_cols, "id") else {
                continue;
            };
            let Some(old_sid) = get_i64_opt(row, &seg_cols, "session_id") else {
                continue;
            };
            let seg = Segment {
                id: legacy_uuid(source_tag, "segment", old_id),
                session_id: legacy_uuid(source_tag, "session", old_sid),
                speaker_label: get_i64_opt(row, &seg_cols, "speaker_id")
                    .map(|sp| format!("Speaker {sp}")),
                t0: get_f64_or(row, &seg_cols, "t0", 0.0),
                t1: get_f64_or(row, &seg_cols, "t1", 0.0),
                src_lang: get_string_opt(row, &seg_cols, "src_lang"),
                src_text: get_string_opt(row, &seg_cols, "src_text"),
                tgt_lang: get_string_opt(row, &seg_cols, "tgt_lang"),
                tgt_text: get_string_opt(row, &seg_cols, "tgt_text"),
                low_conf: get_i64_opt(row, &seg_cols, "low_conf").unwrap_or(0) != 0,
                local_audio_path: non_empty_opt(get_string_opt(row, &seg_cols, "audio_path")),
                created_at: fallback_ts,
                updated_at: fallback_ts,
                deleted_at: None,
                device_id: device_id.clone(),
            };
            target.upsert_segment(&seg)?;
            report.segments += 1;
        }
    }

    // 4. Glossary
    let g_cols = table_columns(&conn, "glossary");
    if !g_cols.is_empty() {
        let mut stmt = conn.prepare("SELECT * FROM glossary")?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let Some(old_id) = get_i64_opt(row, &g_cols, "id") else {
                continue;
            };
            let course_id = get_i64_opt(row, &g_cols, "course_id")
                .map(|cid| legacy_uuid(source_tag, "course", cid))
                .unwrap_or_default();
            let ts = get_ts_or(row, &g_cols, "created_at", fallback_ts);
            let entry = GlossaryEntry {
                id: legacy_uuid(source_tag, "glossary", old_id),
                course_id,
                term: get_string_opt(row, &g_cols, "term"),
                translation: get_string_opt(row, &g_cols, "translation"),
                note: get_string_opt(row, &g_cols, "note"),
                source: get_string_opt(row, &g_cols, "source"),
                created_at: ts,
                updated_at: ts,
                deleted_at: None,
                device_id: device_id.clone(),
            };
            target.upsert_glossary(&entry)?;
            report.glossary += 1;
        }
    }

    // 5. Questions
    let q_cols = table_columns(&conn, "questions");
    if !q_cols.is_empty() {
        let mut stmt = conn.prepare("SELECT * FROM questions")?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let Some(old_id) = get_i64_opt(row, &q_cols, "id") else {
                continue;
            };
            let Some(old_sid) = get_i64_opt(row, &q_cols, "session_id") else {
                continue;
            };
            let ts = get_ts_or(row, &q_cols, "created_at", fallback_ts);
            let q = Question {
                id: legacy_uuid(source_tag, "question", old_id),
                session_id: legacy_uuid(source_tag, "session", old_sid),
                segment_id: get_i64_opt(row, &q_cols, "segment_id")
                    .map(|id| legacy_uuid(source_tag, "segment", id)),
                question: get_string_opt(row, &q_cols, "question"),
                question_tgt: get_string_opt(row, &q_cols, "question_tgt"),
                answer: get_string_opt(row, &q_cols, "answer"),
                answer_alt: get_string_opt(row, &q_cols, "answer_alt"),
                source: get_string_opt(row, &q_cols, "source"),
                created_at: ts,
                updated_at: ts,
                deleted_at: None,
                device_id: device_id.clone(),
            };
            target.upsert_question(&q)?;
            report.questions += 1;
        }
    }

    // 6. Bookmarks
    let b_cols = table_columns(&conn, "bookmarks");
    if !b_cols.is_empty() {
        let mut stmt = conn.prepare("SELECT * FROM bookmarks")?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let Some(old_id) = get_i64_opt(row, &b_cols, "id") else {
                continue;
            };
            let Some(old_sid) = get_i64_opt(row, &b_cols, "session_id") else {
                continue;
            };
            let Some(old_seg) = get_i64_opt(row, &b_cols, "segment_id") else {
                continue;
            };
            let ts = get_ts_or(row, &b_cols, "created_at", fallback_ts);
            let bm = Bookmark {
                id: legacy_uuid(source_tag, "bookmark", old_id),
                session_id: legacy_uuid(source_tag, "session", old_sid),
                segment_id: legacy_uuid(source_tag, "segment", old_seg),
                note: get_string_opt(row, &b_cols, "note"),
                created_at: ts,
                updated_at: ts,
                deleted_at: None,
                device_id: device_id.clone(),
            };
            target.upsert_bookmark(&bm)?;
            report.bookmarks += 1;
        }
    }

    // 7. Summaries (macOS uses `text`, Android uses `content`)
    let sum_cols = table_columns(&conn, "summaries");
    if !sum_cols.is_empty() {
        let mut stmt = conn.prepare("SELECT * FROM summaries")?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let Some(old_id) = get_i64_opt(row, &sum_cols, "id") else {
                continue;
            };
            let Some(old_sid) = get_i64_opt(row, &sum_cols, "session_id") else {
                continue;
            };
            let ts = get_ts_or(row, &sum_cols, "created_at", fallback_ts);
            let content = non_empty_or(
                get_string_opt(row, &sum_cols, "content"),
                &get_string_opt(row, &sum_cols, "text"),
            );
            let note = SummaryNote {
                id: legacy_uuid(source_tag, "summary", old_id),
                session_id: legacy_uuid(source_tag, "session", old_sid),
                content,
                created_at: ts,
                updated_at: ts,
                deleted_at: None,
                device_id: device_id.clone(),
            };
            target.upsert_summary_note(&note)?;
            report.summaries += 1;
        }
    }

    Ok(report)
}

fn non_empty_or(s: String, fallback: &str) -> String {
    if s.trim().is_empty() {
        fallback.to_string()
    } else {
        s
    }
}

fn non_empty_opt(s: String) -> Option<String> {
    if s.trim().is_empty() {
        None
    } else {
        Some(s)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;

    #[test]
    fn imports_legacy_macos_and_android_databases_without_id_collisions() {
        // Build a mock legacy macOS DB (ISO timestamps, `summaries.text`)
        let mac_file = NamedTempFile::new().unwrap();
        {
            let c = Connection::open(mac_file.path()).unwrap();
            c.execute_batch(
                "
                CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, created_at TEXT, summary TEXT, notes TEXT, kind TEXT, outline TEXT);
                CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, course_id INTEGER, started_at TEXT, ended_at TEXT, duration_s REAL, audio_source TEXT, source_lang TEXT, target_lang TEXT, title TEXT, summary TEXT, status TEXT);
                CREATE TABLE segments (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, t0 REAL, t1 REAL, src_lang TEXT, src_text TEXT, tgt_lang TEXT, tgt_text TEXT);
                CREATE TABLE summaries (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, text TEXT, created_at TEXT);
                INSERT INTO courses(name, created_at, kind) VALUES('CS224N', '2026-03-01T10:00:00', '课堂');
                INSERT INTO sessions(course_id, started_at, ended_at, duration_s, audio_source, source_lang, target_lang, title, summary, status)
                  VALUES(1, '2026-03-01T10:05:00', '2026-03-01T11:30:00', 5100, 'system', 'en', 'zh', 'Lecture 1', 'Word vectors', 'completed');
                INSERT INTO segments(session_id, t0, t1, src_lang, src_text, tgt_lang, tgt_text)
                  VALUES(1, 0.0, 2.0, 'en', 'Word2Vec learns embeddings', 'zh', 'Word2Vec 学习词向量');
                INSERT INTO summaries(session_id, text, created_at) VALUES(1, '重点：Skip-gram', '2026-03-01T10:30:00');
                ",
            )
            .unwrap();
        }

        // Build a mock legacy Android DB (epoch ms, `summaries.content`, ALSO integer id=1!)
        let and_file = NamedTempFile::new().unwrap();
        {
            let c = Connection::open(and_file.path()).unwrap();
            c.execute_batch(
                "
                CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kind TEXT, notes TEXT, created_at INTEGER, updated_at INTEGER);
                CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, course_id INTEGER, started_at INTEGER, ended_at INTEGER, duration_s REAL, audio_source TEXT, source_lang TEXT, target_lang TEXT, title TEXT, summary TEXT, status TEXT);
                CREATE TABLE segments (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, t0 REAL, t1 REAL, src_lang TEXT, src_text TEXT, tgt_text TEXT, low_conf INTEGER);
                CREATE TABLE summaries (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, content TEXT, created_at INTEGER);
                INSERT INTO courses(name, kind, notes, created_at, updated_at) VALUES('线性代数', '课堂', '', 1772400000000, 1772400000000);
                INSERT INTO sessions(course_id, started_at, ended_at, duration_s, audio_source, source_lang, target_lang, title, summary, status)
                  VALUES(1, 1772400000000, 1772403600000, 3600, 'mic', 'zh', 'en', '特征值', '矩阵分解', 'completed');
                INSERT INTO segments(session_id, t0, t1, src_lang, src_text, tgt_text, low_conf)
                  VALUES(1, 1.0, 4.0, 'zh', '特征值分解非常重要', 'Eigendecomposition is vital', 0);
                INSERT INTO summaries(session_id, content, created_at) VALUES(1, '近5分钟总结：SVD', 1772401000000);
                ",
            )
            .unwrap();
        }

        let unified = Store::open_in_memory("mac-new").unwrap();
        let r1 = import_legacy_db(&unified, mac_file.path(), "macos-old").unwrap();
        let r2 = import_legacy_db(&unified, and_file.path(), "android-old").unwrap();

        assert_eq!(r1.courses, 1);
        assert_eq!(r2.courses, 1);
        assert_eq!(unified.list_courses().unwrap().len(), 2);
        assert_eq!(unified.list_sessions(None).unwrap().len(), 2);

        // Re-importing the same DB is idempotent (no duplicates!)
        import_legacy_db(&unified, mac_file.path(), "macos-old").unwrap();
        assert_eq!(unified.list_courses().unwrap().len(), 2);
        assert_eq!(unified.list_sessions(None).unwrap().len(), 2);
    }
}
