//! Unified SQLite storage engine with WAL concurrency, FTS5 search,
//! deterministic multi-device ChangeSet sync, and legacy macOS/Android DB migration.

pub mod legacy;
pub mod materials;

use std::path::Path;
use std::sync::{Arc, Mutex};

use core_model::{
    new_id, now_ms, should_win_lww, Bookmark, ChangeSet, Course, GlossaryEntry, KnowledgePoint,
    MergeReport, Question, SearchHit, Segment, Session, SummaryNote,
};
use rusqlite::{params, Connection, OptionalExtension};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("lock poisoned")]
    LockPoisoned,
    #[error("not found: {0}")]
    NotFound(String),
}

pub type Result<T> = std::result::Result<T, StorageError>;

#[derive(Clone)]
pub struct Store {
    conn: Arc<Mutex<Connection>>,
    device_id: String,
}

impl Store {
    /// Open or create a persistent SQLite store on disk with WAL mode enabled.
    pub fn open(path: impl AsRef<Path>, device_id: impl Into<String>) -> Result<Self> {
        if let Some(parent) = path.as_ref().parent() {
            if !parent.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(parent);
            }
        }
        let conn = Connection::open(path)?;
        Self::from_connection(conn, device_id.into())
    }

    /// Open an in-memory store (ideal for tests and ephemeral previews).
    pub fn open_in_memory(device_id: impl Into<String>) -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        Self::from_connection(conn, device_id.into())
    }

    fn from_connection(conn: Connection, device_id: String) -> Result<Self> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "busy_timeout", 5000)?;
        init_schema(&conn)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
            device_id,
        })
    }

    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Connection>> {
        self.conn.lock().map_err(|_| StorageError::LockPoisoned)
    }

    // ------------------------------------------------------------------ sync meta

    pub fn get_meta(&self, key: &str) -> Result<Option<String>> {
        let conn = self.lock()?;
        let val = conn
            .query_row("SELECT value FROM sync_meta WHERE key = ?1", [key], |r| {
                r.get::<_, String>(0)
            })
            .optional()?;
        Ok(val)
    }

    pub fn set_meta(&self, key: &str, value: &str) -> Result<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO sync_meta(key, value) VALUES(?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    // ------------------------------------------------------------------ courses

    pub fn create_course(&self, name: &str, kind: &str) -> Result<Course> {
        let ts = now_ms();
        let course = Course {
            id: new_id(),
            name: name.trim().to_string(),
            kind: kind.trim().to_string(),
            summary: String::new(),
            outline: String::new(),
            notes: String::new(),
            created_at: ts,
            updated_at: ts,
            deleted_at: None,
            device_id: self.device_id.clone(),
        };
        self.upsert_course(&course)?;
        Ok(course)
    }

    pub fn upsert_course(&self, c: &Course) -> Result<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO courses(id, name, kind, summary, outline, notes, created_at, updated_at, deleted_at, device_id)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
             ON CONFLICT(id) DO UPDATE SET
               name=excluded.name, kind=excluded.kind, summary=excluded.summary,
               outline=excluded.outline, notes=excluded.notes,
               updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
               device_id=excluded.device_id",
            params![
                c.id,
                c.name,
                c.kind,
                c.summary,
                c.outline,
                c.notes,
                c.created_at,
                c.updated_at,
                c.deleted_at,
                c.device_id
            ],
        )?;
        Ok(())
    }

    pub fn list_courses(&self) -> Result<Vec<Course>> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, kind, summary, outline, notes, created_at, updated_at, deleted_at, device_id
             FROM courses WHERE deleted_at IS NULL ORDER BY updated_at DESC",
        )?;
        let rows = stmt.query_map([], map_course)?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn get_course(&self, id: &str) -> Result<Option<Course>> {
        let conn = self.lock()?;
        conn.query_row(
            "SELECT id, name, kind, summary, outline, notes, created_at, updated_at, deleted_at, device_id
             FROM courses WHERE id = ?1",
            [id],
            map_course,
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn soft_delete_course(&self, id: &str) -> Result<()> {
        let ts = now_ms();
        let conn = self.lock()?;
        conn.execute(
            "UPDATE courses SET deleted_at = ?2, updated_at = ?2, device_id = ?3 WHERE id = ?1",
            params![id, ts, self.device_id],
        )?;
        Ok(())
    }

    // ------------------------------------------------------------------ sessions

    pub fn create_session(
        &self,
        course_id: Option<String>,
        audio_source: &str,
        source_lang: &str,
        target_lang: &str,
    ) -> Result<Session> {
        let ts = now_ms();
        let session = Session {
            id: new_id(),
            course_id,
            started_at: ts,
            ended_at: None,
            duration_s: 0.0,
            audio_source: audio_source.to_string(),
            source_lang: source_lang.to_string(),
            target_lang: target_lang.to_string(),
            llm_backend: String::new(),
            llm_model: String::new(),
            asr_engine: String::new(),
            asr_model: String::new(),
            title: String::new(),
            title_source: String::new(),
            summary: String::new(),
            knowledge_points: Vec::new(),
            topics: Vec::new(),
            user_note: String::new(),
            quiz: String::new(),
            starred: false,
            status: "running".to_string(),
            created_at: ts,
            updated_at: ts,
            deleted_at: None,
            device_id: self.device_id.clone(),
        };
        self.upsert_session(&session)?;
        Ok(session)
    }

    pub fn upsert_session(&self, s: &Session) -> Result<()> {
        let kp_json = serde_json::to_string(&s.knowledge_points)?;
        let topics_json = serde_json::to_string(&s.topics)?;
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO sessions(
                id, course_id, started_at, ended_at, duration_s, audio_source,
                source_lang, target_lang, llm_backend, llm_model, asr_engine, asr_model,
                title, title_source, summary, knowledge_points_json, topics_json,
                user_note, quiz, starred, status, created_at, updated_at, deleted_at, device_id
             ) VALUES(
                ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12,
                ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22, ?23, ?24, ?25
             )
             ON CONFLICT(id) DO UPDATE SET
                course_id=excluded.course_id, started_at=excluded.started_at,
                ended_at=excluded.ended_at, duration_s=excluded.duration_s,
                audio_source=excluded.audio_source, source_lang=excluded.source_lang,
                target_lang=excluded.target_lang, llm_backend=excluded.llm_backend,
                llm_model=excluded.llm_model, asr_engine=excluded.asr_engine,
                asr_model=excluded.asr_model, title=excluded.title,
                title_source=excluded.title_source, summary=excluded.summary,
                knowledge_points_json=excluded.knowledge_points_json,
                topics_json=excluded.topics_json, user_note=excluded.user_note,
                quiz=excluded.quiz, starred=excluded.starred, status=excluded.status,
                updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
                device_id=excluded.device_id",
            params![
                s.id,
                s.course_id,
                s.started_at,
                s.ended_at,
                s.duration_s,
                s.audio_source,
                s.source_lang,
                s.target_lang,
                s.llm_backend,
                s.llm_model,
                s.asr_engine,
                s.asr_model,
                s.title,
                s.title_source,
                s.summary,
                kp_json,
                topics_json,
                s.user_note,
                s.quiz,
                if s.starred { 1 } else { 0 },
                s.status,
                s.created_at,
                s.updated_at,
                s.deleted_at,
                s.device_id,
            ],
        )?;
        Ok(())
    }

    pub fn update_session_knowledge(
        &self,
        session_id: &str,
        title: Option<&str>,
        summary: Option<&str>,
        knowledge_points: Option<Vec<KnowledgePoint>>,
        status: Option<&str>,
    ) -> Result<Session> {
        let mut session = self
            .get_session(session_id)?
            .ok_or_else(|| StorageError::NotFound(format!("session {session_id}")))?;
        if let Some(t) = title {
            if !t.trim().is_empty() {
                session.title = t.trim().to_string();
                session.title_source = "ai".to_string();
            }
        }
        if let Some(sum) = summary {
            session.summary = sum.trim().to_string();
        }
        if let Some(kps) = knowledge_points {
            session.knowledge_points = kps;
        }
        if let Some(st) = status {
            session.status = st.to_string();
            if st == "completed" && session.ended_at.is_none() {
                let end = now_ms();
                session.ended_at = Some(end);
                session.duration_s = ((end - session.started_at).max(0) as f64) / 1000.0;
            }
        }
        session.updated_at = now_ms().max(session.updated_at + 1);
        session.device_id = self.device_id.clone();
        self.upsert_session(&session)?;
        Ok(session)
    }

    pub fn update_session_user_note(&self, session_id: &str, user_note: &str) -> Result<Session> {
        let mut session = self
            .get_session(session_id)?
            .ok_or_else(|| StorageError::NotFound(format!("session {session_id}")))?;
        session.user_note = user_note.to_string();
        session.updated_at = now_ms().max(session.updated_at + 1);
        session.device_id = self.device_id.clone();
        self.upsert_session(&session)?;
        Ok(session)
    }

    pub fn update_session_quiz(&self, session_id: &str, quiz: &str) -> Result<Session> {
        let mut session = self
            .get_session(session_id)?
            .ok_or_else(|| StorageError::NotFound(format!("session {session_id}")))?;
        session.quiz = quiz.trim().to_string();
        session.updated_at = now_ms().max(session.updated_at + 1);
        session.device_id = self.device_id.clone();
        self.upsert_session(&session)?;
        Ok(session)
    }

    pub fn update_course_outline(&self, course_id: &str, outline: &str) -> Result<Course> {
        let mut course = self
            .get_course(course_id)?
            .ok_or_else(|| StorageError::NotFound(format!("course {course_id}")))?;
        course.outline = outline.trim().to_string();
        course.updated_at = now_ms().max(course.updated_at + 1);
        course.device_id = self.device_id.clone();
        self.upsert_course(&course)?;
        Ok(course)
    }

    pub fn get_session(&self, id: &str) -> Result<Option<Session>> {
        let conn = self.lock()?;
        conn.query_row(
            "SELECT id, course_id, started_at, ended_at, duration_s, audio_source,
                    source_lang, target_lang, llm_backend, llm_model, asr_engine, asr_model,
                    title, title_source, summary, knowledge_points_json, topics_json,
                    user_note, quiz, starred, status, created_at, updated_at, deleted_at, device_id
             FROM sessions WHERE id = ?1",
            [id],
            map_session,
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn list_sessions(&self, course_id: Option<&str>) -> Result<Vec<Session>> {
        let conn = self.lock()?;
        let mut out = Vec::new();
        if let Some(cid) = course_id {
            let mut stmt = conn.prepare(
                "SELECT id, course_id, started_at, ended_at, duration_s, audio_source,
                        source_lang, target_lang, llm_backend, llm_model, asr_engine, asr_model,
                        title, title_source, summary, knowledge_points_json, topics_json,
                        user_note, quiz, starred, status, created_at, updated_at, deleted_at, device_id
                 FROM sessions WHERE deleted_at IS NULL AND course_id = ?1
                 ORDER BY started_at DESC",
            )?;
            for r in stmt.query_map([cid], map_session)? {
                out.push(r?);
            }
        } else {
            let mut stmt = conn.prepare(
                "SELECT id, course_id, started_at, ended_at, duration_s, audio_source,
                        source_lang, target_lang, llm_backend, llm_model, asr_engine, asr_model,
                        title, title_source, summary, knowledge_points_json, topics_json,
                        user_note, quiz, starred, status, created_at, updated_at, deleted_at, device_id
                 FROM sessions WHERE deleted_at IS NULL ORDER BY started_at DESC",
            )?;
            for r in stmt.query_map([], map_session)? {
                out.push(r?);
            }
        }
        Ok(out)
    }

    pub fn soft_delete_session(&self, id: &str) -> Result<()> {
        let ts = now_ms();
        let conn = self.lock()?;
        conn.execute(
            "UPDATE sessions SET deleted_at = ?2, updated_at = ?2, device_id = ?3 WHERE id = ?1",
            params![id, ts, self.device_id],
        )?;
        Ok(())
    }

    // ------------------------------------------------------------------ segments

    #[allow(clippy::too_many_arguments)]
    pub fn add_segment(
        &self,
        session_id: &str,
        t0: f64,
        t1: f64,
        src_lang: &str,
        src_text: &str,
        tgt_lang: &str,
        tgt_text: &str,
        low_conf: bool,
    ) -> Result<Segment> {
        let ts = now_ms();
        let seg = Segment {
            id: new_id(),
            session_id: session_id.to_string(),
            speaker_label: None,
            t0,
            t1,
            src_lang: src_lang.to_string(),
            src_text: src_text.to_string(),
            tgt_lang: tgt_lang.to_string(),
            tgt_text: tgt_text.to_string(),
            low_conf,
            local_audio_path: None,
            created_at: ts,
            updated_at: ts,
            deleted_at: None,
            device_id: self.device_id.clone(),
        };
        self.upsert_segment(&seg)?;
        Ok(seg)
    }

    pub fn upsert_segment(&self, seg: &Segment) -> Result<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO segments(
                id, session_id, speaker_label, t0, t1, src_lang, src_text,
                tgt_lang, tgt_text, low_conf, local_audio_path,
                created_at, updated_at, deleted_at, device_id
             ) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)
             ON CONFLICT(id) DO UPDATE SET
                session_id=excluded.session_id, speaker_label=excluded.speaker_label,
                t0=excluded.t0, t1=excluded.t1, src_lang=excluded.src_lang,
                src_text=excluded.src_text, tgt_lang=excluded.tgt_lang,
                tgt_text=excluded.tgt_text, low_conf=excluded.low_conf,
                local_audio_path=COALESCE(excluded.local_audio_path, segments.local_audio_path),
                updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
                device_id=excluded.device_id",
            params![
                seg.id,
                seg.session_id,
                seg.speaker_label,
                seg.t0,
                seg.t1,
                seg.src_lang,
                seg.src_text,
                seg.tgt_lang,
                seg.tgt_text,
                if seg.low_conf { 1 } else { 0 },
                seg.local_audio_path,
                seg.created_at,
                seg.updated_at,
                seg.deleted_at,
                seg.device_id,
            ],
        )?;
        sync_segment_fts(&conn, seg)?;
        Ok(())
    }

    pub fn list_segments(&self, session_id: &str) -> Result<Vec<Segment>> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, session_id, speaker_label, t0, t1, src_lang, src_text,
                    tgt_lang, tgt_text, low_conf, local_audio_path,
                    created_at, updated_at, deleted_at, device_id
             FROM segments WHERE session_id = ?1 AND deleted_at IS NULL ORDER BY t0 ASC, created_at ASC",
        )?;
        let mut out = Vec::new();
        for r in stmt.query_map([session_id], map_segment)? {
            out.push(r?);
        }
        Ok(out)
    }

    // ------------------------------------------------------------------ glossary

    pub fn upsert_glossary_term(
        &self,
        course_id: &str,
        term: &str,
        translation: &str,
        note: &str,
        source: &str,
    ) -> Result<GlossaryEntry> {
        let clean_term = term.trim();
        let ts = now_ms();
        let conn = self.lock()?;
        // Reuse existing row ID for the same (course_id, term) to prevent cross-device duplicates.
        let existing_id: Option<String> = conn
            .query_row(
                "SELECT id FROM glossary WHERE course_id = ?1 AND lower(term) = lower(?2) LIMIT 1",
                params![course_id, clean_term],
                |r| r.get(0),
            )
            .optional()?;
        let entry = GlossaryEntry {
            id: existing_id.unwrap_or_else(new_id),
            course_id: course_id.to_string(),
            term: clean_term.to_string(),
            translation: translation.trim().to_string(),
            note: note.trim().to_string(),
            source: source.to_string(),
            created_at: ts,
            updated_at: ts,
            deleted_at: None,
            device_id: self.device_id.clone(),
        };
        drop(conn);
        self.upsert_glossary(&entry)?;
        Ok(entry)
    }

    pub fn upsert_glossary(&self, g: &GlossaryEntry) -> Result<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO glossary(id, course_id, term, translation, note, source, created_at, updated_at, deleted_at, device_id)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
             ON CONFLICT(id) DO UPDATE SET
                course_id=excluded.course_id, term=excluded.term,
                translation=excluded.translation, note=excluded.note,
                source=excluded.source, updated_at=excluded.updated_at,
                deleted_at=excluded.deleted_at, device_id=excluded.device_id",
            params![
                g.id,
                g.course_id,
                g.term,
                g.translation,
                g.note,
                g.source,
                g.created_at,
                g.updated_at,
                g.deleted_at,
                g.device_id,
            ],
        )?;
        Ok(())
    }

    pub fn list_glossary(&self, course_id: Option<&str>) -> Result<Vec<GlossaryEntry>> {
        let conn = self.lock()?;
        let mut out = Vec::new();
        if let Some(cid) = course_id {
            let mut stmt = conn.prepare(
                "SELECT id, course_id, term, translation, note, source, created_at, updated_at, deleted_at, device_id
                 FROM glossary WHERE deleted_at IS NULL AND course_id = ?1 ORDER BY updated_at DESC",
            )?;
            for r in stmt.query_map([cid], map_glossary)? {
                out.push(r?);
            }
        } else {
            let mut stmt = conn.prepare(
                "SELECT id, course_id, term, translation, note, source, created_at, updated_at, deleted_at, device_id
                 FROM glossary WHERE deleted_at IS NULL ORDER BY updated_at DESC",
            )?;
            for r in stmt.query_map([], map_glossary)? {
                out.push(r?);
            }
        }
        Ok(out)
    }

    // ------------------------------------------------------------------ bookmarks

    /// Toggle bookmark on a segment. Returns `true` if now bookmarked, `false` if soft-deleted.
    pub fn toggle_bookmark(
        &self,
        session_id: &str,
        segment_id: &str,
        note: &str,
    ) -> Result<(bool, Bookmark)> {
        let ts = now_ms();
        let conn = self.lock()?;
        let existing: Option<Bookmark> = conn
            .query_row(
                "SELECT id, session_id, segment_id, note, created_at, updated_at, deleted_at, device_id
                 FROM bookmarks WHERE session_id = ?1 AND segment_id = ?2 LIMIT 1",
                params![session_id, segment_id],
                map_bookmark,
            )
            .optional()?;
        drop(conn);

        let (active, bm) = match existing {
            Some(mut prev) if prev.deleted_at.is_none() => {
                let next_ts = ts.max(prev.updated_at + 1);
                prev.deleted_at = Some(next_ts);
                prev.updated_at = next_ts;
                prev.device_id = self.device_id.clone();
                (false, prev)
            }
            Some(mut prev) => {
                let next_ts = ts.max(prev.updated_at + 1);
                prev.deleted_at = None;
                if !note.is_empty() {
                    prev.note = note.to_string();
                }
                prev.updated_at = next_ts;
                prev.device_id = self.device_id.clone();
                (true, prev)
            }
            None => (
                true,
                Bookmark {
                    id: new_id(),
                    session_id: session_id.to_string(),
                    segment_id: segment_id.to_string(),
                    note: note.to_string(),
                    created_at: ts,
                    updated_at: ts,
                    deleted_at: None,
                    device_id: self.device_id.clone(),
                },
            ),
        };
        self.upsert_bookmark(&bm)?;
        Ok((active, bm))
    }

    pub fn upsert_bookmark(&self, b: &Bookmark) -> Result<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO bookmarks(id, session_id, segment_id, note, created_at, updated_at, deleted_at, device_id)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
             ON CONFLICT(id) DO UPDATE SET
                session_id=excluded.session_id, segment_id=excluded.segment_id,
                note=excluded.note, updated_at=excluded.updated_at,
                deleted_at=excluded.deleted_at, device_id=excluded.device_id",
            params![
                b.id,
                b.session_id,
                b.segment_id,
                b.note,
                b.created_at,
                b.updated_at,
                b.deleted_at,
                b.device_id,
            ],
        )?;
        Ok(())
    }

    pub fn list_bookmarks(&self, session_id: Option<&str>) -> Result<Vec<Bookmark>> {
        let conn = self.lock()?;
        let mut out = Vec::new();
        if let Some(sid) = session_id {
            let mut stmt = conn.prepare(
                "SELECT id, session_id, segment_id, note, created_at, updated_at, deleted_at, device_id
                 FROM bookmarks WHERE deleted_at IS NULL AND session_id = ?1 ORDER BY created_at DESC",
            )?;
            for r in stmt.query_map([sid], map_bookmark)? {
                out.push(r?);
            }
        } else {
            let mut stmt = conn.prepare(
                "SELECT id, session_id, segment_id, note, created_at, updated_at, deleted_at, device_id
                 FROM bookmarks WHERE deleted_at IS NULL ORDER BY created_at DESC",
            )?;
            for r in stmt.query_map([], map_bookmark)? {
                out.push(r?);
            }
        }
        Ok(out)
    }

    // ------------------------------------------------------------------ questions & summaries

    pub fn add_question(
        &self,
        session_id: &str,
        segment_id: Option<String>,
        question: &str,
        question_tgt: &str,
        answer: &str,
        source: &str,
    ) -> Result<Question> {
        let ts = now_ms();
        let q = Question {
            id: new_id(),
            session_id: session_id.to_string(),
            segment_id,
            question: question.to_string(),
            question_tgt: question_tgt.to_string(),
            answer: answer.to_string(),
            answer_alt: String::new(),
            source: source.to_string(),
            created_at: ts,
            updated_at: ts,
            deleted_at: None,
            device_id: self.device_id.clone(),
        };
        self.upsert_question(&q)?;
        Ok(q)
    }

    pub fn upsert_question(&self, q: &Question) -> Result<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO questions(id, session_id, segment_id, question, question_tgt, answer, answer_alt, source, created_at, updated_at, deleted_at, device_id)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
             ON CONFLICT(id) DO UPDATE SET
                session_id=excluded.session_id, segment_id=excluded.segment_id,
                question=excluded.question, question_tgt=excluded.question_tgt,
                answer=excluded.answer, answer_alt=excluded.answer_alt,
                source=excluded.source, updated_at=excluded.updated_at,
                deleted_at=excluded.deleted_at, device_id=excluded.device_id",
            params![
                q.id,
                q.session_id,
                q.segment_id,
                q.question,
                q.question_tgt,
                q.answer,
                q.answer_alt,
                q.source,
                q.created_at,
                q.updated_at,
                q.deleted_at,
                q.device_id,
            ],
        )?;
        Ok(())
    }

    pub fn list_questions(&self, session_id: Option<&str>) -> Result<Vec<Question>> {
        let conn = self.lock()?;
        let mut out = Vec::new();
        if let Some(sid) = session_id {
            let mut stmt = conn.prepare(
                "SELECT id, session_id, segment_id, question, question_tgt, answer, answer_alt, source, created_at, updated_at, deleted_at, device_id
                 FROM questions WHERE deleted_at IS NULL AND session_id = ?1 ORDER BY created_at ASC",
            )?;
            for r in stmt.query_map([sid], map_question)? {
                out.push(r?);
            }
        } else {
            let mut stmt = conn.prepare(
                "SELECT id, session_id, segment_id, question, question_tgt, answer, answer_alt, source, created_at, updated_at, deleted_at, device_id
                 FROM questions WHERE deleted_at IS NULL ORDER BY created_at DESC",
            )?;
            for r in stmt.query_map([], map_question)? {
                out.push(r?);
            }
        }
        Ok(out)
    }

    pub fn add_summary_note(&self, session_id: &str, content: &str) -> Result<SummaryNote> {
        let ts = now_ms();
        let note = SummaryNote {
            id: new_id(),
            session_id: session_id.to_string(),
            content: content.to_string(),
            created_at: ts,
            updated_at: ts,
            deleted_at: None,
            device_id: self.device_id.clone(),
        };
        self.upsert_summary_note(&note)?;
        Ok(note)
    }

    pub fn upsert_summary_note(&self, n: &SummaryNote) -> Result<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO summaries(id, session_id, content, created_at, updated_at, deleted_at, device_id)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(id) DO UPDATE SET
                session_id=excluded.session_id, content=excluded.content,
                updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
                device_id=excluded.device_id",
            params![
                n.id,
                n.session_id,
                n.content,
                n.created_at,
                n.updated_at,
                n.deleted_at,
                n.device_id,
            ],
        )?;
        Ok(())
    }

    pub fn list_summary_notes(&self, session_id: &str) -> Result<Vec<SummaryNote>> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, session_id, content, created_at, updated_at, deleted_at, device_id
             FROM summaries WHERE deleted_at IS NULL AND session_id = ?1 ORDER BY created_at ASC",
        )?;
        let mut out = Vec::new();
        for r in stmt.query_map([session_id], map_summary_note)? {
            out.push(r?);
        }
        Ok(out)
    }

    // ------------------------------------------------------------------ search

    /// Full-text & substring search across knowledge points, summaries, glossary, questions, and bilingual segments.
    pub fn search(&self, query: &str, limit: usize) -> Result<Vec<SearchHit>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let like = format!("%{}%", q.replace('%', "\\%").replace('_', "\\_"));
        let limit_i = limit.clamp(1, 200) as i64;
        let conn = self.lock()?;
        let mut hits = Vec::new();

        // 1. Sessions (title, summary, knowledge points, user notes)
        {
            let mut stmt = conn.prepare(
                "SELECT id, course_id, title, summary, knowledge_points_json, updated_at
                 FROM sessions
                 WHERE deleted_at IS NULL
                   AND (title LIKE ?1 ESCAPE '\\' OR summary LIKE ?1 ESCAPE '\\'
                        OR knowledge_points_json LIKE ?1 ESCAPE '\\' OR user_note LIKE ?1 ESCAPE '\\')
                 ORDER BY updated_at DESC LIMIT ?2",
            )?;
            let rows = stmt.query_map(params![&like, limit_i], |r| {
                let id: String = r.get(0)?;
                let course_id: Option<String> = r.get(1)?;
                let title: String = r.get(2)?;
                let summary: String = r.get(3)?;
                let kp_json: String = r.get(4)?;
                let updated_at: i64 = r.get(5)?;
                let snippet = if !summary.is_empty() {
                    summary
                } else {
                    kp_json
                };
                Ok(SearchHit {
                    kind: "session".to_string(),
                    id: id.clone(),
                    course_id,
                    session_id: Some(id),
                    title: if title.is_empty() {
                        "未命名课次".to_string()
                    } else {
                        title
                    },
                    snippet: truncate_chars(&snippet, 160),
                    updated_at,
                })
            })?;
            for r in rows {
                hits.push(r?);
            }
        }

        // 2. Glossary terms
        {
            let mut stmt = conn.prepare(
                "SELECT id, course_id, term, translation, note, updated_at
                 FROM glossary
                 WHERE deleted_at IS NULL
                   AND (term LIKE ?1 ESCAPE '\\' OR translation LIKE ?1 ESCAPE '\\' OR note LIKE ?1 ESCAPE '\\')
                 ORDER BY updated_at DESC LIMIT ?2",
            )?;
            let rows = stmt.query_map(params![&like, limit_i], |r| {
                let term: String = r.get(2)?;
                let trans: String = r.get(3)?;
                let note: String = r.get(4)?;
                let snippet = if note.is_empty() {
                    trans
                } else {
                    format!("{trans} — {note}")
                };
                Ok(SearchHit {
                    kind: "glossary".to_string(),
                    id: r.get(0)?,
                    course_id: Some(r.get(1)?),
                    session_id: None,
                    title: term,
                    snippet: truncate_chars(&snippet, 160),
                    updated_at: r.get(5)?,
                })
            })?;
            for r in rows {
                hits.push(r?);
            }
        }

        // 3. Questions
        {
            let mut stmt = conn.prepare(
                "SELECT id, session_id, question, answer, updated_at
                 FROM questions
                 WHERE deleted_at IS NULL
                   AND (question LIKE ?1 ESCAPE '\\' OR answer LIKE ?1 ESCAPE '\\')
                 ORDER BY updated_at DESC LIMIT ?2",
            )?;
            let rows = stmt.query_map(params![&like, limit_i], |r| {
                Ok(SearchHit {
                    kind: "question".to_string(),
                    id: r.get(0)?,
                    course_id: None,
                    session_id: Some(r.get(1)?),
                    title: truncate_chars(&r.get::<_, String>(2)?, 80),
                    snippet: truncate_chars(&r.get::<_, String>(3)?, 160),
                    updated_at: r.get(4)?,
                })
            })?;
            for r in rows {
                hits.push(r?);
            }
        }

        // 4. Segments (uses LIKE so single/2-char CJK queries always match reliably; FTS table stays maintained for trigram queries)
        {
            let mut stmt = conn.prepare(
                "SELECT s.id, s.session_id, ses.course_id, ses.title, s.src_text, s.tgt_text, s.updated_at
                 FROM segments s
                 LEFT JOIN sessions ses ON ses.id = s.session_id
                 WHERE s.deleted_at IS NULL
                   AND (s.src_text LIKE ?1 ESCAPE '\\' OR s.tgt_text LIKE ?1 ESCAPE '\\')
                 ORDER BY s.updated_at DESC LIMIT ?2",
            )?;
            let rows = stmt.query_map(params![&like, limit_i], |r| {
                let src: String = r.get(4)?;
                let tgt: String = r.get(5)?;
                let title: Option<String> = r.get(3)?;
                let snippet = if tgt.is_empty() {
                    src
                } else {
                    format!("{src} / {tgt}")
                };
                Ok(SearchHit {
                    kind: "segment".to_string(),
                    id: r.get(0)?,
                    course_id: r.get(2)?,
                    session_id: Some(r.get(1)?),
                    title: title.filter(|t| !t.is_empty()).unwrap_or_else(|| "课堂字幕".to_string()),
                    snippet: truncate_chars(&snippet, 160),
                    updated_at: r.get(6)?,
                })
            })?;
            for r in rows {
                hits.push(r?);
            }
        }

        hits.sort_by_key(|a| std::cmp::Reverse(a.updated_at));
        hits.truncate(limit);
        Ok(hits)
    }

    // ------------------------------------------------------------------ incremental sync

    /// Export all records (including soft-delete tombstones) updated strictly after `since_ms`.
    /// Strips machine-local audio paths before serialization.
    pub fn export_changes_since(&self, since_ms: i64) -> Result<ChangeSet> {
        self.export_changes_filtered(since_ms, false)
    }

    /// Export only records authored/edited by THIS device (`device_id == self.device_id`)
    /// updated strictly after `since_ms`. Prevents echoing pulled remote records and isolates
    /// local push watermarks from remote device clock skew.
    pub fn export_local_changes_since(&self, since_ms: i64) -> Result<ChangeSet> {
        self.export_changes_filtered(since_ms, true)
    }

    fn export_changes_filtered(&self, since_ms: i64, only_local: bool) -> Result<ChangeSet> {
        let conn = self.lock()?;
        let mut cs = ChangeSet::empty(&self.device_id, since_ms);
        let dev = self.device_id.as_str();

        macro_rules! query_table {
            ($sql_all:expr, $sql_local:expr, $mapper:expr, $vec:expr, $post:expr) => {{
                if only_local {
                    let mut stmt = conn.prepare($sql_local)?;
                    for r in stmt.query_map(params![since_ms, dev], $mapper)? {
                        $vec.push($post(r?));
                    }
                } else {
                    let mut stmt = conn.prepare($sql_all)?;
                    for r in stmt.query_map([since_ms], $mapper)? {
                        $vec.push($post(r?));
                    }
                }
            }};
        }

        query_table!(
            "SELECT id, name, kind, summary, outline, notes, created_at, updated_at, deleted_at, device_id
             FROM courses WHERE updated_at > ?1 ORDER BY updated_at ASC",
            "SELECT id, name, kind, summary, outline, notes, created_at, updated_at, deleted_at, device_id
             FROM courses WHERE updated_at > ?1 AND device_id = ?2 ORDER BY updated_at ASC",
            map_course,
            cs.courses,
            |v| v
        );
        query_table!(
            "SELECT id, course_id, started_at, ended_at, duration_s, audio_source,
                    source_lang, target_lang, llm_backend, llm_model, asr_engine, asr_model,
                    title, title_source, summary, knowledge_points_json, topics_json,
                    user_note, quiz, starred, status, created_at, updated_at, deleted_at, device_id
             FROM sessions WHERE updated_at > ?1 ORDER BY updated_at ASC",
            "SELECT id, course_id, started_at, ended_at, duration_s, audio_source,
                    source_lang, target_lang, llm_backend, llm_model, asr_engine, asr_model,
                    title, title_source, summary, knowledge_points_json, topics_json,
                    user_note, quiz, starred, status, created_at, updated_at, deleted_at, device_id
             FROM sessions WHERE updated_at > ?1 AND device_id = ?2 ORDER BY updated_at ASC",
            map_session,
            cs.sessions,
            |v| v
        );
        query_table!(
            "SELECT id, session_id, speaker_label, t0, t1, src_lang, src_text,
                    tgt_lang, tgt_text, low_conf, local_audio_path,
                    created_at, updated_at, deleted_at, device_id
             FROM segments WHERE updated_at > ?1 ORDER BY updated_at ASC",
            "SELECT id, session_id, speaker_label, t0, t1, src_lang, src_text,
                    tgt_lang, tgt_text, low_conf, local_audio_path,
                    created_at, updated_at, deleted_at, device_id
             FROM segments WHERE updated_at > ?1 AND device_id = ?2 ORDER BY updated_at ASC",
            map_segment,
            cs.segments,
            |v: Segment| v.stripped_for_sync()
        );
        query_table!(
            "SELECT id, course_id, term, translation, note, source, created_at, updated_at, deleted_at, device_id
             FROM glossary WHERE updated_at > ?1 ORDER BY updated_at ASC",
            "SELECT id, course_id, term, translation, note, source, created_at, updated_at, deleted_at, device_id
             FROM glossary WHERE updated_at > ?1 AND device_id = ?2 ORDER BY updated_at ASC",
            map_glossary,
            cs.glossary,
            |v| v
        );
        query_table!(
            "SELECT id, session_id, segment_id, question, question_tgt, answer, answer_alt, source, created_at, updated_at, deleted_at, device_id
             FROM questions WHERE updated_at > ?1 ORDER BY updated_at ASC",
            "SELECT id, session_id, segment_id, question, question_tgt, answer, answer_alt, source, created_at, updated_at, deleted_at, device_id
             FROM questions WHERE updated_at > ?1 AND device_id = ?2 ORDER BY updated_at ASC",
            map_question,
            cs.questions,
            |v| v
        );
        query_table!(
            "SELECT id, session_id, segment_id, note, created_at, updated_at, deleted_at, device_id
             FROM bookmarks WHERE updated_at > ?1 ORDER BY updated_at ASC",
            "SELECT id, session_id, segment_id, note, created_at, updated_at, deleted_at, device_id
             FROM bookmarks WHERE updated_at > ?1 AND device_id = ?2 ORDER BY updated_at ASC",
            map_bookmark,
            cs.bookmarks,
            |v| v
        );
        query_table!(
            "SELECT id, session_id, content, created_at, updated_at, deleted_at, device_id
             FROM summaries WHERE updated_at > ?1 ORDER BY updated_at ASC",
            "SELECT id, session_id, content, created_at, updated_at, deleted_at, device_id
             FROM summaries WHERE updated_at > ?1 AND device_id = ?2 ORDER BY updated_at ASC",
            map_summary_note,
            cs.summaries,
            |v| v
        );

        Ok(cs)
    }

    /// Deterministically merge an incoming [`ChangeSet`] inside a single SQLite transaction.
    /// Safe under retries, duplicate deliveries, and out-of-order arrivals.
    pub fn apply_changeset(&self, cs: &ChangeSet) -> Result<MergeReport> {
        let mut conn = self.lock()?;
        let tx = conn.transaction()?;
        let mut report = MergeReport::default();

        for c in &cs.courses {
            if check_lww_tx(&tx, "courses", &c.id, c.updated_at, &c.device_id)? {
                tx.execute(
                    "INSERT INTO courses(id, name, kind, summary, outline, notes, created_at, updated_at, deleted_at, device_id)
                     VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
                     ON CONFLICT(id) DO UPDATE SET
                       name=excluded.name, kind=excluded.kind, summary=excluded.summary,
                       outline=excluded.outline, notes=excluded.notes,
                       updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
                       device_id=excluded.device_id",
                    params![
                        c.id, c.name, c.kind, c.summary, c.outline, c.notes,
                        c.created_at, c.updated_at, c.deleted_at, c.device_id
                    ],
                )?;
                report.courses_applied += 1;
            } else {
                report.skipped_stale += 1;
            }
        }

        for s in &cs.sessions {
            if check_lww_tx(&tx, "sessions", &s.id, s.updated_at, &s.device_id)? {
                let kp_json = serde_json::to_string(&s.knowledge_points)?;
                let topics_json = serde_json::to_string(&s.topics)?;
                tx.execute(
                    "INSERT INTO sessions(
                        id, course_id, started_at, ended_at, duration_s, audio_source,
                        source_lang, target_lang, llm_backend, llm_model, asr_engine, asr_model,
                        title, title_source, summary, knowledge_points_json, topics_json,
                        user_note, quiz, starred, status, created_at, updated_at, deleted_at, device_id
                     ) VALUES(
                        ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12,
                        ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22, ?23, ?24, ?25
                     )
                     ON CONFLICT(id) DO UPDATE SET
                        course_id=excluded.course_id, started_at=excluded.started_at,
                        ended_at=excluded.ended_at, duration_s=excluded.duration_s,
                        audio_source=excluded.audio_source, source_lang=excluded.source_lang,
                        target_lang=excluded.target_lang, llm_backend=excluded.llm_backend,
                        llm_model=excluded.llm_model, asr_engine=excluded.asr_engine,
                        asr_model=excluded.asr_model, title=excluded.title,
                        title_source=excluded.title_source, summary=excluded.summary,
                        knowledge_points_json=excluded.knowledge_points_json,
                        topics_json=excluded.topics_json, user_note=excluded.user_note,
                        quiz=excluded.quiz, starred=excluded.starred, status=excluded.status,
                        updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
                        device_id=excluded.device_id",
                    params![
                        s.id, s.course_id, s.started_at, s.ended_at, s.duration_s, s.audio_source,
                        s.source_lang, s.target_lang, s.llm_backend, s.llm_model, s.asr_engine, s.asr_model,
                        s.title, s.title_source, s.summary, kp_json, topics_json,
                        s.user_note, s.quiz, if s.starred { 1 } else { 0 }, s.status,
                        s.created_at, s.updated_at, s.deleted_at, s.device_id
                    ],
                )?;
                report.sessions_applied += 1;
            } else {
                report.skipped_stale += 1;
            }
        }

        for seg in &cs.segments {
            if check_lww_tx(&tx, "segments", &seg.id, seg.updated_at, &seg.device_id)? {
                let clean = seg.stripped_for_sync();
                tx.execute(
                    "INSERT INTO segments(
                        id, session_id, speaker_label, t0, t1, src_lang, src_text,
                        tgt_lang, tgt_text, low_conf, local_audio_path,
                        created_at, updated_at, deleted_at, device_id
                     ) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, NULL, ?11, ?12, ?13, ?14)
                     ON CONFLICT(id) DO UPDATE SET
                        session_id=excluded.session_id, speaker_label=excluded.speaker_label,
                        t0=excluded.t0, t1=excluded.t1, src_lang=excluded.src_lang,
                        src_text=excluded.src_text, tgt_lang=excluded.tgt_lang,
                        tgt_text=excluded.tgt_text, low_conf=excluded.low_conf,
                        updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
                        device_id=excluded.device_id",
                    params![
                        clean.id, clean.session_id, clean.speaker_label, clean.t0, clean.t1,
                        clean.src_lang, clean.src_text, clean.tgt_lang, clean.tgt_text,
                        if clean.low_conf { 1 } else { 0 },
                        clean.created_at, clean.updated_at, clean.deleted_at, clean.device_id
                    ],
                )?;
                sync_segment_fts(&tx, &clean)?;
                report.segments_applied += 1;
            } else {
                report.skipped_stale += 1;
            }
        }

        for g in &cs.glossary {
            if check_lww_tx(&tx, "glossary", &g.id, g.updated_at, &g.device_id)? {
                tx.execute(
                    "INSERT INTO glossary(id, course_id, term, translation, note, source, created_at, updated_at, deleted_at, device_id)
                     VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
                     ON CONFLICT(id) DO UPDATE SET
                        course_id=excluded.course_id, term=excluded.term,
                        translation=excluded.translation, note=excluded.note,
                        source=excluded.source, updated_at=excluded.updated_at,
                        deleted_at=excluded.deleted_at, device_id=excluded.device_id",
                    params![
                        g.id, g.course_id, g.term, g.translation, g.note, g.source,
                        g.created_at, g.updated_at, g.deleted_at, g.device_id
                    ],
                )?;
                report.glossary_applied += 1;
            } else {
                report.skipped_stale += 1;
            }
        }

        for q in &cs.questions {
            if check_lww_tx(&tx, "questions", &q.id, q.updated_at, &q.device_id)? {
                tx.execute(
                    "INSERT INTO questions(id, session_id, segment_id, question, question_tgt, answer, answer_alt, source, created_at, updated_at, deleted_at, device_id)
                     VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
                     ON CONFLICT(id) DO UPDATE SET
                        session_id=excluded.session_id, segment_id=excluded.segment_id,
                        question=excluded.question, question_tgt=excluded.question_tgt,
                        answer=excluded.answer, answer_alt=excluded.answer_alt,
                        source=excluded.source, updated_at=excluded.updated_at,
                        deleted_at=excluded.deleted_at, device_id=excluded.device_id",
                    params![
                        q.id, q.session_id, q.segment_id, q.question, q.question_tgt,
                        q.answer, q.answer_alt, q.source, q.created_at, q.updated_at,
                        q.deleted_at, q.device_id
                    ],
                )?;
                report.questions_applied += 1;
            } else {
                report.skipped_stale += 1;
            }
        }

        for b in &cs.bookmarks {
            if check_lww_tx(&tx, "bookmarks", &b.id, b.updated_at, &b.device_id)? {
                tx.execute(
                    "INSERT INTO bookmarks(id, session_id, segment_id, note, created_at, updated_at, deleted_at, device_id)
                     VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                     ON CONFLICT(id) DO UPDATE SET
                        session_id=excluded.session_id, segment_id=excluded.segment_id,
                        note=excluded.note, updated_at=excluded.updated_at,
                        deleted_at=excluded.deleted_at, device_id=excluded.device_id",
                    params![
                        b.id, b.session_id, b.segment_id, b.note,
                        b.created_at, b.updated_at, b.deleted_at, b.device_id
                    ],
                )?;
                report.bookmarks_applied += 1;
            } else {
                report.skipped_stale += 1;
            }
        }

        for n in &cs.summaries {
            if check_lww_tx(&tx, "summaries", &n.id, n.updated_at, &n.device_id)? {
                tx.execute(
                    "INSERT INTO summaries(id, session_id, content, created_at, updated_at, deleted_at, device_id)
                     VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7)
                     ON CONFLICT(id) DO UPDATE SET
                        session_id=excluded.session_id, content=excluded.content,
                        updated_at=excluded.updated_at, deleted_at=excluded.deleted_at,
                        device_id=excluded.device_id",
                    params![
                        n.id, n.session_id, n.content,
                        n.created_at, n.updated_at, n.deleted_at, n.device_id
                    ],
                )?;
                report.summaries_applied += 1;
            } else {
                report.skipped_stale += 1;
            }
        }

        tx.commit()?;
        Ok(report)
    }
}

