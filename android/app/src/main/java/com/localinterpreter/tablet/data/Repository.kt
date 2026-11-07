package com.localinterpreter.tablet.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class Repository(private val context: Context, private val db: Database) {

    private val audioRoot = File(context.filesDir, "audio").apply { mkdirs() }
    private val materialRoot = File(context.filesDir, "materials").apply { mkdirs() }

    fun audioFile(sessionId: Long, index: Int): File =
        File(File(audioRoot, "session_$sessionId").apply { mkdirs() }, "seg_%05d.wav".format(index))

    // --------------------------------------------------------------- material

    fun importMaterial(courseId: Long?, uri: Uri, displayName: String): Result<Long> {
        var dest: File? = null
        return try {
            if (courseId != null && db.getCourse(courseId) == null) {
                return Result.failure(RuntimeException("课程已被删除，请重新选择课程"))
            }
            val supported = setOf("pdf", "docx", "pptx", "txt", "md", "markdown")
            var ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext !in supported) {
                val fromUri = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
                if (fromUri in supported) ext = fromUri
            }
            if (ext !in supported) {
                ext = when (context.contentResolver.getType(uri).orEmpty()) {
                    "application/pdf" -> "pdf"
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                    "text/plain" -> "txt"
                    "text/markdown" -> "md"
                    else -> ext
                }
            }
            if (ext !in supported) {
                return Result.failure(RuntimeException("只支持 PDF / DOCX / PPTX / TXT / Markdown"))
            }
            val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            val file = File(materialRoot, "${System.currentTimeMillis()}_$safeName")
            dest = file
            val stream = context.contentResolver.openInputStream(uri)
                ?: run {
                    file.delete()
                    return Result.failure(RuntimeException("无法读取文件"))
                }
            stream.use { input ->
                file.outputStream().use { output ->
                    var total = 0L
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_MATERIAL_BYTES) {
                            throw RuntimeException("文件太大（超过 ${MAX_MATERIAL_BYTES / 1_048_576}MB）")
                        }
                        output.write(buf, 0, n)
                    }
                }
            }

            val chunks = when (ext) {
                "pdf" -> {
                    if (file.length() > MAX_PDF_BYTES) {
                        file.delete()
                        return Result.failure(RuntimeException("PDF 超过 ${MAX_PDF_BYTES / 1_048_576}MB，请拆分后再导入"))
                    }
                    extractPdf(file)
                }
                "docx" -> listOf(Pair(1, extractDocx(file)))
                "pptx" -> extractPptx(file)
                "txt", "md", "markdown" -> listOf(Pair(1, readTextSmart(file).take(MAX_TEXT_CHARS)))
                else -> emptyList()
            }.flatMap { (page, text) -> chunkText(text, page) }
                .filter { it.second.isNotBlank() }

            if (chunks.isEmpty()) {
                file.delete()
                return Result.failure(RuntimeException("没有提取到文字；扫描版 PDF 暂不支持"))
            }
            if (courseId != null && db.getCourse(courseId) == null) {
                file.delete()
                return Result.failure(RuntimeException("导入过程中课程已被删除，资料未保存"))
            }
            val id = db.addMaterial(courseId, displayName, file.absolutePath, chunks)
            if (id < 0) throw RuntimeException("保存资料失败")
            Result.success(id)
        } catch (e: Exception) {
            runCatching { dest?.delete() }
            Result.failure(RuntimeException(e.message ?: "导入失败", e))
        } catch (e: OutOfMemoryError) {
            runCatching { dest?.delete() }
            Result.failure(RuntimeException("文件内容过于复杂或过大，内存不足，导入失败"))
        }
    }

    fun deleteCourse(id: Long) {
        val materials = db.listMaterials(id)
        materials.forEach {
            db.deleteMaterial(it.id)
            runCatching { File(it.path).delete() }
        }
        db.deleteCourse(id)
    }

    private fun extractPdf(file: File): List<Pair<Int, String>> {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(file).use { doc ->
            val pages = mutableListOf<Pair<Int, String>>()
            val pageCount = doc.numberOfPages
            for (i in 1..pageCount) {
                val stripper = PDFTextStripper()
                stripper.startPage = i
                stripper.endPage = i
                val text = try {
                    stripper.getText(doc)
                } catch (_: Exception) {
                    ""
                }
                if (text.isNotBlank()) pages += (i to text)
            }
            return pages
        }
    }

    private fun extractDocx(file: File): String {
        var xml = ""
        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    xml = readZipEntry(zip, 8 * 1024 * 1024)
                    break
                }
                entry = zip.nextEntry
            }
        }
        return stripXml(xml)
    }

    private fun extractPptx(file: File): List<Pair<Int, String>> {
        val slides = mutableMapOf<Int, String>()
        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    val num = name.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 0
                    if (slides.size < 500) {
                        slides[num] = stripXml(readZipEntry(zip, 4 * 1024 * 1024))
                    }
                }
                entry = zip.nextEntry
            }
        }
        return slides.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun readZipEntry(zip: ZipInputStream, maxBytes: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            if (out.size() + n > maxBytes) throw RuntimeException("文档解压后过大，无法导入")
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** 优先按 UTF-8 读取；识别 UTF-16 BOM；不是合法 UTF-8 时回退 GB18030（常见中文 TXT）。 */
    private fun readTextSmart(file: File): String {
        val limit = 8 * 1024 * 1024
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        fun decode(charset: java.nio.charset.Charset, offset: Int): String? = runCatching {
            val decoder = charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val source = java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset)
            val out = java.nio.CharBuffer.allocate((bytes.size - offset).coerceAtLeast(16) + 16)
            // endOfInput=false：8MB 截断点落在多字节字符中间时不判为整体乱码。
            val result = decoder.decode(source, out, false)
            if (result.isError) error("decode failed")
            out.flip()
            out.toString()
        }.getOrNull()

        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        decode(charset, offset)?.let { return it }
        return runCatching {
            String(bytes, offset, bytes.size - offset, charset("GB18030"))
        }.getOrDefault(String(bytes, offset, bytes.size - offset, Charsets.ISO_8859_1))
    }

    private fun stripXml(xml: String): String {
        var s = xml
        s = s.replace(Regex("</w:p>|</a:p>"), "\n")
        s = s.replace(Regex("<w:tab/>|<a:tab/>"), "\t")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&apos;", "'")
        s = s.replace(Regex("[ \\t]+\\n"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    fun chunkText(text: String, page: Int, maxLen: Int = 760): List<Pair<Int, String>> {
        val paragraphs = text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<Pair<Int, String>>()
        val buf = StringBuilder()
        for (p in paragraphs) {
            if (buf.length + p.length + 1 > maxLen && buf.isNotEmpty()) {
                out += page to buf.toString().trim()
                buf.setLength(0)
            }
            if (p.length > maxLen) {
                p.chunked(maxLen).forEach { part ->
                    if (buf.isNotEmpty()) {
                        out += page to buf.toString().trim()
                        buf.setLength(0)
                    }
                    out += page to part
                }
            } else {
                buf.append(p).append('\n')
            }
        }
        if (buf.isNotBlank()) out += page to buf.toString().trim()
        return out
    }

    fun deleteMaterial(id: Long) {
        val path = db.deleteMaterial(id)
        if (path != null) File(path).delete()
    }

    // ---------------------------------------------------------------- export

    fun sessionSrt(sessionId: Long): String {
        val segments = db.listSegments(sessionId)
        val speakers = db.listSpeakers(sessionId).associateBy { it.id }
        val sb = StringBuilder()
        segments.forEachIndexed { i, seg ->
            sb.append(i + 1).append('\n')
            sb.append(formatSrtTime(seg.t0)).append(" --> ").append(formatSrtTime(seg.t1)).append('\n')
            // 说话人写进字幕正文，播放器导出/剪辑时也能保留是谁说的。
            val speaker = seg.speakerId?.let { speakers[it]?.label }
            if (speaker != null) sb.append('[').append(speaker).append("] ")
            sb.append((seg.tgtText.ifBlank { seg.srcText }).trim()).append("\n\n")
        }
        return sb.toString()
    }

    /** 纯文本时间轴：给做笔记、喂给其它工具用，比 SRT 更好读。 */
    fun sessionTxt(sessionId: Long): String {
        val session = db.getSession(sessionId) ?: return ""
        val segments = db.listSegments(sessionId)
        val speakers = db.listSpeakers(sessionId).associateBy { it.id }
        val sb = StringBuilder()
        sb.append(session.title.ifBlank { "课次" }).append('\n')
        sb.append(db.formatDate(session.startedAt)).append("\n\n")
        for (seg in segments) {
            val total = seg.t0.toLong().coerceAtLeast(0)
            sb.append("[%02d:%02d] ".format(total / 60, total % 60))
            val speaker = seg.speakerId?.let { speakers[it]?.label }
            if (speaker != null) sb.append('(').append(speaker).append(") ")
            sb.append(seg.srcText.trim())
            if (seg.tgtText.isNotBlank()) sb.append("\n         ").append(seg.tgtText.trim())
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatSrtTime(seconds: Double): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0)
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    fun sessionMarkdown(sessionId: Long): String {
        val session = db.getSession(sessionId) ?: return ""
        val speakers = db.listSpeakers(sessionId).associateBy { it.id }
        val sb = StringBuilder()
        sb.append("# ").append(session.title.ifBlank { "课次" }).append("\n\n")
        sb.append("- 时间：").append(db.formatDate(session.startedAt)).append('\n')
        sb.append("- 时长：").append((session.durationS / 60).toInt()).append(" 分钟\n")
        if (session.summary.isNotBlank()) sb.append("\n").append(session.summary).append("\n\n")
        for (seg in db.listSegments(sessionId)) {
            val speaker = seg.speakerId?.let { speakers[it]?.label }
            if (speaker != null) sb.append("**").append(speaker).append("** ")
            sb.append(seg.srcText).append('\n')
            if (seg.tgtText.isNotBlank()) sb.append(seg.tgtText).append('\n')
            sb.append('\n')
        }
        val questions = db.listQuestions(sessionId)
        if (questions.isNotEmpty()) {
            sb.append("## 课堂问答\n\n")
            for (q in questions) {
                sb.append("**问：").append(q.question).append("**\n\n")
                sb.append(q.answer).append("\n\n")
            }
        }
        return sb.toString()
    }

    fun sessionJson(sessionId: Long): String {
        val session = db.getSession(sessionId) ?: return "{}"
        val root = org.json.JSONObject()
        root.put("id", session.id)
        root.put("courseId", session.courseId ?: org.json.JSONObject.NULL)
        root.put("startedAt", session.startedAt)
        root.put("endedAt", session.endedAt ?: org.json.JSONObject.NULL)
        root.put("durationS", session.durationS)
        root.put("audioSource", session.audioSource)
        root.put("sourceLang", session.sourceLang)
        root.put("targetLang", session.targetLang)
        root.put("title", session.title)
        root.put("summary", session.summary)
        root.put("status", session.status)
        val speakers = db.listSpeakers(sessionId)
        val segs = org.json.JSONArray()
        db.listSegments(sessionId).forEach { seg ->
            segs.put(org.json.JSONObject().apply {
                put("id", seg.id)
                put("t0", seg.t0)
                put("t1", seg.t1)
                put("speaker", speakers.firstOrNull { it.id == seg.speakerId }?.label ?: org.json.JSONObject.NULL)
                put("srcLang", seg.srcLang)
                put("srcText", seg.srcText)
                put("tgtText", seg.tgtText)
                put("audioPath", seg.audioPath ?: org.json.JSONObject.NULL)
                put("lowConfidence", seg.lowConfidence)
            })
        }
        root.put("segments", segs)
        val qs = org.json.JSONArray()
        db.listQuestions(sessionId).forEach { q ->
            qs.put(org.json.JSONObject().apply {
                put("question", q.question)
                put("questionTgt", q.questionTgt)
                put("answer", q.answer)
                put("answerAlt", q.answerAlt)
                put("source", q.source)
                put("createdAt", q.createdAt)
            })
        }
        root.put("questions", qs)
        return root.toString(2)
    }

    fun courseMarkdown(courseId: Long): String {
        val course = db.getCourse(courseId) ?: return ""
        val sb = StringBuilder("# ${course.name}\n\n")
        if (course.notes.isNotBlank()) sb.append("## 课程笔记\n\n").append(course.notes).append("\n\n")
        val glossary = db.listGlossary(courseId)
        if (glossary.isNotEmpty()) {
            sb.append("## 术语表\n\n")
            glossary.forEach { sb.append("- ").append(it.term).append("：").append(it.translation).append('\n') }
            sb.append('\n')
        }
        val materials = db.listMaterials(courseId)
        if (materials.isNotEmpty()) {
            sb.append("## 课程资料\n\n")
            materials.forEach { sb.append("- ").append(it.name).append('\n') }
            sb.append('\n')
        }
        for (session in db.listSessions(courseId).sortedBy { it.startedAt }) {
            sb.append(sessionMarkdown(session.id))
            sb.append("\n---\n\n")
        }
        return sb.toString()
    }

    // --------------------------------------------------------------- storage

    data class StorageInfo(
        val databaseBytes: Long,
        val audioBytes: Long,
        val materialBytes: Long,
        val modelBytes: Long,
        val freeBytes: Long,
    )

    fun freeBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    fun storageInfo(): StorageInfo {
        val dbFile = context.getDatabasePath("history.db")
        val models = File(context.filesDir, "models")
        val stat = StatFs(context.filesDir.absolutePath)
        return StorageInfo(
            databaseBytes = dbFile.length(),
            audioBytes = dirSize(audioRoot),
            materialBytes = dirSize(materialRoot),
            modelBytes = dirSize(models),
            freeBytes = stat.availableBytes,
        )
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun cleanupSessions(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        var removed = 0
        for (s in db.listSessions(null, "", 5000)) {
            if (s.startedAt < cutoff && s.status != "running") {
                deleteSession(s.id)
                removed++
            }
        }
        return removed
    }

    fun deleteSession(id: Long, force: Boolean = false) {
        if (!force && db.getSession(id)?.status == "running") {
            throw IllegalStateException("这次课正在进行中，请先结束同传再删除")
        }
        val paths = db.deleteSession(id)
        paths.forEach { runCatching { File(it).delete() } }
        File(audioRoot, "session_$id").deleteRecursively()
    }

    fun audioRoot(): File = audioRoot

    companion object {
        private const val MAX_MATERIAL_BYTES = 200L * 1024 * 1024
        private const val MAX_PDF_BYTES = 60L * 1024 * 1024
        private const val MAX_TEXT_CHARS = 2_000_000
    }
}
