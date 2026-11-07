package com.localinterpreter.tablet.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.max
import kotlin.math.min

class Database(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    private val appContext = context.applicationContext

    fun recreate() {
        runCatching { close() }
        val dbFile = appContext.getDatabasePath("history.db")
        runCatching {
            if (dbFile.exists()) {
                // 保留每一次损坏现场，绝不覆盖旧备份；改名失败时先复制再删除，尽量不丢原始数据。
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                    .format(java.util.Date())
                val backup = java.io.File(dbFile.parentFile, "history.db.corrupt-$stamp")
                if (!dbFile.renameTo(backup)) {
                    // 同目录 rename 一般不会失败；如果复制也失败（磁盘满），绝不能删掉唯一的原库。
                    val copied = runCatching { dbFile.copyTo(backup, overwrite = true); true }.getOrDefault(false)
                    if (!copied) {
                        android.util.Log.e("Database", "无法备份损坏数据库，保留原文件：${dbFile.absolutePath}")
                        return
                    }
                    dbFile.delete()
                }
            }
            java.io.File(dbFile.path + "-wal").delete()
            java.io.File(dbFile.path + "-shm").delete()
            java.io.File(dbFile.path + "-journal").delete()
        }
        runCatching { writableDatabase }
    }

    override fun onCreate(db: SQLiteDatabase) = ensureSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = ensureSchema(db)

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        runCatching { ensureSchema(db) }
    }

    /**
     * 建表和补列都是幂等的：老版本数据库（或字段不全的数据库）升级后直接可用，
     * 不会因为缺表/缺列在第一次查询时崩溃。
     */
    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS courses(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              kind TEXT DEFAULT '',
              notes TEXT DEFAULT '',
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              course_id INTEGER,
              started_at INTEGER NOT NULL,
              ended_at INTEGER,
              duration_s REAL DEFAULT 0,
              audio_source TEXT DEFAULT 'mic',
              source_lang TEXT DEFAULT 'auto',
              target_lang TEXT DEFAULT 'auto',
              title TEXT DEFAULT '',
              title_source TEXT DEFAULT '',
              summary TEXT DEFAULT '',
              status TEXT DEFAULT 'running'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS segments(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id INTEGER NOT NULL,
              speaker_id INTEGER,
              t0 REAL DEFAULT 0,
              t1 REAL DEFAULT 0,
              src_lang TEXT DEFAULT '',
              src_text TEXT DEFAULT '',
              tgt_text TEXT DEFAULT '',
              audio_path TEXT,
              low_conf INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS speakers(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id INTEGER NOT NULL,
              label TEXT NOT NULL,
              color TEXT NOT NULL,
              embedding BLOB
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS questions(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id INTEGER NOT NULL,
              segment_id INTEGER,
              question TEXT NOT NULL,
              question_tgt TEXT DEFAULT '',
              answer TEXT DEFAULT '',
              answer_alt TEXT DEFAULT '',
              source TEXT DEFAULT '',
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS summaries(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id INTEGER NOT NULL,
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS materials(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              course_id INTEGER,
              name TEXT NOT NULL,
              path TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS material_chunks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              material_id INTEGER NOT NULL,
              course_id INTEGER,
              page INTEGER DEFAULT 0,
              text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS glossary(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              course_id INTEGER,
              term TEXT NOT NULL,
              translation TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id INTEGER NOT NULL,
              segment_id INTEGER NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS quizzes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id INTEGER NOT NULL,
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_course ON sessions(course_id, started_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_segments_session ON segments(session_id, t0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_course ON material_chunks(course_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_session ON questions(session_id, created_at)")
        ensureColumns(db)
    }

    /** 旧数据库可能缺少后续版本新增的列，逐列检查后补齐。 */
    private fun ensureColumns(db: SQLiteDatabase) {
        for ((table, columns) in EXPECTED_COLUMNS) {
            val existing = mutableSetOf<String>()
            runCatching {
                db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    val nameIdx = c.getColumnIndexOrThrow("name")
                    while (c.moveToNext()) existing += c.getString(nameIdx)
                }
            }
            for ((name, def) in columns) {
                if (name !in existing) {
                    runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $name $def") }
                }
            }
        }
    }

    // ---------------------------------------------------------------- courses

    fun createCourse(name: String, kind: String = ""): Course {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("courses", null, ContentValues().apply {
            put("name", name.trim())
            put("kind", kind)
            put("notes", "")
            put("created_at", now)
            put("updated_at", now)
        })
        if (id < 0) throw android.database.SQLException("courses insert failed")
        return Course(id, name.trim(), kind, "", now, now)
    }

    fun updateCourse(id: Long, name: String? = null, kind: String? = null, notes: String? = null) {
        val cv = ContentValues()
        if (name != null) cv.put("name", name.trim())
        if (kind != null) cv.put("kind", kind)
        if (notes != null) cv.put("notes", notes)
        cv.put("updated_at", System.currentTimeMillis())
        writableDatabase.update("courses", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteCourse(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("sessions", ContentValues().apply { putNull("course_id") }, "course_id=?", arrayOf(id.toString()))
            db.update("materials", ContentValues().apply { putNull("course_id") }, "course_id=?", arrayOf(id.toString()))
            db.update("material_chunks", ContentValues().apply { putNull("course_id") }, "course_id=?", arrayOf(id.toString()))
            db.delete("glossary", "course_id=?", arrayOf(id.toString()))
            db.delete("courses", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listCourses(query: String = ""): List<Course> {
        val where = if (query.isBlank()) null else "name LIKE ? OR notes LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%${query.trim()}%", "%${query.trim()}%")
        return query("SELECT * FROM courses" + (where?.let { " WHERE $it" } ?: "") + " ORDER BY updated_at DESC", args) { c ->
            Course(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("kind")) ?: "",
                c.getString(c.getColumnIndexOrThrow("notes")) ?: "",
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("updated_at")),
            )
        }
    }

    fun getCourse(id: Long): Course? = query(
        "SELECT * FROM courses WHERE id=?", arrayOf(id.toString())
    ) { c ->
        Course(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("name")),
            c.getString(c.getColumnIndexOrThrow("kind")) ?: "",
            c.getString(c.getColumnIndexOrThrow("notes")) ?: "",
            c.getLong(c.getColumnIndexOrThrow("created_at")),
            c.getLong(c.getColumnIndexOrThrow("updated_at")),
        )
    }.firstOrNull()

    fun courseSessionCount(courseId: Long): Int = query(
        "SELECT COUNT(*) FROM sessions WHERE course_id=?", arrayOf(courseId.toString())
    ) { it.getInt(0) }.firstOrNull() ?: 0

    // --------------------------------------------------------------- sessions

    fun createSession(courseId: Long?, source: String, sourceLang: String, targetLang: String): Long {
        val id = writableDatabase.insert("sessions", null, ContentValues().apply {
            if (courseId != null) put("course_id", courseId) else putNull("course_id")
            put("started_at", System.currentTimeMillis())
            put("audio_source", source)
            put("source_lang", sourceLang)
            put("target_lang", targetLang)
            put("status", "running")
        })
        if (id < 0) throw android.database.SQLException("sessions insert failed")
        return id
    }

    fun endSession(id: Long, title: String, summary: String, status: String = "finished") {
        val started = query("SELECT started_at FROM sessions WHERE id=?", arrayOf(id.toString())) {
            it.getLong(0)
        }.firstOrNull() ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()
        writableDatabase.update("sessions", ContentValues().apply {
            put("ended_at", now)
            put("duration_s", (now - started) / 1000.0)
            if (summary.isNotBlank()) put("summary", summary.trim())
            put("status", status)
        }, "id=?", arrayOf(id.toString()))
        // 自动标题只填空标题，条件更新避免与用户同时改标题时互相覆盖。
        if (title.isNotBlank()) {
            writableDatabase.update(
                "sessions",
                ContentValues().apply {
                    put("title", title.trim())
                    put("title_source", "auto")
                },
                "id=? AND (title IS NULL OR title='')",
                arrayOf(id.toString()),
            )
        }
    }

    fun updateSessionTitle(id: Long, title: String, source: String = "user") {
        writableDatabase.update("sessions", ContentValues().apply {
            put("title", title.trim())
            put("title_source", source)
        }, "id=?", arrayOf(id.toString()))
    }

    /** AI/自动标题不覆盖用户手动编辑过的标题。 */
    fun updateSessionTitleIfNotUser(id: Long, title: String): Boolean {
        if (title.isBlank()) return false
        return writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("title", title.trim())
                put("title_source", "auto")
            },
            "id=? AND (title_source IS NULL OR title_source != 'user')",
            arrayOf(id.toString()),
        ) > 0
    }

    fun updateSessionSummary(id: Long, summary: String) {
        writableDatabase.update("sessions", ContentValues().apply { put("summary", summary.trim()) }, "id=?", arrayOf(id.toString()))
    }

    fun setSessionCourse(id: Long, courseId: Long?) {
        writableDatabase.update("sessions", ContentValues().apply {
            if (courseId != null) put("course_id", courseId) else putNull("course_id")
        }, "id=?", arrayOf(id.toString()))
    }

    fun listSessions(courseId: Long? = null, query: String = "", limit: Int = 500): List<Session> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (courseId != null) {
            clauses += "course_id=?"
            args += courseId.toString()
        }
        if (query.isNotBlank()) {
            clauses += "(title LIKE ? OR summary LIKE ?)"
            args += "%${query.trim()}%"
            args += "%${query.trim()}%"
        }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        return query("SELECT * FROM sessions$where ORDER BY started_at DESC LIMIT $limit", args.toTypedArray()) { readSession(it) }
    }

    fun listUnassignedSessions(query: String = "", limit: Int = 500): List<Session> {
        val like = "%${query.trim()}%"
        val where = if (query.isBlank()) "course_id IS NULL"
        else "course_id IS NULL AND (title LIKE ? OR summary LIKE ?)"
        val args = if (query.isBlank()) null else arrayOf(like, like)
        return query("SELECT * FROM sessions WHERE $where ORDER BY started_at DESC LIMIT $limit", args) { readSession(it) }
    }

    fun unassignedSessionCount(): Int = query(
        "SELECT COUNT(*) FROM sessions WHERE course_id IS NULL", null
    ) { it.getInt(0) }.firstOrNull() ?: 0

    fun finishStaleSessions(): List<Long> {
        val stale = query("SELECT id, started_at FROM sessions WHERE status='running'", null) {
            it.getLong(0) to it.getLong(1)
        }
        for ((id, started) in stale) {
            val last = query("SELECT MAX(t1) FROM segments WHERE session_id=?", arrayOf(id.toString())) {
                if (it.isNull(0)) 0.0 else it.getDouble(0)
            }.firstOrNull() ?: 0.0
            writableDatabase.update("sessions", ContentValues().apply {
                put("status", "interrupted")
                put("duration_s", last)
                put("ended_at", started + (last * 1000).toLong())
            }, "id=?", arrayOf(id.toString()))
        }
        return stale.map { it.first }
    }

    fun getSession(id: Long): Session? = query("SELECT * FROM sessions WHERE id=?", arrayOf(id.toString())) {
        readSession(it)
    }.firstOrNull()

    private fun readSession(c: Cursor) = Session(
        c.getLong(c.getColumnIndexOrThrow("id")),
        c.getColumnIndexOrThrow("course_id").let { if (c.isNull(it)) null else c.getLong(it) },
        c.getLong(c.getColumnIndexOrThrow("started_at")),
        c.getColumnIndexOrThrow("ended_at").let { if (c.isNull(it)) null else c.getLong(it) },
        c.getDouble(c.getColumnIndexOrThrow("duration_s")),
        c.getString(c.getColumnIndexOrThrow("audio_source")) ?: "mic",
        c.getString(c.getColumnIndexOrThrow("source_lang")) ?: "auto",
        c.getString(c.getColumnIndexOrThrow("target_lang")) ?: "auto",
        c.getString(c.getColumnIndexOrThrow("title")) ?: "",
        c.getString(c.getColumnIndexOrThrow("summary")) ?: "",
        c.getString(c.getColumnIndexOrThrow("status")) ?: "finished",
    )

    fun deleteSession(id: Long): List<String> {
        val audioPaths = query("SELECT audio_path FROM segments WHERE session_id=? AND audio_path IS NOT NULL", arrayOf(id.toString())) {
            it.getString(0)
        }.filterNotNull()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("segments", "session_id=?", arrayOf(id.toString()))
            db.delete("speakers", "session_id=?", arrayOf(id.toString()))
            db.delete("questions", "session_id=?", arrayOf(id.toString()))
            db.delete("summaries", "session_id=?", arrayOf(id.toString()))
            db.delete("bookmarks", "session_id=?", arrayOf(id.toString()))
            db.delete("quizzes", "session_id=?", arrayOf(id.toString()))
            db.delete("sessions", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return audioPaths
    }

    // --------------------------------------------------------------- segments

    fun addSegment(
        sessionId: Long,
        t0: Double,
        t1: Double,
        srcLang: String,
        srcText: String,
        audioPath: String?,
        lowConfidence: Boolean,
    ): Long {
        val id = writableDatabase.insert("segments", null, ContentValues().apply {
            put("session_id", sessionId)
            put("t0", t0)
            put("t1", t1)
            put("src_lang", srcLang)
            put("src_text", srcText)
            put("tgt_text", "")
            if (audioPath != null) put("audio_path", audioPath) else putNull("audio_path")
            put("low_conf", if (lowConfidence) 1 else 0)
        })
        if (id < 0) throw android.database.SQLException("segments insert failed")
        return id
    }

    fun updateSegmentTranslation(id: Long, text: String) {
        writableDatabase.update("segments", ContentValues().apply { put("tgt_text", text) }, "id=?", arrayOf(id.toString()))
    }

    fun updateSegmentSource(id: Long, text: String) {
        writableDatabase.update("segments", ContentValues().apply { put("src_text", text) }, "id=?", arrayOf(id.toString()))
    }

    fun setSegmentSpeaker(id: Long, speakerId: Long?) {
        writableDatabase.update("segments", ContentValues().apply {
            if (speakerId != null) put("speaker_id", speakerId) else putNull("speaker_id")
        }, "id=?", arrayOf(id.toString()))
    }

    fun listSegments(sessionId: Long): List<Segment> = query(
        "SELECT * FROM segments WHERE session_id=? ORDER BY t0", arrayOf(sessionId.toString())
    ) { c ->
        Segment(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getLong(c.getColumnIndexOrThrow("session_id")),
            c.getColumnIndexOrThrow("speaker_id").let { if (c.isNull(it)) null else c.getLong(it) },
            c.getDouble(c.getColumnIndexOrThrow("t0")),
            c.getDouble(c.getColumnIndexOrThrow("t1")),
            c.getString(c.getColumnIndexOrThrow("src_lang")) ?: "",
            c.getString(c.getColumnIndexOrThrow("src_text")) ?: "",
            c.getString(c.getColumnIndexOrThrow("tgt_text")) ?: "",
            c.getString(c.getColumnIndexOrThrow("audio_path")),
            c.getInt(c.getColumnIndexOrThrow("low_conf")) != 0,
        )
    }

    fun getSegment(id: Long): Segment? = query("SELECT * FROM segments WHERE id=?", arrayOf(id.toString())) { c ->
        Segment(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getLong(c.getColumnIndexOrThrow("session_id")),
            c.getColumnIndexOrThrow("speaker_id").let { if (c.isNull(it)) null else c.getLong(it) },
            c.getDouble(c.getColumnIndexOrThrow("t0")),
            c.getDouble(c.getColumnIndexOrThrow("t1")),
            c.getString(c.getColumnIndexOrThrow("src_lang")) ?: "",
            c.getString(c.getColumnIndexOrThrow("src_text")) ?: "",
            c.getString(c.getColumnIndexOrThrow("tgt_text")) ?: "",
            c.getString(c.getColumnIndexOrThrow("audio_path")),
            c.getInt(c.getColumnIndexOrThrow("low_conf")) != 0,
        )
    }.firstOrNull()

    fun searchSegments(courseId: Long?, query: String, limit: Int = 40): List<SegmentHit> {
        if (query.isBlank()) return emptyList()
        val like = "%${query.trim()}%"
        val courseClause = if (courseId != null) " AND s.course_id=?" else ""
        val args = mutableListOf(like, like)
        if (courseId != null) args += courseId.toString()
        return this.query(
            """
            SELECT g.*, s.title AS session_title, s.started_at AS session_started
            FROM segments g JOIN sessions s ON g.session_id = s.id
            WHERE (g.src_text LIKE ? OR g.tgt_text LIKE ?)$courseClause
            ORDER BY s.started_at DESC, g.t0 ASC LIMIT $limit
            """.trimIndent(),
            args.toTypedArray(),
        ) { c ->
            SegmentHit(
                Segment(
                    c.getLong(c.getColumnIndexOrThrow("id")),
                    c.getLong(c.getColumnIndexOrThrow("session_id")),
                    c.getColumnIndexOrThrow("speaker_id").let { if (c.isNull(it)) null else c.getLong(it) },
                    c.getDouble(c.getColumnIndexOrThrow("t0")),
                    c.getDouble(c.getColumnIndexOrThrow("t1")),
                    c.getString(c.getColumnIndexOrThrow("src_lang")) ?: "",
                    c.getString(c.getColumnIndexOrThrow("src_text")) ?: "",
                    c.getString(c.getColumnIndexOrThrow("tgt_text")) ?: "",
                    c.getString(c.getColumnIndexOrThrow("audio_path")),
                    c.getInt(c.getColumnIndexOrThrow("low_conf")) != 0,
                ),
                c.getString(c.getColumnIndexOrThrow("session_title")) ?: "",
                c.getLong(c.getColumnIndexOrThrow("session_started")),
            )
        }
    }

    // --------------------------------------------------------------- speakers

    fun addSpeaker(sessionId: Long, label: String, color: String, embedding: FloatArray?): Long {
        val id = writableDatabase.insert("speakers", null, ContentValues().apply {
            put("session_id", sessionId)
            put("label", label)
            put("color", color)
            if (embedding != null) put("embedding", floatsToBytes(embedding))
        })
        if (id < 0) throw android.database.SQLException("speakers insert failed")
        return id
    }

    fun listSpeakers(sessionId: Long): List<Speaker> = query(
        "SELECT id, session_id, label, color FROM speakers WHERE session_id=? ORDER BY id",
        arrayOf(sessionId.toString())
    ) { c ->
        Speaker(
            c.getLong(0),
            c.getLong(1),
            c.getString(2) ?: "",
            c.getString(3) ?: "#4fd1a5",
        )
    }

    fun speakerEmbeddings(sessionId: Long): List<Pair<Long, FloatArray>> = query(
        "SELECT id, embedding FROM speakers WHERE session_id=?", arrayOf(sessionId.toString())
    ) { c ->
        val b = c.getBlob(1)
        Pair(c.getLong(0), if (b == null) FloatArray(0) else bytesToFloats(b))
    }

    fun renameSpeaker(id: Long, label: String) {
        writableDatabase.update("speakers", ContentValues().apply { put("label", label) }, "id=?", arrayOf(id.toString()))
    }

    // -------------------------------------------------------------- questions

    fun addQuestion(
        sessionId: Long,
        segmentId: Long?,
        question: String,
        questionTgt: String,
        answer: String,
        answerAlt: String,
        source: String,
    ): Long {
        val id = writableDatabase.insert("questions", null, ContentValues().apply {
            put("session_id", sessionId)
            if (segmentId != null) put("segment_id", segmentId) else putNull("segment_id")
            put("question", question)
            put("question_tgt", questionTgt)
            put("answer", answer)
            put("answer_alt", answerAlt)
            put("source", source)
            put("created_at", System.currentTimeMillis())
        })
        if (id < 0) throw android.database.SQLException("questions insert failed")
        return id
    }

    fun listQuestions(sessionId: Long): List<Question> = query(
        "SELECT * FROM questions WHERE session_id=? ORDER BY created_at DESC", arrayOf(sessionId.toString())
    ) { c ->
        Question(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getLong(c.getColumnIndexOrThrow("session_id")),
            c.getColumnIndexOrThrow("segment_id").let { if (c.isNull(it)) null else c.getLong(it) },
            c.getString(c.getColumnIndexOrThrow("question")) ?: "",
            c.getString(c.getColumnIndexOrThrow("question_tgt")) ?: "",
            c.getString(c.getColumnIndexOrThrow("answer")) ?: "",
            c.getString(c.getColumnIndexOrThrow("answer_alt")) ?: "",
            c.getString(c.getColumnIndexOrThrow("source")) ?: "",
            c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }

    fun updateQuestionAlt(id: Long, answerAlt: String) {
        writableDatabase.update("questions", ContentValues().apply { put("answer_alt", answerAlt) }, "id=?", arrayOf(id.toString()))
    }

    fun updateQuestionAnswer(id: Long, answer: String, answerAlt: String, source: String) {
        writableDatabase.update("questions", ContentValues().apply {
            put("answer", answer)
            put("answer_alt", answerAlt)
            put("source", source)
        }, "id=?", arrayOf(id.toString()))
    }

    // --------------------------------------------------------------- summaries

    fun addSummary(sessionId: Long, content: String): SummaryItem {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("summaries", null, ContentValues().apply {
            put("session_id", sessionId)
            put("content", content)
            put("created_at", now)
        })
        if (id < 0) throw android.database.SQLException("summaries insert failed")
        return SummaryItem(id, sessionId, content, now)
    }

    fun latestSummary(sessionId: Long): SummaryItem? = query(
        "SELECT * FROM summaries WHERE session_id=? ORDER BY created_at DESC LIMIT 1",
        arrayOf(sessionId.toString())
    ) { c ->
        SummaryItem(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getLong(c.getColumnIndexOrThrow("session_id")),
            c.getString(c.getColumnIndexOrThrow("content")) ?: "",
            c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }.firstOrNull()

    // --------------------------------------------------------------- materials

    fun addMaterial(courseId: Long?, name: String, path: String, chunks: List<Pair<Int, String>>): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var id = 0L
        db.beginTransaction()
        try {
            id = db.insert("materials", null, ContentValues().apply {
                if (courseId != null) put("course_id", courseId) else putNull("course_id")
                put("name", name)
                put("path", path)
                put("created_at", now)
            })
            if (id < 0) throw android.database.SQLException("materials insert failed")
            chunks.forEach { (page, text) ->
                val chunkId = db.insert("material_chunks", null, ContentValues().apply {
                    put("material_id", id)
                    if (courseId != null) put("course_id", courseId) else putNull("course_id")
                    put("page", page)
                    put("text", text)
                })
                if (chunkId < 0) throw android.database.SQLException("material_chunks insert failed")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    fun listMaterials(courseId: Long): List<Material> = query(
        "SELECT * FROM materials WHERE course_id=? ORDER BY created_at DESC", arrayOf(courseId.toString())
    ) { c ->
        Material(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getColumnIndexOrThrow("course_id").let { if (c.isNull(it)) null else c.getLong(it) },
            c.getString(c.getColumnIndexOrThrow("name")) ?: "",
            c.getString(c.getColumnIndexOrThrow("path")) ?: "",
            c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }

    fun deleteMaterial(id: Long): String? {
        val path = query("SELECT path FROM materials WHERE id=?", arrayOf(id.toString())) { it.getString(0) }.firstOrNull()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("material_chunks", "material_id=?", arrayOf(id.toString()))
            db.delete("materials", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return path
    }

    fun materialChunkCount(materialId: Long): Int = query(
        "SELECT COUNT(*) FROM material_chunks WHERE material_id=?", arrayOf(materialId.toString())
    ) { it.getInt(0) }.firstOrNull() ?: 0

    fun allMaterialChunks(courseId: Long, limit: Int = 80): List<CourseHit> {
        // 新资料优先，并在多份资料之间平均取样；只取最早文件开头会让新导入的讲义完全不参与助教。
        val materials = query(
            "SELECT id, name FROM materials WHERE course_id=? ORDER BY created_at DESC LIMIT 12",
            arrayOf(courseId.toString()),
        ) { c -> c.getLong(0) to (c.getString(1) ?: "") }
        if (materials.isEmpty()) return emptyList()
        val per = (limit / materials.size).coerceAtLeast(4)
        val out = mutableListOf<CourseHit>()
        for ((id, name) in materials) {
            out += query(
                "SELECT page, text FROM material_chunks WHERE material_id=? ORDER BY page, id LIMIT $per",
                arrayOf(id.toString()),
            ) { c -> CourseHit(name, c.getInt(0), c.getString(1) ?: "", 0.0) }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    fun courseTranscript(courseId: Long, limit: Int = 240): List<Pair<Long, String>> {
        return query(
            """
            SELECT s.started_at AS started_at, g.src_text AS src
            FROM segments g JOIN sessions s ON g.session_id = s.id
            WHERE s.course_id=?
            ORDER BY s.started_at DESC, g.t0 DESC
            LIMIT $limit
            """.trimIndent(),
            arrayOf(courseId.toString()),
        ) { c -> c.getLong(0) to (c.getString(1) ?: "") }
            .reversed()
    }

    fun topMaterialChunks(courseId: Long?, query: String, limit: Int = 6): List<CourseHit> {
        if (query.isBlank()) return emptyList()
        val terms = keywords(query).take(8)
        if (terms.isEmpty()) return emptyList()
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (courseId != null) {
            clauses += "c.course_id=?"
            args += courseId.toString()
        }
        clauses += "(" + terms.joinToString(" OR ") { "c.text LIKE ?" } + ")"
        args += terms.map { "%${it.replace("%", "").replace("_", "")}%" }
        val rows = this.query(
            """
            SELECT m.name AS name, c.page AS page, c.text AS text
            FROM material_chunks c JOIN materials m ON c.material_id = m.id
            WHERE ${clauses.joinToString(" AND ")}
            LIMIT 400
            """.trimIndent(),
            args.toTypedArray(),
        ) { c ->
            Triple(c.getString(0) ?: "", c.getInt(1), c.getString(2) ?: "")
        }
        val scored = rows.map { (name, page, text) ->
            var score = 0.0
            val lower = text.lowercase()
            for (t in terms) {
                var idx = lower.indexOf(t)
                var count = 0
                while (idx >= 0 && count < 8) {
                    count++
                    idx = lower.indexOf(t, idx + t.length)
                }
                score += count * t.length.coerceAtMost(4)
            }
            CourseHit(name, page, text, score)
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(limit)
        return scored
    }

    // --------------------------------------------------------------- glossary

    fun addGlossary(courseId: Long?, term: String, translation: String): Long {
        val id = writableDatabase.insert("glossary", null, ContentValues().apply {
            if (courseId != null) put("course_id", courseId) else putNull("course_id")
            put("term", term.trim())
            put("translation", translation.trim())
        })
        if (id < 0) throw android.database.SQLException("glossary insert failed")
        return id
    }

    fun listGlossary(courseId: Long): List<GlossaryItem> = query(
        "SELECT * FROM glossary WHERE course_id=? ORDER BY id DESC", arrayOf(courseId.toString())
    ) { c ->
        GlossaryItem(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getColumnIndexOrThrow("course_id").let { if (c.isNull(it)) null else c.getLong(it) },
            c.getString(c.getColumnIndexOrThrow("term")) ?: "",
            c.getString(c.getColumnIndexOrThrow("translation")) ?: "",
        )
    }

    fun deleteGlossary(id: Long) {
        writableDatabase.delete("glossary", "id=?", arrayOf(id.toString()))
    }

    fun glossaryPrompt(courseId: Long?, limit: Int = 30): String {
        if (courseId == null) return ""
        return listGlossary(courseId).take(limit).joinToString("；") { "${it.term}→${it.translation}" }
    }

    // -------------------------------------------------------------- bookmarks

    fun addBookmark(sessionId: Long, segmentId: Long) {
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("session_id", sessionId)
            put("segment_id", segmentId)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun removeBookmark(sessionId: Long, segmentId: Long) {
        writableDatabase.delete("bookmarks", "session_id=? AND segment_id=?", arrayOf(sessionId.toString(), segmentId.toString()))
    }

    fun listBookmarks(sessionId: Long): List<Bookmark> = query(
        "SELECT * FROM bookmarks WHERE session_id=? ORDER BY created_at", arrayOf(sessionId.toString())
    ) { c ->
        Bookmark(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getLong(c.getColumnIndexOrThrow("session_id")),
            c.getLong(c.getColumnIndexOrThrow("segment_id")),
            c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }

    // ------------------------------------------------------------------ quiz

    fun deleteQuiz(sessionId: Long) {
        writableDatabase.delete("quizzes", "session_id=?", arrayOf(sessionId.toString()))
    }

    fun saveQuiz(sessionId: Long, content: String): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("quizzes", "session_id=?", arrayOf(sessionId.toString()))
            val id = db.insert("quizzes", null, ContentValues().apply {
                put("session_id", sessionId)
                put("content", content)
                put("created_at", System.currentTimeMillis())
            })
            if (id < 0) throw android.database.SQLException("quizzes insert failed")
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun getQuiz(sessionId: Long): Quiz? = query(
        "SELECT * FROM quizzes WHERE session_id=? ORDER BY created_at DESC LIMIT 1", arrayOf(sessionId.toString())
    ) { c ->
        Quiz(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getLong(c.getColumnIndexOrThrow("session_id")),
            c.getString(c.getColumnIndexOrThrow("content")) ?: "",
            c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }.firstOrNull()

    // --------------------------------------------------------------- helpers

    fun courseContext(courseId: Long): String {
        val course = getCourse(courseId) ?: return ""
        val notes = course.notes.trim()
        val summaries = listSessions(courseId, "", 5)
            .filter { it.summary.isNotBlank() }
            .joinToString("\n") { "- ${it.title}（${formatDate(it.startedAt)}）：${it.summary}" }
        val glossary = listGlossary(courseId).take(20)
            .joinToString("；") { "${it.term}→${it.translation}" }
        return buildString {
            if (notes.isNotEmpty()) appendLine("课程笔记：$notes")
            if (summaries.isNotEmpty()) appendLine("往次课次摘要：\n$summaries")
            if (glossary.isNotEmpty()) appendLine("术语表：$glossary")
        }.trim()
    }

    fun formatDate(ms: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return "%d-%02d-%02d %02d:%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    private fun keywords(query: String): List<String> {
        val text = query.lowercase()
        val out = mutableListOf<String>()
        val words = Regex("[a-z0-9_]{2,}").findAll(text).map { it.value }.toList()
        out += words
        val cjk = text.filter { it in '一'..'鿿' }
        if (cjk.length >= 2) {
            for (i in 0 until cjk.length - 1) out += cjk.substring(i, i + 2)
        }
        return out.distinct().sortedByDescending { it.length }.take(24)
    }

    private fun floatsToBytes(arr: FloatArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(arr.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }

    private fun <T> query(sql: String, args: Array<String>?, mapper: (Cursor) -> T): List<T> {
        val out = mutableListOf<T>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += mapper(c)
        }
        return out
    }

    companion object {
        private val EXPECTED_COLUMNS: Map<String, Map<String, String>> = mapOf(
            "courses" to mapOf(
                "name" to "TEXT NOT NULL DEFAULT ''",
                "kind" to "TEXT DEFAULT ''",
                "notes" to "TEXT DEFAULT ''",
                "created_at" to "INTEGER DEFAULT 0",
                "updated_at" to "INTEGER DEFAULT 0",
            ),
            "sessions" to mapOf(
                "course_id" to "INTEGER",
                "started_at" to "INTEGER DEFAULT 0",
                "ended_at" to "INTEGER",
                "duration_s" to "REAL DEFAULT 0",
                "audio_source" to "TEXT DEFAULT 'mic'",
                "source_lang" to "TEXT DEFAULT 'auto'",
                "target_lang" to "TEXT DEFAULT 'auto'",
                "title" to "TEXT DEFAULT ''",
                "title_source" to "TEXT DEFAULT ''",
                "summary" to "TEXT DEFAULT ''",
                "status" to "TEXT DEFAULT 'finished'",
            ),
            "segments" to mapOf(
                "session_id" to "INTEGER NOT NULL DEFAULT 0",
                "speaker_id" to "INTEGER",
                "t0" to "REAL DEFAULT 0",
                "t1" to "REAL DEFAULT 0",
                "src_lang" to "TEXT DEFAULT ''",
                "src_text" to "TEXT DEFAULT ''",
                "tgt_text" to "TEXT DEFAULT ''",
                "audio_path" to "TEXT",
                "low_conf" to "INTEGER DEFAULT 0",
            ),
            "speakers" to mapOf(
                "session_id" to "INTEGER NOT NULL DEFAULT 0",
                "label" to "TEXT DEFAULT ''",
                "color" to "TEXT DEFAULT '#4fd1a5'",
                "embedding" to "BLOB",
            ),
            "questions" to mapOf(
                "session_id" to "INTEGER NOT NULL DEFAULT 0",
                "segment_id" to "INTEGER",
                "question" to "TEXT DEFAULT ''",
                "question_tgt" to "TEXT DEFAULT ''",
                "answer" to "TEXT DEFAULT ''",
                "answer_alt" to "TEXT DEFAULT ''",
                "source" to "TEXT DEFAULT ''",
                "created_at" to "INTEGER DEFAULT 0",
            ),
            "summaries" to mapOf(
                "session_id" to "INTEGER NOT NULL DEFAULT 0",
                "content" to "TEXT DEFAULT ''",
                "created_at" to "INTEGER DEFAULT 0",
            ),
            "materials" to mapOf(
                "course_id" to "INTEGER",
                "name" to "TEXT DEFAULT ''",
                "path" to "TEXT DEFAULT ''",
                "created_at" to "INTEGER DEFAULT 0",
            ),
            "material_chunks" to mapOf(
                "material_id" to "INTEGER NOT NULL DEFAULT 0",
                "course_id" to "INTEGER",
                "page" to "INTEGER DEFAULT 0",
                "text" to "TEXT DEFAULT ''",
            ),
            "glossary" to mapOf(
                "course_id" to "INTEGER",
                "term" to "TEXT DEFAULT ''",
                "translation" to "TEXT DEFAULT ''",
            ),
            "bookmarks" to mapOf(
                "session_id" to "INTEGER NOT NULL DEFAULT 0",
                "segment_id" to "INTEGER NOT NULL DEFAULT 0",
                "created_at" to "INTEGER DEFAULT 0",
            ),
            "quizzes" to mapOf(
                "session_id" to "INTEGER NOT NULL DEFAULT 0",
                "content" to "TEXT DEFAULT ''",
                "created_at" to "INTEGER DEFAULT 0",
            ),
        )

        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val n = min(a.size, b.size)
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in 0 until n) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na == 0.0 || nb == 0.0) return 0.0
            return dot / (Math.sqrt(na) * Math.sqrt(nb))
        }

        fun relevanceScore(query: String, text: String): Double {
            val q = query.lowercase()
            val t = text.lowercase()
            if (q.isBlank()) return 0.0
            var score = 0.0
            for (term in Regex("[a-z0-9_]{2,}").findAll(q)) {
                if (t.contains(term.value)) score += term.value.length
            }
            val cjk = q.filter { it in '一'..'鿿' }
            for (i in 0 until max(0, cjk.length - 1)) {
                val bigram = cjk.substring(i, i + 2)
                if (t.contains(bigram)) score += 2
            }
            return score
        }
    }
}