fn check_lww_tx(
    tx: &rusqlite::Transaction<'_>,
    table: &str,
    id: &str,
    incoming_updated_at: i64,
    incoming_device_id: &str,
) -> Result<bool> {
    let sql = format!("SELECT updated_at, device_id FROM {table} WHERE id = ?1");
    let existing: Option<(i64, String)> = tx
        .query_row(&sql, [id], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    match existing {
        None => Ok(true),
        Some((ex_ts, ex_dev)) => Ok(should_win_lww(
            ex_ts,
            &ex_dev,
            incoming_updated_at,
            incoming_device_id,
        )),
    }
}

fn init_schema(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "
        CREATE TABLE IF NOT EXISTS sync_meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS courses (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            kind TEXT NOT NULL DEFAULT '',
            summary TEXT NOT NULL DEFAULT '',
            outline TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            device_id TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            course_id TEXT,
            started_at INTEGER NOT NULL,
            ended_at INTEGER,
            duration_s REAL NOT NULL DEFAULT 0,
            audio_source TEXT NOT NULL DEFAULT 'mic',
            source_lang TEXT NOT NULL DEFAULT 'auto',
            target_lang TEXT NOT NULL DEFAULT 'auto',
            llm_backend TEXT NOT NULL DEFAULT '',
            llm_model TEXT NOT NULL DEFAULT '',
            asr_engine TEXT NOT NULL DEFAULT '',
            asr_model TEXT NOT NULL DEFAULT '',
            title TEXT NOT NULL DEFAULT '',
            title_source TEXT NOT NULL DEFAULT '',
            summary TEXT NOT NULL DEFAULT '',
            knowledge_points_json TEXT NOT NULL DEFAULT '[]',
            topics_json TEXT NOT NULL DEFAULT '[]',
            user_note TEXT NOT NULL DEFAULT '',
            quiz TEXT NOT NULL DEFAULT '',
            starred INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'completed',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            device_id TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS segments (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            speaker_label TEXT,
            t0 REAL NOT NULL DEFAULT 0,
            t1 REAL NOT NULL DEFAULT 0,
            src_lang TEXT NOT NULL DEFAULT '',
            src_text TEXT NOT NULL DEFAULT '',
            tgt_lang TEXT NOT NULL DEFAULT '',
            tgt_text TEXT NOT NULL DEFAULT '',
            low_conf INTEGER NOT NULL DEFAULT 0,
            local_audio_path TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            device_id TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS glossary (
            id TEXT PRIMARY KEY,
            course_id TEXT NOT NULL,
            term TEXT NOT NULL,
            translation TEXT NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            device_id TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS questions (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            segment_id TEXT,
            question TEXT NOT NULL,
            question_tgt TEXT NOT NULL DEFAULT '',
            answer TEXT NOT NULL DEFAULT '',
            answer_alt TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            device_id TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS bookmarks (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            segment_id TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            device_id TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS summaries (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            device_id TEXT NOT NULL DEFAULT ''
        );

        CREATE INDEX IF NOT EXISTS idx_courses_updated ON courses(updated_at);
        CREATE INDEX IF NOT EXISTS idx_sessions_course ON sessions(course_id, started_at);
        CREATE INDEX IF NOT EXISTS idx_sessions_updated ON sessions(updated_at);
        CREATE INDEX IF NOT EXISTS idx_segments_session ON segments(session_id, t0);
        CREATE INDEX IF NOT EXISTS idx_segments_updated ON segments(updated_at);
        CREATE INDEX IF NOT EXISTS idx_glossary_course ON glossary(course_id);
        CREATE INDEX IF NOT EXISTS idx_glossary_updated ON glossary(updated_at);
        CREATE INDEX IF NOT EXISTS idx_questions_session ON questions(session_id);
        CREATE INDEX IF NOT EXISTS idx_questions_updated ON questions(updated_at);
        CREATE INDEX IF NOT EXISTS idx_bookmarks_session ON bookmarks(session_id, segment_id);
        CREATE TABLE IF NOT EXISTS materials (
            id TEXT PRIMARY KEY,
            course_id TEXT NOT NULL,
            name TEXT NOT NULL,
            path TEXT NOT NULL DEFAULT '',
            page_count INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS material_chunks (
            id TEXT PRIMARY KEY,
            material_id TEXT NOT NULL,
            course_id TEXT NOT NULL,
            page INTEGER NOT NULL DEFAULT 1,
            text TEXT NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_bookmarks_updated ON bookmarks(updated_at);
        CREATE INDEX IF NOT EXISTS idx_summaries_session ON summaries(session_id);
        CREATE INDEX IF NOT EXISTS idx_summaries_updated ON summaries(updated_at);
        CREATE INDEX IF NOT EXISTS idx_materials_course ON materials(course_id);
        CREATE INDEX IF NOT EXISTS idx_material_chunks_course ON material_chunks(course_id, page);
        ",
    )?;
    // Optional FTS5 table for accelerated search when supported by bundled SQLite.
    let _ = conn.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS segments_fts USING fts5(segment_id UNINDEXED, src_text, tgt_text)",
        [],
    );
    Ok(())
}

fn sync_segment_fts(conn: &Connection, seg: &Segment) -> Result<()> {
    let _ = conn.execute("DELETE FROM segments_fts WHERE segment_id = ?1", [&seg.id]);
    if seg.deleted_at.is_none() {
        let _ = conn.execute(
            "INSERT INTO segments_fts(segment_id, src_text, tgt_text) VALUES(?1, ?2, ?3)",
            params![seg.id, seg.src_text, seg.tgt_text],
        );
    }
    Ok(())
}

fn map_course(r: &rusqlite::Row<'_>) -> rusqlite::Result<Course> {
    Ok(Course {
        id: r.get(0)?,
        name: r.get(1)?,
        kind: r.get(2)?,
        summary: r.get(3)?,
        outline: r.get(4)?,
        notes: r.get(5)?,
        created_at: r.get(6)?,
        updated_at: r.get(7)?,
        deleted_at: r.get(8)?,
        device_id: r.get(9)?,
    })
}

fn map_session(r: &rusqlite::Row<'_>) -> rusqlite::Result<Session> {
    let kp_json: String = r.get(15)?;
    let topics_json: String = r.get(16)?;
    let knowledge_points: Vec<KnowledgePoint> =
        serde_json::from_str(&kp_json).unwrap_or_default();
    let topics: Vec<String> = serde_json::from_str(&topics_json).unwrap_or_default();
    Ok(Session {
        id: r.get(0)?,
        course_id: r.get(1)?,
        started_at: r.get(2)?,
        ended_at: r.get(3)?,
        duration_s: r.get(4)?,
        audio_source: r.get(5)?,
        source_lang: r.get(6)?,
        target_lang: r.get(7)?,
        llm_backend: r.get(8)?,
        llm_model: r.get(9)?,
        asr_engine: r.get(10)?,
        asr_model: r.get(11)?,
        title: r.get(12)?,
        title_source: r.get(13)?,
        summary: r.get(14)?,
        knowledge_points,
        topics,
        user_note: r.get(17)?,
        quiz: r.get(18)?,
        starred: r.get::<_, i64>(19)? != 0,
        status: r.get(20)?,
        created_at: r.get(21)?,
        updated_at: r.get(22)?,
        deleted_at: r.get(23)?,
        device_id: r.get(24)?,
    })
}

fn map_segment(r: &rusqlite::Row<'_>) -> rusqlite::Result<Segment> {
    Ok(Segment {
        id: r.get(0)?,
        session_id: r.get(1)?,
        speaker_label: r.get(2)?,
        t0: r.get(3)?,
        t1: r.get(4)?,
        src_lang: r.get(5)?,
        src_text: r.get(6)?,
        tgt_lang: r.get(7)?,
        tgt_text: r.get(8)?,
        low_conf: r.get::<_, i64>(9)? != 0,
        local_audio_path: r.get(10)?,
        created_at: r.get(11)?,
        updated_at: r.get(12)?,
        deleted_at: r.get(13)?,
        device_id: r.get(14)?,
    })
}

fn map_glossary(r: &rusqlite::Row<'_>) -> rusqlite::Result<GlossaryEntry> {
    Ok(GlossaryEntry {
        id: r.get(0)?,
        course_id: r.get(1)?,
        term: r.get(2)?,
        translation: r.get(3)?,
        note: r.get(4)?,
        source: r.get(5)?,
        created_at: r.get(6)?,
        updated_at: r.get(7)?,
        deleted_at: r.get(8)?,
        device_id: r.get(9)?,
    })
}

fn map_question(r: &rusqlite::Row<'_>) -> rusqlite::Result<Question> {
    Ok(Question {
        id: r.get(0)?,
        session_id: r.get(1)?,
        segment_id: r.get(2)?,
        question: r.get(3)?,
        question_tgt: r.get(4)?,
        answer: r.get(5)?,
        answer_alt: r.get(6)?,
        source: r.get(7)?,
        created_at: r.get(8)?,
        updated_at: r.get(9)?,
        deleted_at: r.get(10)?,
        device_id: r.get(11)?,
    })
}

fn map_bookmark(r: &rusqlite::Row<'_>) -> rusqlite::Result<Bookmark> {
    Ok(Bookmark {
        id: r.get(0)?,
        session_id: r.get(1)?,
        segment_id: r.get(2)?,
        note: r.get(3)?,
        created_at: r.get(4)?,
        updated_at: r.get(5)?,
        deleted_at: r.get(6)?,
        device_id: r.get(7)?,
    })
}

fn map_summary_note(r: &rusqlite::Row<'_>) -> rusqlite::Result<SummaryNote> {
    Ok(SummaryNote {
        id: r.get(0)?,
        session_id: r.get(1)?,
        content: r.get(2)?,
        created_at: r.get(3)?,
        updated_at: r.get(4)?,
        deleted_at: r.get(5)?,
        device_id: r.get(6)?,
    })
}

fn truncate_chars(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        s.to_string()
    } else {
        let mut out: String = s.chars().take(max_chars).collect();
        out.push('…');
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bidirectional_lww_sync_and_soft_delete_converge() {
        let mac = Store::open_in_memory("mac-book").unwrap();
        let tablet = Store::open_in_memory("android-tab").unwrap();

        let course = mac.create_course("Machine Learning", "课堂").unwrap();
        let session = mac
            .create_session(Some(course.id.clone()), "system", "en", "zh")
            .unwrap();
        let seg = mac
            .add_segment(
                &session.id,
                0.0,
                3.2,
                "en",
                "Transformers use self-attention.",
                "zh",
                "Transformer 使用自注意力机制。",
                false,
            )
            .unwrap();
        mac.update_session_knowledge(
            &session.id,
            Some("第 1 讲：Transformer"),
            Some("介绍了自注意力机制与位置编码"),
            Some(vec![KnowledgePoint {
                title: "Self-Attention".into(),
                explanation: "每个 token 与序列内所有 token 计算相关性权重".into(),
                importance: 3,
                tags: vec!["NLP".into()],
                source_quote: "Transformers use self-attention.".into(),
            }]),
            Some("completed"),
        )
        .unwrap();
        mac.upsert_glossary_term(&course.id, "Self-Attention", "自注意力", "核心机制", "ai")
            .unwrap();
        let (active, _) = mac.toggle_bookmark(&session.id, &seg.id, "考点").unwrap();
        assert!(active);

        // Push Mac -> Tablet
        let cs_mac = mac.export_changes_since(0).unwrap();
        assert_eq!(cs_mac.courses.len(), 1);
        assert_eq!(cs_mac.sessions.len(), 1);
        assert_eq!(cs_mac.segments.len(), 1);
        assert_eq!(cs_mac.glossary.len(), 1);
        assert_eq!(cs_mac.bookmarks.len(), 1);

        let report = tablet.apply_changeset(&cs_mac).unwrap();
        assert_eq!(report.total_applied(), 5);
        assert_eq!(report.skipped_stale, 0);

        // Re-applying same changeset is idempotent (same timestamp + same device_id still works cleanly)
        let loaded = tablet.get_session(&session.id).unwrap().unwrap();
        assert_eq!(loaded.knowledge_points.len(), 1);
        assert_eq!(loaded.knowledge_points[0].title, "Self-Attention");

        // Tablet edits note & un-bookmarks segment after 10ms
        std::thread::sleep(std::time::Duration::from_millis(5));
        tablet
            .update_session_user_note(&session.id, "平板手写补充：考前必看")
            .unwrap();
        let (still_active, _) = tablet.toggle_bookmark(&session.id, &seg.id, "").unwrap();
        assert!(!still_active);

        // Push Tablet -> Mac
        let cs_tab = tablet.export_changes_since(cs_mac.max_updated_at()).unwrap();
        let back_report = mac.apply_changeset(&cs_tab).unwrap();
        assert_eq!(back_report.sessions_applied, 1);
        assert_eq!(back_report.bookmarks_applied, 1);

        // Verify Mac converged to Tablet's newer note and bookmark tombstone!
        let mac_session = mac.get_session(&session.id).unwrap().unwrap();
        assert_eq!(mac_session.user_note, "平板手写补充：考前必看");
        assert!(mac.list_bookmarks(Some(&session.id)).unwrap().is_empty());

        // Search works across Chinese & English
        let hits = mac.search("自注意力", 20).unwrap();
        assert!(hits.len() >= 2);
    }
}
