package com.localinterpreter.tablet.sync

import android.content.Context
import com.localinterpreter.tablet.data.Database
import com.localinterpreter.tablet.data.Settings
import com.localinterpreter.tablet.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CloudSyncResult(
    val pushedItems: Int,
    val pulledItems: Int,
    val serverCursor: Long,
)

/**
 * 与 Google Cloud Run 同步服务（`cloud/`）及 Rust Core（`core-sync`）完全协议兼容的双向增量同步客户端。
 * - 互斥锁防止手动同步与下课自动同步并发重入；
 * - 区分本机创建与远端拉取的记录，杜绝回环覆盖 Mac 端结构化知识点与 iPhone 笔记；
 * - 记录增量推送水位，避免每次全量重传历史课次。
 */
class CloudSyncClient(
    context: Context,
    private val db: Database,
    private val settings: Settings,
) {
    private val prefs = context.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE)
    private val idMapPrefs = context.getSharedPreferences("cloud_sync_id_map", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()

    val deviceId: String by lazy {
        prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }
            ?: "android-${UUID.randomUUID().toString().take(8)}".also {
                prefs.edit().putString("device_id", it).apply()
            }
    }

    suspend fun syncNow(): Result<CloudSyncResult> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.value
                val baseUrl = s.cloudSyncUrl.trim().trimEnd('/')
                require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
                    "请先填写有效的 Google Cloud Run 地址（以 https:// 开头）"
                }
                val token = s.cloudSyncToken.trim()

                // 1. Push only locally-authored records updated since lastPushedAt
                val lastPushedAt = prefs.getLong("last_pushed_at", 0L)
                val (payload, maxLocalTs) = buildLocalChangeSet(lastPushedAt)
                val pushedItems = countItems(payload)
                var latestCursor = prefs.getLong("last_pulled_cursor", 0L)

                if (pushedItems > 0) {
                    val pushReq = Request.Builder()
                        .url("$baseUrl/api/v1/sync/push")
                        .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    httpClient.newCall(pushReq).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            error("上传失败 (${resp.code}): ${body.take(200)}")
                        }
                    }
                    if (maxLocalTs > lastPushedAt) {
                        prefs.edit().putLong("last_pushed_at", maxLocalTs).apply()
                    }
                }

                // 2. Pull remote changes since last server_cursor
                val since = prefs.getLong("last_pulled_cursor", 0L)
                val pullReq = Request.Builder()
                    .url("$baseUrl/api/v1/sync/pull?since=$since")
                    .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                    .get()
                    .build()
                var pulledItems = 0
                httpClient.newCall(pullReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        error("拉取失败 (${resp.code}): ${body.take(200)}")
                    }
                    val cs = JSONObject(body)
                    pulledItems = applyRemoteChangeSet(cs)
                    val nextCursor = cs.optLong("server_cursor", since)
                    if (nextCursor > latestCursor) latestCursor = nextCursor
                }

                prefs.edit()
                    .putLong("last_pulled_cursor", latestCursor)
                    .putLong("last_sync_time", System.currentTimeMillis())
                    .apply()

                CloudSyncResult(pushedItems, pulledItems, latestCursor)
            }.onFailure {
                AppLog.e("CloudSync", "Google Cloud 同步失败", it)
            }
        }
    }

    fun lastSyncTimeMs(): Long = prefs.getLong("last_sync_time", 0L)

    // ------------------------------------------------------------------ export local DB -> ChangeSet

    private fun buildLocalChangeSet(sinceMs: Long): Pair<JSONObject, Long> {
        val now = System.currentTimeMillis()
        var maxTs = sinceMs
        val coursesArr = JSONArray()
        val sessionsArr = JSONArray()
        val segmentsArr = JSONArray()
        val glossaryArr = JSONArray()
        val questionsArr = JSONArray()
        val bookmarksArr = JSONArray()

        val courses = db.listCourses()
        for (c in courses) {
            if (isRemoteOrigin("course", c.id)) continue
            val cid = localToUuid("course", c.id)
            if (c.updatedAt > sinceMs) {
                maxTs = maxOf(maxTs, c.updatedAt)
                coursesArr.put(
                    JSONObject()
                        .put("id", cid)
                        .put("name", c.name)
                        .put("kind", c.kind)
                        .put("summary", "")
                        .put("outline", "")
                        .put("notes", c.notes)
                        .put("created_at", c.createdAt)
                        .put("updated_at", c.updatedAt)
                        .put("deleted_at", JSONObject.NULL)
                        .put("device_id", deviceId)
                )
            }
            for (g in db.listGlossary(c.id)) {
                if (isRemoteOrigin("glossary", g.id)) continue
                val gTs = maxOf(c.updatedAt, g.id)
                if (gTs <= sinceMs && sinceMs > 0L) continue
                maxTs = maxOf(maxTs, c.updatedAt)
                glossaryArr.put(
                    JSONObject()
                        .put("id", localToUuid("glossary", g.id))
                        .put("course_id", cid)
                        .put("term", g.term)
                        .put("translation", g.translation)
                        .put("note", "")
                        .put("source", "android")
                        .put("created_at", c.updatedAt)
                        .put("updated_at", c.updatedAt)
                        .put("deleted_at", JSONObject.NULL)
                        .put("device_id", deviceId)
                )
            }
        }

        val sessions = db.listSessions(limit = 300)
        for (s in sessions) {
            // 跳过录制中的会话，以及从远端拉取的会话（避免覆盖 Mac 端结构化知识点）
            if (s.status == "running" || isRemoteOrigin("session", s.id)) continue
            val updatedAt = s.endedAt ?: s.startedAt
            val sid = localToUuid("session", s.id)
            val cid = s.courseId?.let { localToUuid("course", it) }

            if (updatedAt > sinceMs) {
                maxTs = maxOf(maxTs, updatedAt)
                val kps = extractKnowledgePointsFromSummary(s.summary)
                sessionsArr.put(
                    JSONObject()
                        .put("id", sid)
                        .put("course_id", cid ?: JSONObject.NULL)
                        .put("started_at", s.startedAt)
                        .put("ended_at", s.endedAt ?: JSONObject.NULL)
                        .put("duration_s", s.durationS)
                        .put("audio_source", s.audioSource)
                        .put("source_lang", s.sourceLang)
                        .put("target_lang", s.targetLang)
                        .put("title", s.title)
                        .put("title_source", "")
                        .put("summary", s.summary)
                        .put("knowledge_points", kps)
                        .put("topics", JSONArray())
                        .put("user_note", "")
                        .put("quiz", db.getQuiz(s.id)?.content.orEmpty())
                        .put("starred", false)
                        .put("status", s.status)
                        .put("created_at", s.startedAt)
                        .put("updated_at", updatedAt)
                        .put("deleted_at", JSONObject.NULL)
                        .put("device_id", deviceId)
                )

                for (seg in db.listSegments(s.id)) {
                    if (isRemoteOrigin("segment", seg.id)) continue
                    segmentsArr.put(
                        JSONObject()
                            .put("id", localToUuid("segment", seg.id))
                            .put("session_id", sid)
                            .put("speaker_label", JSONObject.NULL)
                            .put("t0", seg.t0)
                            .put("t1", seg.t1)
                            .put("src_lang", seg.srcLang)
                            .put("src_text", seg.srcText)
                            .put("tgt_lang", s.targetLang)
                            .put("tgt_text", seg.tgtText)
                            .put("low_conf", seg.lowConfidence)
                            .put("created_at", s.startedAt)
                            .put("updated_at", updatedAt)
                            .put("deleted_at", JSONObject.NULL)
                            .put("device_id", deviceId)
                    )
                }
            }

            for (q in db.listQuestions(s.id)) {
                if (q.createdAt <= sinceMs) continue
                maxTs = maxOf(maxTs, q.createdAt)
                questionsArr.put(
                    JSONObject()
                        .put("id", localToUuid("question", q.id))
                        .put("session_id", sid)
                        .put("segment_id", q.segmentId?.let { localToUuid("segment", it) } ?: JSONObject.NULL)
                        .put("question", q.question)
                        .put("question_tgt", q.questionTgt)
                        .put("answer", q.answer)
                        .put("answer_alt", q.answerAlt)
                        .put("source", q.source)
                        .put("created_at", q.createdAt)
                        .put("updated_at", q.createdAt)
                        .put("deleted_at", JSONObject.NULL)
                        .put("device_id", deviceId)
                )
            }

            for (bm in db.listBookmarks(s.id)) {
                if (bm.createdAt <= sinceMs) continue
                maxTs = maxOf(maxTs, bm.createdAt)
                bookmarksArr.put(
                    JSONObject()
                        .put("id", localToUuid("bookmark", bm.id))
                        .put("session_id", sid)
                        .put("segment_id", localToUuid("segment", bm.segmentId))
                        .put("note", "")
                        .put("created_at", bm.createdAt)
                        .put("updated_at", bm.createdAt)
                        .put("deleted_at", JSONObject.NULL)
                        .put("device_id", deviceId)
                )
            }
        }

        val obj = JSONObject()
            .put("schema_version", 1)
            .put("device_id", deviceId)
            .put("generated_at", now)
            .put("since_ms", sinceMs)
            .put("courses", coursesArr)
            .put("sessions", sessionsArr)
            .put("segments", segmentsArr)
            .put("glossary", glossaryArr)
            .put("questions", questionsArr)
            .put("bookmarks", bookmarksArr)
            .put("summaries", JSONArray())
        return obj to maxTs
    }

    // ------------------------------------------------------------------ merge remote ChangeSet -> local DB

    private fun applyRemoteChangeSet(cs: JSONObject): Int {
        var applied = 0

        // 1. Courses
        val courses = cs.optJSONArray("courses") ?: JSONArray()
        for (i in 0 until courses.length()) {
            val o = courses.optJSONObject(i) ?: continue
            if (!o.isNull("deleted_at") || o.optString("device_id") == deviceId) continue
            val uuid = o.optString("id")
            val name = o.optString("name").trim()
            if (uuid.isBlank() || name.isBlank()) continue
            val existingLocal = uuidToLocal("course", uuid)
            if (existingLocal != null && db.getCourse(existingLocal) != null) {
                db.updateCourse(existingLocal, name = name, kind = o.optString("kind"), notes = o.optString("notes"))
            } else {
                val created = db.createCourse(name, o.optString("kind"))
                if (o.optString("notes").isNotBlank()) {
                    db.updateCourse(created.id, notes = o.optString("notes"))
                }
                bindUuid("course", created.id, uuid, remote = true)
            }
            applied++
        }

        // 2. Sessions
        val sessions = cs.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until sessions.length()) {
            val o = sessions.optJSONObject(i) ?: continue
            if (!o.isNull("deleted_at") || o.optString("device_id") == deviceId) continue
            val uuid = o.optString("id")
            if (uuid.isBlank()) continue
            val existingLocal = uuidToLocal("session", uuid)
            val title = o.optString("title")
            val summary = buildMergedSummary(o)
            if (existingLocal != null && db.getSession(existingLocal) != null) {
                if (title.isNotBlank()) db.updateSessionTitle(existingLocal, title, "sync")
                if (summary.isNotBlank()) db.updateSessionSummary(existingLocal, summary)
            } else {
                val courseUuid = o.optString("course_id", "")
                val localCourseId = courseUuid.takeIf { it.isNotBlank() }?.let { uuidToLocal("course", it) }
                val localSid = db.createSession(
                    courseId = localCourseId,
                    source = o.optString("audio_source", "system").ifBlank { "system" },
                    sourceLang = o.optString("source_lang", "auto").ifBlank { "auto" },
                    targetLang = o.optString("target_lang", "auto").ifBlank { "auto" },
                )
                db.endSession(localSid, title, summary)
                if (title.isNotBlank()) db.updateSessionTitle(localSid, title, "sync")
                bindUuid("session", localSid, uuid, remote = true)
            }
            applied++
        }

        // 3. Segments
        val segments = cs.optJSONArray("segments") ?: JSONArray()
        for (i in 0 until segments.length()) {
            val o = segments.optJSONObject(i) ?: continue
            if (!o.isNull("deleted_at") || o.optString("device_id") == deviceId) continue
            val uuid = o.optString("id")
            val localSid = uuidToLocal("session", o.optString("session_id")) ?: continue
            val existingLocal = uuidToLocal("segment", uuid)
            if (existingLocal != null && db.getSegment(existingLocal) != null) {
                val tgt = o.optString("tgt_text")
                if (tgt.isNotBlank()) db.updateSegmentTranslation(existingLocal, tgt)
            } else {
                val localSegId = db.addSegment(
                    sessionId = localSid,
                    t0 = o.optDouble("t0", 0.0),
                    t1 = o.optDouble("t1", 0.0),
                    srcLang = o.optString("src_lang"),
                    srcText = o.optString("src_text"),
                    audioPath = null,
                    lowConfidence = o.optBoolean("low_conf", false),
                )
                val tgt = o.optString("tgt_text")
                if (tgt.isNotBlank()) db.updateSegmentTranslation(localSegId, tgt)
                bindUuid("segment", localSegId, uuid, remote = true)
            }
            applied++
        }

        // 4. Glossary (cache per course to avoid N+1 SQLite queries)
        val glossary = cs.optJSONArray("glossary") ?: JSONArray()
        val glossaryTermsByCourse = HashMap<Long, MutableSet<String>>()
        for (i in 0 until glossary.length()) {
            val o = glossary.optJSONObject(i) ?: continue
            if (!o.isNull("deleted_at") || o.optString("device_id") == deviceId) continue
            val localCid = uuidToLocal("course", o.optString("course_id")) ?: continue
            val term = o.optString("term").trim()
            val trans = o.optString("translation").trim()
            if (term.isBlank() || trans.isBlank()) continue
            val existingTerms = glossaryTermsByCourse.getOrPut(localCid) {
                db.listGlossary(localCid).mapTo(HashSet()) { it.term.lowercase() }
            }
            if (existingTerms.add(term.lowercase())) {
                val gid = db.addGlossary(localCid, term, trans)
                bindUuid("glossary", gid, o.optString("id"), remote = true)
                applied++
            }
        }

        // 5. Bookmarks (including bookmarks toggled from iPhone PWA!)
        val bookmarks = cs.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until bookmarks.length()) {
            val o = bookmarks.optJSONObject(i) ?: continue
            if (o.optString("device_id") == deviceId) continue
            val localSid = uuidToLocal("session", o.optString("session_id")) ?: continue
            val localSegId = uuidToLocal("segment", o.optString("segment_id")) ?: continue
            if (o.isNull("deleted_at")) {
                db.addBookmark(localSid, localSegId)
            } else {
                db.removeBookmark(localSid, localSegId)
            }
            applied++
        }

        return applied
    }

    private fun buildMergedSummary(sessionObj: JSONObject): String {
        val sb = StringBuilder()
        val sum = sessionObj.optString("summary").trim()
        if (sum.isNotEmpty()) sb.append(sum)
        val kps = sessionObj.optJSONArray("knowledge_points")
        if (kps != null && kps.length() > 0) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【核心知识点】")
            for (i in 0 until kps.length()) {
                val kp = kps.optJSONObject(i) ?: continue
                val t = kp.optString("title").trim()
                val e = kp.optString("explanation").trim()
                if (t.isNotEmpty()) {
                    sb.append("\n- ").append(t)
                    if (e.isNotEmpty()) sb.append("：").append(e)
                }
            }
        }
        val userNote = sessionObj.optString("user_note").trim()
        if (userNote.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【跨端笔记】\n").append(userNote)
        }
        return sb.toString()
    }

    /** 将安卓端生成的要点式摘要解析为结构化 KnowledgePoint，供 iPhone PWA 卡片展示。 */
    private fun extractKnowledgePointsFromSummary(summary: String): JSONArray {
        val arr = JSONArray()
        if (summary.isBlank()) return arr
        for (rawLine in summary.lines()) {
            val line = rawLine.trim().trimStart('-', '*', '•', ' ').trim()
            if (line.length < 4 || line.startsWith("主题：") || line.startsWith("【")) continue
            val sepIdx = listOf("：", ": ", " - ").map { line.indexOf(it) }.filter { it > 0 }.minOrNull()
            val title = if (sepIdx != null) line.substring(0, sepIdx).trim() else line.take(28)
            val exp = if (sepIdx != null) line.substring(sepIdx + 1).trimStart(' ', ':', '：', '-') else line
            arr.put(
                JSONObject()
                    .put("title", title)
                    .put("explanation", exp)
                    .put("importance", 2)
                    .put("tags", JSONArray())
                    .put("source_quote", "")
            )
            if (arr.length() >= 8) break
        }
        return arr
    }

    private fun countItems(cs: JSONObject): Int =
        (cs.optJSONArray("courses")?.length() ?: 0) +
            (cs.optJSONArray("sessions")?.length() ?: 0) +
            (cs.optJSONArray("segments")?.length() ?: 0) +
            (cs.optJSONArray("glossary")?.length() ?: 0) +
            (cs.optJSONArray("questions")?.length() ?: 0) +
            (cs.optJSONArray("bookmarks")?.length() ?: 0)

    private fun localToUuid(kind: String, localId: Long): String {
        val key = "l2u:$kind:$localId"
        val existing = idMapPrefs.getString(key, null)
        if (!existing.isNullOrBlank()) return existing
        val deterministic = deterministicUuid("$deviceId:$kind:$localId")
        bindUuid(kind, localId, deterministic, remote = false)
        return deterministic
    }

    private fun uuidToLocal(kind: String, uuid: String): Long? {
        if (uuid.isBlank()) return null
        val v = idMapPrefs.getLong("u2l:$kind:$uuid", -1L)
        return if (v > 0L) v else null
    }

    private fun isRemoteOrigin(kind: String, localId: Long): Boolean =
        idMapPrefs.getBoolean("remote:$kind:$localId", false)

    private fun bindUuid(kind: String, localId: Long, uuid: String, remote: Boolean) {
        if (uuid.isBlank()) return
        idMapPrefs.edit()
            .putString("l2u:$kind:$localId", uuid)
            .putLong("u2l:$kind:$uuid", localId)
            .putBoolean("remote:$kind:$localId", remote)
            .apply()
    }

    private fun deterministicUuid(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (digest[i].toLong() and 0xff)
        for (i in 8..15) lsb = (lsb shl 8) or (digest[i].toLong() and 0xff)
        return UUID(msb, lsb).toString()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }
}
