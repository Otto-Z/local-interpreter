package com.localinterpreter.tablet.assistant

import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.llm.Prompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CourseAssistant(private val app: App) {

    suspend fun askCourse(courseId: Long, question: String, onToken: ((String) -> Unit)? = null): String {
        val chunks = withContext(Dispatchers.IO) { app.db.topMaterialChunks(courseId, question, 5) }
        val context = withContext(Dispatchers.IO) { app.db.courseContext(courseId) }.take(2200)
        val hits = withContext(Dispatchers.IO) { app.db.searchSegments(courseId, question, 8) }
        val transcript = hits.joinToString("\n") {
            "- ${app.db.formatDate(it.sessionStartedAt)} ${it.segment.srcText.take(140)} / ${it.segment.tgtText.take(140)}"
        }
        val materials = chunks.joinToString("\n") {
            "[${it.materialName} 第${it.page}页] ${it.text.take(620)}"
        }
        val body = buildString {
            appendLine("课程背景：")
            appendLine(context.ifBlank { "（无）" })
            if (materials.isNotBlank()) appendLine("\n课程资料片段：\n$materials")
            if (transcript.isNotBlank()) appendLine("\n相关课堂原话：\n$transcript")
        }.clip(4600)
        val prompt = "$body\n\n问题：$question"
        return app.llm.analyze(Prompts.COURSE_QA_SYSTEM, prompt, 700, 0.3f, onToken) ?: ""
    }

    suspend fun outline(courseId: Long): String {
        val chunks = withContext(Dispatchers.IO) { app.db.allMaterialChunks(courseId, 14) }
        val context = withContext(Dispatchers.IO) { app.db.courseContext(courseId) }.take(2200)
        val materialText = chunks.joinToString("\n") { "[第${it.page}页] ${it.text.take(520)}" }
        val prompt = buildString {
            if (materialText.isNotBlank()) appendLine("课程资料：\n$materialText")
            if (context.isNotBlank()) appendLine("\n课程背景：\n$context")
            if (materialText.isBlank() && context.isBlank()) appendLine("（暂无资料，请根据课程名称给出通用预习提纲）")
        }.clip(4800)
        return app.llm.analyze(Prompts.OUTLINE_SYSTEM, prompt, 600, 0.35f) ?: ""
    }

    suspend fun extractGlossary(courseId: Long): List<Pair<String, String>> {
        val chunks = withContext(Dispatchers.IO) { app.db.allMaterialChunks(courseId, 14) }
        val transcript = withContext(Dispatchers.IO) { app.db.courseTranscript(courseId, 80) }
        val prompt = buildString {
            if (chunks.isNotEmpty()) {
                appendLine("课程资料：")
                chunks.forEach { appendLine("[第${it.page}页] ${it.text.take(500)}") }
            }
            if (transcript.isNotEmpty()) {
                appendLine("\n课堂内容：")
                transcript.takeLast(50).forEach { appendLine(it.second.take(120)) }
            }
        }
        val text = app.llm.analyze(Prompts.GLOSSARY_SYSTEM, prompt.clip(4800), 500, 0.3f) ?: return emptyList()
        return parsePairs(text)
    }

    suspend fun extractTerms(sessionId: Long): List<Pair<String, String>> {
        val segments = withContext(Dispatchers.IO) { app.db.listSegments(sessionId) }
        val body = segments.takeLast(80).joinToString("\n") {
            (it.srcText + if (it.tgtText.isNotBlank()) " / ${it.tgtText}" else "").take(180)
        }
        if (body.isBlank()) return emptyList()
        val text = app.llm.analyze(
            Prompts.TERMS_SYSTEM,
            ("课堂内容：\n$body").clip(4800),
            260,
            0.3f,
        ) ?: return emptyList()
        return parsePairs(text)
    }

    suspend fun summarizeSession(sessionId: Long): String {
        val segments = withContext(Dispatchers.IO) { app.db.listSegments(sessionId) }
        if (segments.isEmpty()) return ""
        val body = segments.takeLast(120).joinToString("\n") {
            "- 原文：${it.srcText.take(180)}\n  译文：${it.tgtText.take(180)}"
        }
        return app.llm.analyze(
            Prompts.SUMMARY_SYSTEM,
            ("课堂字幕（按时间顺序）：\n$body").clip(5200),
            360,
            0.3f,
        ) ?: ""
    }

    suspend fun quiz(sessionId: Long): String {
        val segments = withContext(Dispatchers.IO) { app.db.listSegments(sessionId) }
        val body = segments.takeLast(100).joinToString("\n") {
            "- ${it.srcText.take(150)} / ${it.tgtText.take(150)}"
        }
        return app.llm.analyze(Prompts.QUIZ_SYSTEM, ("课堂记录：\n$body").clip(5200), 900, 0.4f) ?: ""
    }

    suspend fun titleFor(sessionId: Long): String {
        val segments = withContext(Dispatchers.IO) { app.db.listSegments(sessionId).take(30) }
        val body = segments.joinToString("\n") { (it.srcText + " / " + it.tgtText).take(160) }
        val text = app.llm.analyze(
            "你是课程助教。用不超过 20 个字给这次课起一个具体标题，只输出标题本身，不要引号和句号。",
            ("课堂内容：\n$body").clip(4000),
            40,
            0.3f,
        ) ?: return ""
        return text.stripQuote().take(30)
    }

    fun parsePairs(text: String): List<Pair<String, String>> {
        val leadingNum = Regex("^\\d+[.)、]\\s*")
        return text.lines().mapNotNull { line ->
            val clean = line.trim().replace(leadingNum, "").trim('-', '*', '•', ' ')
            if (clean.isBlank()) return@mapNotNull null
            val sep = listOf("=>", "->", "→", "：", ":", "＝", "=")
                .mapNotNull { s -> clean.indexOf(s).takeIf { it > 0 }?.let { it to s } }
                .minByOrNull { it.first }
            if (sep == null) return@mapNotNull null
            val term = clean.substring(0, sep.first).trim().trim('"', '“', '”', '*', '-', '•', ' ')
            val trans = clean.substring(sep.first + sep.second.length).trim().trim('"', '“', '”', '*', '-', '•', ' ')
            if (term.length in 1..40 && trans.length in 1..80) term to trans else null
        }.distinctBy { it.first }.take(20)
    }
}

private fun String.stripQuote(): String = trim().trim('"', '“', '”', '「', '」', '。', '.', '\n', ' ')

private fun String.clip(max: Int): String =
    if (length <= max) this else take(max * 2 / 3) + "\n…\n" + takeLast(max / 3)
