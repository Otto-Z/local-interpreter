"""会话历史库：SQLite 存储、检索与音频路径管理。"""
from __future__ import annotations

import json
import os
import shutil
import sqlite3
import threading
import time
from datetime import datetime
from pathlib import Path

from .config import DATA_DIR

DB_PATH = DATA_DIR / "history.db"
AUDIO_DIR = DATA_DIR / "audio"


_STOP = set("的了和是在有与及就也都而及其这那你们我们什么怎么为什么多少请问")


def _query_terms(query: str) -> list[str]:
    terms: set[str] = set()
    for run in __import__("re").findall(r"[\u4e00-\u9fff]+", query):
        for size in (2, 3):
            for i in range(len(run) - size + 1):
                piece = run[i:i + size]
                if piece not in _STOP:
                    terms.add(piece)
    for word in __import__("re").findall(r"[A-Za-z]{3,}", query):
        terms.add(word.lower())
    return list(terms)[:16]


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


class Store:
    def __init__(self, path: Path = DB_PATH):
        self.path = path
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(path), check_same_thread=False)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self._init()
        self.mark_interrupted()

    def _init(self) -> None:
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS courses (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  created_at TEXT,
                  summary TEXT DEFAULT '',
                  notes TEXT DEFAULT ''
                );
                CREATE TABLE IF NOT EXISTS sessions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  started_at TEXT, ended_at TEXT, duration_s REAL,
                  audio_source TEXT, source_lang TEXT, target_lang TEXT,
                  llm_backend TEXT, llm_model TEXT,
                  asr_engine TEXT, asr_model TEXT,
                  title TEXT, summary TEXT, topics TEXT, kind TEXT,
                  starred INTEGER DEFAULT 0, status TEXT DEFAULT 'running',
                  tag_state TEXT DEFAULT 'pending'
                );
                CREATE TABLE IF NOT EXISTS segments (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id INTEGER NOT NULL,
                  t0 REAL, t1 REAL,
                  src_lang TEXT, src_text TEXT,
                  tgt_lang TEXT, tgt_text TEXT,
                  speaker_id INTEGER, audio_path TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_seg_session ON segments(session_id, t0);
                CREATE TABLE IF NOT EXISTS speakers (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id INTEGER NOT NULL,
                  label TEXT, color TEXT, embedding BLOB
                );
                CREATE TABLE IF NOT EXISTS questions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id INTEGER NOT NULL,
                  segment_id INTEGER,
                  question TEXT, question_tgt TEXT,
                  answer TEXT, answer_alt TEXT, source TEXT,
                  created_at TEXT
                );
                CREATE TABLE IF NOT EXISTS summaries (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id INTEGER NOT NULL,
                  text TEXT, created_at TEXT
                );
                CREATE TABLE IF NOT EXISTS materials (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT, path TEXT, pages INTEGER DEFAULT 0,
                  created_at TEXT
                );
                CREATE TABLE IF NOT EXISTS material_chunks (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  material_id INTEGER NOT NULL,
                  page INTEGER, text TEXT
                );
                CREATE TABLE IF NOT EXISTS glossary (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  course_id INTEGER NOT NULL,
                  term TEXT NOT NULL,
                  translation TEXT DEFAULT '',
                  note TEXT DEFAULT '',
                  source TEXT DEFAULT '',
                  created_at TEXT,
                  UNIQUE(course_id, term)
                );
                CREATE TABLE IF NOT EXISTS bookmarks (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id INTEGER NOT NULL,
                  segment_id INTEGER,
                  note TEXT DEFAULT '',
                  created_at TEXT
                );
                """
            )
            self._create_fts()
            self._migrate()
            self.conn.commit()

    def _create_fts(self) -> None:
        for table, cols in (
            ("segments_fts", "src_text, tgt_text"),
            ("chunks_fts", "text"),
        ):
            try:
                self.conn.execute(
                    f"CREATE VIRTUAL TABLE IF NOT EXISTS {table} "
                    f"USING fts5({cols}, tokenize='trigram')"
                )
            except sqlite3.OperationalError:
                self.conn.execute(
                    f"CREATE VIRTUAL TABLE IF NOT EXISTS {table} USING fts5({cols})"
                )

    def _migrate(self) -> None:
        session_cols = {r["name"] for r in self.conn.execute("PRAGMA table_info(sessions)")}
        if "course_id" not in session_cols:
            self.conn.execute("ALTER TABLE sessions ADD COLUMN course_id INTEGER")
        if "user_note" not in session_cols:
            self.conn.execute("ALTER TABLE sessions ADD COLUMN user_note TEXT DEFAULT ''")
        if "quiz" not in session_cols:
            self.conn.execute("ALTER TABLE sessions ADD COLUMN quiz TEXT DEFAULT ''")
        segment_cols = {r["name"] for r in self.conn.execute("PRAGMA table_info(segments)")}
        if "confidence" not in segment_cols:
            self.conn.execute(
                "ALTER TABLE segments ADD COLUMN confidence TEXT DEFAULT ''"
            )
        course_cols = {r["name"] for r in self.conn.execute("PRAGMA table_info(courses)")}
        if "kind" not in course_cols:
            self.conn.execute("ALTER TABLE courses ADD COLUMN kind TEXT DEFAULT '课堂'")
        if "outline" not in course_cols:
            self.conn.execute("ALTER TABLE courses ADD COLUMN outline TEXT DEFAULT ''")
        material_cols = {r["name"] for r in self.conn.execute("PRAGMA table_info(materials)")}
        if "course_id" not in material_cols:
            self.conn.execute("ALTER TABLE materials ADD COLUMN course_id INTEGER")
        row = self.conn.execute("SELECT id FROM courses ORDER BY id LIMIT 1").fetchone()
        if row:
            self.default_course_id = int(row["id"])
        else:
            cur = self.conn.execute(
                "INSERT INTO courses(name, created_at) VALUES(?,?)",
                ("未分类", _now()),
            )
            self.default_course_id = int(cur.lastrowid)
        self.conn.execute(
            "UPDATE sessions SET course_id=? WHERE course_id IS NULL",
            (self.default_course_id,),
        )
        self.conn.execute(
            "UPDATE materials SET course_id=? WHERE course_id IS NULL",
            (self.default_course_id,),
        )

    def _exec(self, sql: str, args: tuple = ()):
        with self._lock:
            return self.conn.execute(sql, args)

    # ---------- 会话 ----------
    def start_session(
        self,
        audio_source: str,
        source_lang: str,
        target_lang: str,
        llm_backend: str,
        llm_model: str,
        asr_engine: str,
        asr_model: str,
        course_id: int | None = None,
    ) -> int:
        cur = self._exec(
            """INSERT INTO sessions(started_at, audio_source, source_lang, target_lang,
               llm_backend, llm_model, asr_engine, asr_model, course_id, status)
               VALUES(?,?,?,?,?,?,?,?,?, 'running')""",
            (
                _now(),
                audio_source,
                source_lang,
                target_lang,
                llm_backend,
                llm_model,
                asr_engine,
                asr_model,
                course_id or self.default_course_id,
            ),
        )
        with self._lock:
            self.conn.commit()
        return int(cur.lastrowid)

    def add_segment(
        self,
        session_id: int,
        t0: float,
        t1: float,
        src_lang: str,
        src_text: str,
        tgt_lang: str,
        tgt_text: str,
        audio_path: str = "",
        speaker_id: int | None = None,
        confidence: str = "",
    ) -> int:
        cur = self._exec(
            """INSERT INTO segments(session_id, t0, t1, src_lang, src_text,
               tgt_lang, tgt_text, speaker_id, audio_path, confidence)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
            (
                session_id,
                t0,
                t1,
                src_lang,
                src_text,
                tgt_lang,
                tgt_text,
                speaker_id,
                audio_path,
                confidence,
            ),
        )
        seg_id = int(cur.lastrowid)
        self._exec(
            "INSERT INTO segments_fts(rowid, src_text, tgt_text) VALUES(?,?,?)",
            (seg_id, src_text, tgt_text),
        )
        with self._lock:
            self.conn.commit()
        return seg_id

    def update_segment_translation(self, segment_id: int, tgt_lang: str, tgt_text: str) -> None:
        self._exec(
            "UPDATE segments SET tgt_lang=?, tgt_text=? WHERE id=?",
            (tgt_lang, tgt_text, segment_id),
        )
        self._exec("DELETE FROM segments_fts WHERE rowid=?", (segment_id,))
        row = self._exec("SELECT src_text FROM segments WHERE id=?", (segment_id,)).fetchone()
        if row:
            self._exec(
                "INSERT INTO segments_fts(rowid, src_text, tgt_text) VALUES(?,?,?)",
                (segment_id, row["src_text"], tgt_text),
            )
        with self._lock:
            self.conn.commit()

    def update_segment_confidence(self, segment_id: int, confidence: str) -> None:
        self._exec(
            "UPDATE segments SET confidence=? WHERE id=?", (confidence, segment_id)
        )
        with self._lock:
            self.conn.commit()

    def update_segment_audio(self, segment_id: int, audio_path: str) -> None:
        self._exec("UPDATE segments SET audio_path=? WHERE id=?", (audio_path, segment_id))
        with self._lock:
            self.conn.commit()

    def add_question(self, session_id: int, segment_id: int | None, **fields) -> int:
        cur = self._exec(
            """INSERT INTO questions(session_id, segment_id, question, question_tgt,
               answer, answer_alt, source, created_at) VALUES(?,?,?,?,?,?,?,?)""",
            (
                session_id,
                segment_id or 0,
                fields.get("question", ""),
                fields.get("question_tgt", ""),
                fields.get("answer", ""),
                fields.get("answer_alt", ""),
                fields.get("source", ""),
                _now(),
            ),
        )
        with self._lock:
            self.conn.commit()
        return int(cur.lastrowid)

    def add_summary(self, session_id: int, text: str) -> None:
        self._exec(
            "INSERT INTO summaries(session_id, text, created_at) VALUES(?,?,?)",
            (session_id, text, _now()),
        )
        with self._lock:
            self.conn.commit()

    def finish_session(self, session_id: int, duration_s: float, status: str = "finished") -> None:
        self._exec(
            "UPDATE sessions SET ended_at=?, duration_s=?, status=? WHERE id=?",
            (_now(), duration_s, status, session_id),
        )
        with self._lock:
            self.conn.commit()

    def set_tags(
        self,
        session_id: int,
        title: str,
        summary: str,
        topics: list[str],
        kind: str,
        state: str = "ready",
    ) -> None:
        self._exec(
            "UPDATE sessions SET title=?, summary=?, topics=?, kind=?, tag_state=? WHERE id=?",
            (title, summary, json.dumps(topics, ensure_ascii=False), kind, state, session_id),
        )
        with self._lock:
            self.conn.commit()

    def mark_interrupted(self) -> None:
        self._exec(
            "UPDATE sessions SET status='interrupted', ended_at=COALESCE(ended_at, ?) "
            "WHERE status='running'",
            (_now(),),
        )
        with self._lock:
            self.conn.commit()

    # ---------- 查询 ----------
    @staticmethod
    def _session_dict(row: sqlite3.Row) -> dict:
        data = dict(row)
        try:
            data["topics"] = json.loads(data.get("topics") or "[]")
        except Exception:
            data["topics"] = []
        return data

    def list_sessions(
        self, q: str = "", tag: str = "", limit: int = 100, course_id: int | None = None
    ) -> list[dict]:
        where = []
        args: list = []
        if course_id is not None:
            where.append("s.course_id = ?")
            args.append(course_id)
        if tag:
            where.append("s.topics LIKE ?")
            args.append(f"%{tag}%")
        if q:
            where.append(
                "s.id IN (SELECT session_id FROM segments WHERE id IN "
                "(SELECT rowid FROM segments_fts WHERE segments_fts MATCH ?))"
            )
            args.append(q)
        clause = ("WHERE " + " AND ".join(where)) if where else ""
        try:
            rows = self._exec(
                f"SELECT s.*, (SELECT COUNT(*) FROM segments WHERE session_id=s.id) AS seg_count "
                f"FROM sessions s {clause} ORDER BY s.started_at DESC LIMIT ?",
                tuple(args + [limit]),
            ).fetchall()
        except sqlite3.OperationalError:
            # 用户输入的 FTS 语法不合法时不要 500，退化为空结果
            return []
        return [self._session_dict(r) for r in rows]

    def get_session(self, session_id: int) -> dict | None:
        row = self._exec("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        if not row:
            return None
        data = self._session_dict(row)
        data["segments"] = [
            dict(r)
            for r in self._exec(
                "SELECT * FROM segments WHERE session_id=? ORDER BY t0", (session_id,)
            ).fetchall()
        ]
        data["questions"] = [
            dict(r)
            for r in self._exec(
                "SELECT * FROM questions WHERE session_id=? ORDER BY id", (session_id,)
            ).fetchall()
        ]
        data["summaries"] = [
            dict(r)
            for r in self._exec(
                "SELECT * FROM summaries WHERE session_id=? ORDER BY id", (session_id,)
            ).fetchall()
        ]
        data["speakers"] = [
            dict(r)
            for r in self._exec(
                "SELECT id, label, color FROM speakers WHERE session_id=?", (session_id,)
            ).fetchall()
        ]
        data["bookmarks"] = self.list_bookmarks(session_id)
        return data

    def delete_session(self, session_id: int) -> None:
        self._exec("DELETE FROM segments_fts WHERE rowid IN (SELECT id FROM segments WHERE session_id=?)", (session_id,))
        for table in ("segments", "questions", "summaries", "speakers", "bookmarks"):
            self._exec(f"DELETE FROM {table} WHERE session_id=?", (session_id,))
        self._exec("DELETE FROM sessions WHERE id=?", (session_id,))
        with self._lock:
            self.conn.commit()
        try:
            shutil.rmtree(AUDIO_DIR / str(session_id), ignore_errors=True)
        except Exception:
            pass

    def all_tags(self) -> list[str]:
        rows = self._exec("SELECT topics FROM sessions WHERE topics IS NOT NULL").fetchall()
        tags: dict[str, int] = {}
        for row in rows:
            try:
                for t in json.loads(row["topics"] or "[]"):
                    t = str(t).strip()
                    if t:
                        tags[t] = tags.get(t, 0) + 1
            except Exception:
                continue
        return [t for t, _ in sorted(tags.items(), key=lambda kv: -kv[1])]

    def get_segment_audio(self, segment_id: int) -> str | None:
        row = self._exec("SELECT audio_path FROM segments WHERE id=?", (segment_id,)).fetchone()
        return row["audio_path"] if row else None

    # ---------- 课程 ----------
    def list_courses(self) -> list[dict]:
        rows = self._exec(
            """SELECT c.*,
               (SELECT COUNT(*) FROM sessions s WHERE s.course_id=c.id) AS lesson_count,
               (SELECT COUNT(*) FROM materials m WHERE m.course_id=c.id) AS material_count,
               (SELECT MAX(started_at) FROM sessions s WHERE s.course_id=c.id) AS last_at
               FROM courses c
               ORDER BY (last_at IS NULL), COALESCE(last_at, c.created_at) DESC"""
        ).fetchall()
        items = [dict(r) for r in rows]
        for item in items:
            item["is_default"] = item["id"] == self.default_course_id
        return items

    def create_course(self, name: str, kind: str = "课堂") -> int:
        cur = self._exec(
            "INSERT INTO courses(name, created_at, kind) VALUES(?,?,?)",
            (name.strip()[:60] or "新课程", _now(), (kind or "课堂")[:12]),
        )
        with self._lock:
            self.conn.commit()
        return int(cur.lastrowid)

    def update_course(self, course_id: int, **fields) -> None:
        allowed = {
            k: v
            for k, v in fields.items()
            if k in ("name", "summary", "notes", "kind", "outline")
        }
        if not allowed:
            return
        sets = ", ".join(f"{k}=?" for k in allowed)
        self._exec(
            f"UPDATE courses SET {sets} WHERE id=?",
            tuple(allowed.values()) + (course_id,),
        )
        with self._lock:
            self.conn.commit()

    def delete_course(self, course_id: int) -> None:
        if course_id == self.default_course_id:
            return
        self._exec(
            "UPDATE sessions SET course_id=? WHERE course_id=?",
            (self.default_course_id, course_id),
        )
        self._exec(
            "UPDATE materials SET course_id=? WHERE course_id=?",
            (self.default_course_id, course_id),
        )
        self._exec("DELETE FROM glossary WHERE course_id=?", (course_id,))
        self._exec("DELETE FROM courses WHERE id=?", (course_id,))
        with self._lock:
            self.conn.commit()

    def course_exists(self, course_id: int) -> bool:
        row = self._exec(
            "SELECT 1 FROM courses WHERE id=?", (course_id,)
        ).fetchone()
        return row is not None

    def get_course(self, course_id: int) -> dict | None:
        row = self._exec("SELECT * FROM courses WHERE id=?", (course_id,)).fetchone()
        if not row:
            return None
        data = dict(row)
        data["is_default"] = course_id == self.default_course_id
        data["sessions"] = self.list_sessions(limit=200, course_id=course_id)
        data["materials"] = self.list_materials(course_id)
        data["glossary"] = self.list_glossary(course_id)
        return data

    def course_context(self, course_id: int, limit: int = 6) -> str:
        rows = self._exec(
            """SELECT title, summary, started_at FROM sessions
               WHERE course_id=? AND (summary IS NOT NULL OR title IS NOT NULL)
               ORDER BY started_at DESC LIMIT ?""",
            (course_id, limit),
        ).fetchall()
        lines = []
        for row in rows:
            title = row["title"] or "未命名课次"
            date = (row["started_at"] or "")[:10]
            summary = (row["summary"] or "").strip()
            lines.append(f"- [{date}] {title}：{summary[:120]}")
        return "\n".join(lines)

    def set_session_note(self, session_id: int, note: str) -> None:
        self._exec("UPDATE sessions SET user_note=? WHERE id=?", (note, session_id))
        with self._lock:
            self.conn.commit()

    # ---------- 课件 ----------
    def add_material(
        self,
        name: str,
        path: str,
        chunks: list[tuple[int, str]],
        course_id: int | None = None,
    ) -> int:
        cur = self._exec(
            "INSERT INTO materials(name, path, pages, created_at, course_id) VALUES(?,?,?,?,?)",
            (
                name,
                path,
                max((p for p, _ in chunks), default=0),
                _now(),
                course_id or self.default_course_id,
            ),
        )
        material_id = int(cur.lastrowid)
        for page, text in chunks:
            c = self._exec(
                "INSERT INTO material_chunks(material_id, page, text) VALUES(?,?,?)",
                (material_id, page, text),
            )
            self._exec(
                "INSERT INTO chunks_fts(rowid, text) VALUES(?,?)",
                (int(c.lastrowid), text),
            )
        with self._lock:
            self.conn.commit()
        return material_id

    def get_material(self, material_id: int) -> dict | None:
        row = self._exec("SELECT * FROM materials WHERE id=?", (material_id,)).fetchone()
        return dict(row) if row else None

    def list_materials(self, course_id: int | None = None) -> list[dict]:
        if course_id is None:
            rows = self._exec("SELECT * FROM materials ORDER BY id DESC").fetchall()
        else:
            rows = self._exec(
                "SELECT * FROM materials WHERE course_id=? ORDER BY id DESC",
                (course_id,),
            ).fetchall()
        return [dict(r) for r in rows]

    def delete_material(self, material_id: int) -> None:
        self._exec(
            "DELETE FROM chunks_fts WHERE rowid IN "
            "(SELECT id FROM material_chunks WHERE material_id=?)",
            (material_id,),
        )
        self._exec("DELETE FROM material_chunks WHERE material_id=?", (material_id,))
        self._exec("DELETE FROM materials WHERE id=?", (material_id,))
        with self._lock:
            self.conn.commit()

    def search_materials(
        self, query: str, limit: int = 4, course_id: int | None = None
    ) -> list[dict]:
        terms = _query_terms(query)
        if not terms:
            return []
        if course_id is None:
            rows = self._exec(
                "SELECT c.page, c.text, m.name FROM material_chunks c "
                "JOIN materials m ON m.id=c.material_id"
            ).fetchall()
        else:
            rows = self._exec(
                "SELECT c.page, c.text, m.name FROM material_chunks c "
                "JOIN materials m ON m.id=c.material_id WHERE m.course_id=?",
                (course_id,),
            ).fetchall()
        scored: list[tuple[int, dict]] = []
        for row in rows:
            text = row["text"] or ""
            score = sum(1 for term in terms if term in text)
            if score:
                scored.append((score, dict(row)))
        scored.sort(key=lambda item: -item[0])
        return [item[1] for item in scored[:limit]]

    # ---------- 术语表 ----------
    def list_glossary(self, course_id: int) -> list[dict]:
        rows = self._exec(
            "SELECT * FROM glossary WHERE course_id=? ORDER BY term",
            (course_id,),
        ).fetchall()
        return [dict(r) for r in rows]

    def add_glossary(self, course_id: int, items: list[dict]) -> int:
        added = 0
        for item in items or []:
            if not isinstance(item, dict):
                continue
            term = str(item.get("term") or "").strip()[:60]
            if not term:
                continue
            try:
                self._exec(
                    """INSERT INTO glossary(course_id, term, translation, note, source, created_at)
                       VALUES(?,?,?,?,?,?)
                       ON CONFLICT(course_id, term) DO UPDATE SET
                         translation=excluded.translation,
                         note=excluded.note,
                         source=excluded.source""",
                    (
                        course_id,
                        term,
                        str(item.get("translation") or "").strip()[:120],
                        str(item.get("note") or "").strip()[:240],
                        str(item.get("source") or "").strip()[:40],
                        _now(),
                    ),
                )
                added += 1
            except Exception:
                continue
        with self._lock:
            self.conn.commit()
        return added

    def delete_glossary(self, item_id: int) -> None:
        self._exec("DELETE FROM glossary WHERE id=?", (item_id,))
        with self._lock:
            self.conn.commit()

    def glossary_prompt(self, course_id: int, limit: int = 30) -> str:
        rows = self._exec(
            "SELECT term, translation FROM glossary WHERE course_id=? "
            "ORDER BY id DESC LIMIT ?",
            (course_id, limit),
        ).fetchall()
        lines = []
        for row in rows:
            term = row["term"] or ""
            trans = row["translation"] or ""
            if term and trans and term != trans:
                lines.append(f"{term} = {trans}")
            elif term:
                lines.append(term)
        return "; ".join(lines)

    # ---------- 书签 ----------
    def add_bookmark(self, session_id: int, segment_id: int | None, note: str = "") -> int:
        cur = self._exec(
            "INSERT INTO bookmarks(session_id, segment_id, note, created_at) VALUES(?,?,?,?)",
            (session_id, segment_id, (note or "").strip()[:200], _now()),
        )
        with self._lock:
            self.conn.commit()
        return int(cur.lastrowid)

    def list_bookmarks(self, session_id: int) -> list[dict]:
        rows = self._exec(
            """SELECT b.*, s.t0, s.src_text, s.tgt_text, s.src_lang
               FROM bookmarks b LEFT JOIN segments s ON s.id=b.segment_id
               WHERE b.session_id=? ORDER BY b.id""",
            (session_id,),
        ).fetchall()
        return [dict(r) for r in rows]

    def delete_bookmark(self, bookmark_id: int) -> None:
        self._exec("DELETE FROM bookmarks WHERE id=?", (bookmark_id,))
        with self._lock:
            self.conn.commit()

    # ---------- 课程范围检索 ----------
    def search_course_segments(
        self, course_id: int, query: str, limit: int = 4
    ) -> list[dict]:
        terms = _query_terms(query)
        if not terms:
            return []
        rows = self._exec(
            """SELECT s.id, s.src_text, s.tgt_text, s.t0, s.session_id,
                      se.title, se.started_at
               FROM segments s JOIN sessions se ON se.id=s.session_id
               WHERE se.course_id=? ORDER BY s.id DESC LIMIT 20000""",
            (course_id,),
        ).fetchall()
        scored: list[tuple[int, dict]] = []
        for row in rows:
            text = ((row["src_text"] or "") + " " + (row["tgt_text"] or "")).lower()
            score = sum(1 for term in terms if term in text)
            if score:
                scored.append((score, dict(row)))
        scored.sort(key=lambda item: -item[0])
        return [item[1] for item in scored[:limit]]

    def course_material_text(self, course_id: int, limit_chars: int = 7000) -> str:
        rows = self._exec(
            """SELECT c.page, c.text, m.name FROM material_chunks c
               JOIN materials m ON m.id=c.material_id
               WHERE m.course_id=? ORDER BY m.id, c.page""",
            (course_id,),
        ).fetchall()
        parts: list[str] = []
        used = 0
        for row in rows:
            piece = f"[{row['name']} 第{row['page']}页] {(row['text'] or '')[:600]}"
            if used + len(piece) > limit_chars:
                break
            parts.append(piece)
            used += len(piece)
        return "\n".join(parts)

    def course_transcript(self, course_id: int, limit: int = 160) -> str:
        rows = self._exec(
            """SELECT s.src_text, se.title, se.started_at
               FROM segments s JOIN sessions se ON se.id=s.session_id
               WHERE se.course_id=? ORDER BY s.id DESC LIMIT ?""",
            (course_id, limit),
        ).fetchall()
        lines = []
        for row in reversed(rows):
            if row["src_text"]:
                lines.append(f"- {row['src_text']}")
        return "\n".join(lines)

    def list_questions(self, session_id: int) -> list[dict]:
        rows = self._exec(
            "SELECT * FROM questions WHERE session_id=? ORDER BY id",
            (session_id,),
        ).fetchall()
        return [dict(r) for r in rows]

    def session_exists(self, session_id: int) -> bool:
        row = self._exec(
            "SELECT 1 FROM sessions WHERE id=?", (session_id,)
        ).fetchone()
        return row is not None

    def get_quiz(self, session_id: int) -> str:
        row = self._exec(
            "SELECT quiz FROM sessions WHERE id=?", (session_id,)
        ).fetchone()
        return (row["quiz"] or "") if row else ""

    def set_quiz(self, session_id: int, quiz: str) -> None:
        self._exec("UPDATE sessions SET quiz=? WHERE id=?", (quiz or "", session_id))
        with self._lock:
            self.conn.commit()

    # ---------- 数据保留 ----------
    def purge_older_than(self, days: int) -> list[int]:
        if days <= 0:
            return []
        cutoff = time.time() - days * 86400
        rows = self._exec(
            "SELECT id, started_at FROM sessions WHERE started_at IS NOT NULL"
        ).fetchall()
        removed: list[int] = []
        for row in rows:
            try:
                stamp = datetime.fromisoformat(row["started_at"]).timestamp()
            except Exception:
                continue
            if stamp < cutoff:
                removed.append(int(row["id"]))
        for session_id in removed:
            self.delete_session(session_id)
        return removed

    # ---------- 说话人 ----------
    def ensure_speaker(self, session_id: int, label: str, color: str) -> int:
        row = self._exec(
            "SELECT id FROM speakers WHERE session_id=? AND label=?",
            (session_id, label),
        ).fetchone()
        if row:
            return int(row["id"])
        cur = self._exec(
            "INSERT INTO speakers(session_id, label, color) VALUES(?,?,?)",
            (session_id, label, color),
        )
        with self._lock:
            self.conn.commit()
        return int(cur.lastrowid)

    def assign_speaker(self, segment_id: int, speaker_id: int) -> None:
        self._exec("UPDATE segments SET speaker_id=? WHERE id=?", (speaker_id, segment_id))
        with self._lock:
            self.conn.commit()

    def rename_speaker(self, session_id: int, speaker_id: int, label: str) -> None:
        self._exec(
            "UPDATE speakers SET label=? WHERE id=? AND session_id=?",
            (label, speaker_id, session_id),
        )
        with self._lock:
            self.conn.commit()
