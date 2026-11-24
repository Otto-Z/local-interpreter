package com.localinterpreter.tablet.llm

import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object Langs {
    val targets = listOf(
        "auto" to "自动",
        "zh" to "简体中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
        "it" to "Italiano",
        "pt" to "Português",
        "ar" to "العربية",
        "th" to "ไทย",
        "vi" to "Tiếng Việt",
    )
    val sources = listOf(
        "auto" to "自动",
        "zh" to "中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "yue" to "粤语",
    )

    fun name(code: String): String = when {
        code.startsWith("zh") -> "简体中文"
        code.startsWith("yue") -> "粤语（繁体中文）"
        code == "en" -> "English"
        code == "ja" -> "日本語"
        code == "ko" -> "한국어"
        code == "fr" -> "Français"
        code == "de" -> "Deutsch"
        code == "es" -> "Español"
        code == "ru" -> "Русский"
        code == "it" -> "Italiano"
        code == "pt" -> "Português"
        code == "ar" -> "العربية"
        code == "th" -> "ไทย"
        code == "vi" -> "Tiếng Việt"
        else -> code
    }

    fun resolveTarget(detected: String, target: String): String {
        if (target != "auto") return target
        return if (detected.startsWith("zh") || detected.startsWith("yue")) "en" else "zh"
    }

    fun tagLabel(lang: String): String = when {
        lang.startsWith("zh") -> "中"
        lang.startsWith("yue") -> "粤"
        lang == "en" -> "EN"
        lang == "ja" -> "日"
        lang == "ko" -> "韩"
        else -> lang.uppercase().take(2)
    }
}

object Prompts {
    const val TRANSLATE = """你是专业同声传译。把输入翻译成{target}。只输出自然译文，不要解释、不要复述原文、不要输出思考过程；人名、数字、专有名词要准确，识别错字按上下文理解。"""

    const val CORRECT = """你是语音识别结果的后处理编辑。请根据上下文修正识别文本中的同音字、错别字、漏字、标点和专有名词错误。

规则：
1. 只修正明显错误，不改变原意，不扩写、不缩写、不解释。
2. 人名、产品名、术语参考上下文保持一致。
3. 如果原文没有明显错误，原样返回。
4. 只输出修正后的文本，不要加引号或任何说明。"""

    const val SUMMARY_SYSTEM = "你是课堂助教。请用中文输出 3–5 条课堂要点，每行以「- 」开头；最后一行以「主题：」开头概括当前话题。只输出这些内容，不要解释。"

    const val RECAP_SYSTEM = "你是课堂助教。用更容易理解的中文总结最近几分钟的课堂内容。输出 3–5 条要点，每行以「- 」开头；如果出现关键概念，用一句大白话解释。不要提「字幕」「记录」这些词。"

    const val QA_SYSTEM = "你是课堂助教。根据课堂记录和课件片段回答学生的问题。优先使用课堂记录；课堂记录没有提到时查课件片段，并标注「第几页」；两者都没有时再用常识回答。不要复述课堂记录或问题。第一行只写来源（课堂内容/课件/模型知识/综合），换行后直接给出 3–5 句简洁中文答案。"

    const val COURSE_QA_SYSTEM = "你是课程助教。根据这门课的资料、往次课次摘要和术语表回答问题。先给出结论，再说明依据；引用资料时标注页码。只用中文回答，不知道就直说。"

    const val OUTLINE_SYSTEM = "你是课程助教。根据课程资料生成课前预习提纲：4–6 条本节要点，以及 3 个听课问题。用中文，条理清晰。"

    const val GLOSSARY_SYSTEM = "你是课程助教。从给定资料和课堂记录中提取 8–15 个专业术语。每行一个，格式为「术语 => 译名或一句话说明」。只输出这些行。"

    const val QUIZ_SYSTEM = "你是课程助教。根据课堂记录出一份课后复习题：5 道题，先题干后参考答案（答案另起一行，以「答：」开头）。用中文。"

    const val TERMS_SYSTEM = "你是课程助教。找出这段课堂内容里值得记入术语表的新术语，最多 5 个。每行格式为「术语 => 译名或说明」。没有就输出空。"

    fun translate(target: String): String = TRANSLATE.replace("{target}", Langs.name(target))
}


/** 去掉思考模型输出的 <think>…</think> 片段；流式时也不能把思考过程打到字幕上。 */
object Think {
    private val block = Regex("<think[^>]*>.*?(?:</think[^>]*>|<｜end▁of▁thinking｜>|$)", RegexOption.DOT_MATCHES_ALL)
    private val tag = Regex("</?think[^>]*>|<｜end▁of▁thinking｜>")

    fun strip(text: String): String = tag.replace(block.replace(text, ""), "").trim()
}

class ThinkStreamFilter {
    private val buffer = StringBuilder()
    private var inThink = false

    private companion object {
        val endMarkers = listOf("</think", "<｜end▁of▁thinking｜>")

        /** 缓冲区末尾与某个结束标记前缀相同的最长长度；用于拼回被拆包的标记。 */
        fun longestMarkerPrefix(text: CharSequence, markers: List<String>): Int {
            val max = markers.maxOf { it.length }.coerceAtMost(text.length)
            for (len in max downTo 1) {
                val tail = text.subSequence(text.length - len, text.length).toString()
                if (markers.any { it.startsWith(tail) }) return len
            }
            return 0
        }
    }

    fun feed(chunk: String): String {
        buffer.append(chunk)
        val out = StringBuilder()
        while (buffer.isNotEmpty()) {
            if (inThink) {
                val end1 = buffer.indexOf("</think")
                val end2 = buffer.indexOf("<｜end▁of▁thinking｜>")
                val end = when {
                    end1 >= 0 && end2 >= 0 -> minOf(end1, end2)
                    end1 >= 0 -> end1
                    end2 >= 0 -> end2
                    else -> -1
                }
                if (end < 0) {
                    // 结束标记可能被拆成两段到达（"</" + "think>"）。只保留可能构成标记前缀的尾巴，
                    // 其余直接丢弃；否则一旦误清空，后面的正文会全部被吞掉。
                    val keep = longestMarkerPrefix(buffer, endMarkers)
                    if (keep > 0) buffer.delete(0, buffer.length - keep) else buffer.clear()
                    break
                }
                val close = buffer.indexOf(">", end)
                if (close < 0) {
                    buffer.delete(0, end)
                    break
                }
                buffer.delete(0, close + 1)
                inThink = false
            } else {
                val start = buffer.indexOf("<think")
                if (start >= 0) {
                    out.append(buffer.substring(0, start))
                    val close = buffer.indexOf(">", start)
                    if (close < 0) {
                        buffer.delete(0, start)
                        break
                    }
                    buffer.delete(0, close + 1)
                    inThink = true
                } else {
                    val lt = buffer.lastIndexOf('<')
                    if (lt >= 0 && buffer.length - lt <= 12 && "<think".startsWith(buffer.substring(lt))) {
                        out.append(buffer.substring(0, lt))
                        buffer.delete(0, lt)
                        break
                    }
                    out.append(buffer)
                    buffer.clear()
                }
            }
        }
        return out.toString()
    }

    /** 流结束：把缓冲区里残留的正文吐出来（可能是被拆包的普通文本，而不是思考内容）。 */
    fun finish(): String {
        val rest = buffer.toString()
        buffer.clear()
        return if (inThink) "" else rest
    }
}

/**
 * 在 IO 线程执行阻塞的 JNI/HTTP 调用，并在协程取消时**立刻**触发 [onCancel]，
 * 不必等到阻塞调用返回才感知取消（避免提示词 prefill 或网络建连阶段卡死）。
 */
private suspend fun <T> runCancellableBlocking(onCancel: () -> Unit, block: () -> T): T =
    kotlinx.coroutines.coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) runCatching { onCancel() }
            }
        }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

class LocalLlm(
    private val path: File,
    private val contextSize: Int,
    val threads: Int,
    val label: String,
    val useGpu: Boolean = false,
) {
    private val mutex = Mutex()
    private val lifecycleLock = Any()
    private val nativeLock = Any()
    @Volatile private var native: LlamaNative? = null
    @Volatile private var closed = false
    @Volatile var loaded = false
        private set

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (closed) return false
        if (loaded && native != null) return true
        if (!path.exists() || path.length() == 0L) {
            AppLog.e("LLM", "$label 模型文件不存在: $path")
            return false
        }
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val needBytes = (path.length() * 0.6).toLong().coerceIn(400L * 1024 * 1024, 1_800_000_000L)
            val availBytes = AppLog.availableBytes()
            val libDir = App.instance.applicationInfo.nativeLibraryDir
            val gpuLayers = if (useGpu && LlamaNative.hasGpuBackend(libDir)) 99 else 0
            AppLog.d(
                "LLM",
                "$label 开始加载 ${path.name} size=${path.length() / 1_048_576}MB ctx=$contextSize threads=$threads " +
                    "gpuLayers=$gpuLayers need≈${needBytes / 1_048_576}MB ${AppLog.memory()}"
            )
            if (availBytes < needBytes) {
                AppLog.e(
                    "LLM",
                    "$label 内存不足：可用 ${availBytes / 1_048_576}MB，建议 ${needBytes / 1_048_576}MB；跳过加载"
                )
                return@withContext false
            }
            try {
                LlamaNative.ensureBackend(libDir)
                val n = LlamaNative.load(path.absolutePath, contextSize, threads, gpuLayers)
                val accepted = synchronized(lifecycleLock) {
                    if (closed) {
                        false
                    } else {
                        native = n
                        loaded = n != null
                        true
                    }
                }
                if (!accepted) {
                    n?.close()
                    loaded = false
                    return@withContext false
                }
                AppLog.d(
                    "LLM",
                    "$label 加载${if (loaded) "成功" else "失败"} ${System.currentTimeMillis() - started}ms ${AppLog.memory()}"
                )
                loaded
            } catch (t: Throwable) {
                loaded = false
                AppLog.e("LLM", "$label 加载异常 ${System.currentTimeMillis() - started}ms", t)
                false
            }
        }
    }

    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        onToken: ((String) -> Unit)? = null,
    ): String {
        if (!ensureLoaded()) {
            throw IllegalStateException("$label 模型加载失败（文件损坏或内存不足）")
        }
        return mutex.withLock {
            val n = native ?: throw IllegalStateException("$label 模型未加载")
            val job = kotlin.coroutines.coroutineContext[Job]
            runCancellableBlocking(onCancel = { n.cancel() }) {
                // close() 会等待这个锁，避免 native 句柄在生成过程中被释放（use-after-free）。
                synchronized(nativeLock) {
                    if (closed) throw IllegalStateException("$label 已卸载")
                    n.generate(system, user, maxTokens, temperature) { piece ->
                        if (job?.isCancelled == true) n.cancel()
                        onToken?.invoke(piece)
                    }
                }
            }
        }
    }

    fun cancel() {
        synchronized(lifecycleLock) { native?.cancel() }
    }

    fun close() {
        val n = synchronized(lifecycleLock) {
            closed = true
            val current = native
            native = null
            loaded = false
            current
        }
        if (n != null) {
            // 先让正在生成的调用尽快返回，再等它退出 native 临界区后释放句柄。
            runCatching { n.cancel() }
            synchronized(nativeLock) { runCatching { n.close() } }
        }
    }
}

class ApiLlm(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) {
    private val client = sharedClient
    // 流式请求若长时间收不到任何字节，尽快失败并切换到非流式请求，而不是一直等到 180 秒。
    private val streamClient by lazy { sharedClient.newBuilder().readTimeout(20, TimeUnit.SECONDS).build() }
    private val blockingClient by lazy { sharedClient.newBuilder().readTimeout(90, TimeUnit.SECONDS).build() }
    private val active = AtomicReference<Call?>(null)

    fun cancel() {
        active.get()?.cancel()
    }

    /** 标识因思考耗尽 max_tokens 或仅输出 reasoning 导致 content 为空，供上层自动放大预算重试。 */
    class ApiReasoningBudgetException(
        message: String,
        val finishReason: String = "",
        val hasReasoning: Boolean = false,
    ) : RuntimeException(message)

    private enum class ThinkingHint { NONE, CHAT_TEMPLATE, ENABLE_THINKING, REASONING_EFFORT, THINKING_DISABLED }

    private fun preferredThinkingHint(forceDisable: Boolean): ThinkingHint {
        if (noExtraParams(baseUrl, model)) return ThinkingHint.NONE
        val m = model.lowercase()
        return when {
            listOf("gemini-2.5", "gemini-3", "gpt-5", "o1", "o3", "o4").any { m.contains(it) } ->
                ThinkingHint.REASONING_EFFORT
            listOf("glm-4.5", "glm-4.6", "glm-z1", "doubao").any { m.contains(it) } ->
                ThinkingHint.THINKING_DISABLED
            listOf("qwen3", "qwen-3", "qwq", "thinking", "reasoner", "reasoning", "deepseek-r1", "minimax-m1", "kimi-k2")
                .any { m.contains(it) } ->
                ThinkingHint.CHAT_TEMPLATE
            forceDisable -> ThinkingHint.REASONING_EFFORT
            else -> ThinkingHint.NONE
        }
    }

    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        onToken: ((String) -> Unit)? = null,
        stream: Boolean = true,
        forceDisableThinking: Boolean = false,
    ): String {
        val base = normalizeBase(baseUrl)
        if (base.isBlank()) throw RuntimeException("接口地址为空")
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", user))

        val effectiveMaxTokens = if (needsLargeBudget(baseUrl, model)) maxOf(maxTokens, 2048) else maxTokens
        val primaryHint = preferredThinkingHint(forceDisableThinking)
        val hintVariants = when (primaryHint) {
            ThinkingHint.NONE -> listOf(ThinkingHint.NONE)
            ThinkingHint.CHAT_TEMPLATE -> listOf(ThinkingHint.CHAT_TEMPLATE, ThinkingHint.ENABLE_THINKING, ThinkingHint.NONE)
            else -> listOf(primaryHint, ThinkingHint.NONE)
        }

        fun requestBody(hint: ThinkingHint): String {
            val body = JSONObject()
                .put("model", model.ifBlank { "default" })
                .put("messages", messages)
                .put("stream", stream)
                .put("temperature", temperature)
                .put("max_tokens", effectiveMaxTokens)
            when (hint) {
                ThinkingHint.CHAT_TEMPLATE ->
                    body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                ThinkingHint.ENABLE_THINKING ->
                    body.put("enable_thinking", false)
                ThinkingHint.REASONING_EFFORT ->
                    body.put("reasoning_effort", "none")
                ThinkingHint.THINKING_DISABLED ->
                    body.put("thinking", JSONObject().put("type", "disabled"))
                ThinkingHint.NONE -> Unit
            }
            return body.toString()
        }

        val urls = chatUrls(base)
        var lastError: Throwable? = null
        for (url in urls) {
            var nextUrl = false
            for (hint in hintVariants) {
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "Accept",
                        if (stream) "text/event-stream, application/json" else "application/json",
                    )
                    .post(requestBody(hint).toRequestBody("application/json".toMediaType()))
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
                val call = (if (stream) streamClient else blockingClient).newCall(request)
                AppLog.d(
                    "LLM-API",
                    "POST url=$url model=${model.ifBlank { "default" }} stream=$stream " +
                        "hint=$hint text=${user.length}字 maxTokens=$effectiveMaxTokens",
                )
                active.set(call)
                val job = kotlin.coroutines.coroutineContext[Job]
                try {
                    val started = System.currentTimeMillis()
                    val text = runCancellableBlocking(onCancel = { call.cancel() }) {
                        call.execute().use { resp ->
                            if (!resp.isSuccessful) {
                                val bodyText = runCatching { resp.body?.string() }.getOrNull()?.take(300).orEmpty()
                                throw ApiHttpException(resp.code, "HTTP ${resp.code} $bodyText")
                            }
                            AppLog.d(
                                "LLM-API",
                                "响应 HTTP ${resp.code} type=${resp.header("Content-Type") ?: "-"} " +
                                    "wait=${System.currentTimeMillis() - started}ms",
                            )
                            readResponse(resp, onToken) { job?.isCancelled == true }
                        }
                    }
                    AppLog.d(
                        "LLM-API",
                        "完成 ${System.currentTimeMillis() - started}ms out=${text.length}字 stream=$stream url=$url",
                    )
                    return text
                } catch (e: ApiHttpException) {
                    lastError = e
                    AppLog.e("LLM-API", "请求失败 url=$url hint=$hint ${e.message?.take(200)}")
                    // 服务端不接受扩展参数（常见 400 或 FastAPI 422）时，尝试下一档或退回无扩展参数并记住。
                    if ((e.code == 400 || e.code == 422) && hint != ThinkingHint.NONE) {
                        if (hint == hintVariants.dropLast(1).lastOrNull()) {
                            markNoExtraParams(baseUrl, model)
                        }
                        continue
                    }
                    if (e.code == 404 && url != urls.last()) {
                        nextUrl = true
                        break
                    }
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    // 只有「地址根本连不上」才换下一个候选 URL。空响应/超时换 URL 只会白白多等一轮。
                    if (url != urls.last() &&
                        (e is java.net.ConnectException || e is java.net.UnknownHostException)
                    ) {
                        nextUrl = true
                        break
                    }
                    throw e
                } finally {
                    active.compareAndSet(call, null)
                }
            }
            if (nextUrl) continue
        }
        throw lastError ?: RuntimeException("接口请求失败")
    }

    private class ApiHttpException(val code: Int, message: String) : RuntimeException(message)

    private fun chatUrls(base: String): List<String> {
        if (base.endsWith("/chat/completions")) return listOf(base)
        val urls = mutableListOf("$base/chat/completions")
        if (!base.endsWith("/v1")) urls.add("$base/v1/chat/completions")
        return urls
    }

    private fun readResponse(
        resp: okhttp3.Response,
        onToken: ((String) -> Unit)?,
        isCancelled: () -> Boolean = { false },
    ): String {
        val contentType = resp.header("Content-Type") ?: ""
        val source = resp.body?.source() ?: throw RuntimeException("接口返回了空响应")
        val sb = StringBuilder()
        val raw = StringBuilder()
        var sawReasoning = false
        var serverError: String? = null
        var finishReason = ""
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            val whole = source.readString(Charsets.UTF_8)
            raw.append(whole)
            val text = parseCompletion(whole)
                .ifBlank { parseSseText(whole) }
                .ifBlank { parseJsonLines(whole) }
            if (text.isBlank()) {
                val diag = diagnoseEmptyJson(whole)
                AppLog.e("LLM-API", "接口返回空内容：diag=$diag body=${whole.take(600)}")
                when {
                    !diag.error.isNullOrBlank() ->
                        throw RuntimeException("接口返回错误：${diag.error.take(200)}")
                    !diag.refusal.isNullOrBlank() ->
                        throw RuntimeException("接口拒绝回复：${diag.refusal.take(160)}")
                    diag.finishReason == "content_filter" ->
                        throw RuntimeException("接口内容被安全策略过滤（content_filter）")
                    diag.finishReason == "length" || diag.hasReasoning -> {
                        val reasonDetail = buildString {
                            if (diag.reasoningTokens > 0) append("思考消耗 ${diag.reasoningTokens} tokens")
                            else if (diag.hasReasoning) append("仅返回了思考字段")
                            if (diag.finishReason.isNotBlank()) {
                                if (isNotEmpty()) append("，")
                                append("finish_reason=${diag.finishReason}")
                            }
                        }
                        throw ApiReasoningBudgetException(
                            "模型输出为空（$reasonDetail），未生成最终译文。请在服务端关闭思考模式或改用非思考对话模型。",
                            finishReason = diag.finishReason,
                            hasReasoning = diag.hasReasoning,
                        )
                    }
                    else -> {
                        val summary = diag.summary.ifBlank { whole.take(160) }
                        throw RuntimeException("接口返回为空（HTTP ${resp.code}，$summary）")
                    }
                }
            }
            onToken?.invoke(text)
            return text
        }
        while (!source.exhausted()) {
            if (isCancelled()) throw CancellationException("cancelled")
            val line = source.readUtf8Line() ?: break
            raw.append(line).append('\n')
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            try {
                val obj = JSONObject(data)
                obj.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { serverError = it.take(200) }
                // 兼容 OpenAI Responses API 流式事件：{"type":"response.output_text.delta","delta":"..."}
                if (obj.optString("type") == "response.output_text.delta") {
                    val piece = contentOf(obj, "delta")
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        onToken?.invoke(piece)
                    }
                    continue
                }
                val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finishReason = it }
                val delta = choice.optJSONObject("delta")
                val message = choice.optJSONObject("message")
                if (hasReasoningField(delta) || hasReasoningField(message)) {
                    sawReasoning = true
                }
                val piece = contentOf(delta, "content")
                    .ifEmpty { contentOf(message, "content") }
                    .ifEmpty { contentOf(choice, "text") }
                if (piece.isNotEmpty()) {
                    sb.append(piece)
                    onToken?.invoke(piece)
                }
            } catch (_: Exception) {
            }
        }
        if (sb.isEmpty()) {
            val rawStr = raw.toString()
            val fallback = parseCompletion(rawStr)
                .ifBlank { parseSseText(rawStr) }
                .ifBlank { parseJsonLines(rawStr) }
            if (fallback.isNotBlank()) {
                onToken?.invoke(fallback)
                return fallback
            }
            AppLog.e("LLM-API", "接口流式返回空内容：${rawStr.take(600)}")
            val hasReasoningText = sawReasoning || rawStr.contains("reasoning_content") ||
                rawStr.contains("\"reasoning\"") || rawStr.contains("\"thinking\"")
            when {
                serverError != null -> throw RuntimeException("接口返回错误：$serverError")
                finishReason == "content_filter" -> throw RuntimeException("接口内容被安全策略过滤（content_filter）")
                finishReason == "length" || hasReasoningText ->
                    throw ApiReasoningBudgetException(
                        "模型流式输出为空（${if (finishReason.isNotBlank()) "finish_reason=$finishReason" else "仅输出思考内容"}），没有最终译文。",
                        finishReason = finishReason,
                        hasReasoning = hasReasoningText,
                    )
                else -> throw RuntimeException("接口没有返回任何内容（SSE）。请确认模型名正确、服务端支持 /chat/completions 且未拒绝 stream。")
            }
        }
        return sb.toString()
    }

    companion object {
        // 某些网关的 SSE 永远是空的；记住 10 分钟，后续请求直接走非流式，省掉每句一次空等待。
        private val blockingPreferred = java.util.concurrent.ConcurrentHashMap<String, Long>()
        // 某些推理模型需要更大 max_tokens 才能在思考后输出译文；记住 10 分钟。
        private val largeBudgetPreferred = java.util.concurrent.ConcurrentHashMap<String, Long>()
        // 某些服务端严格校验字段，拒绝 chat_template_kwargs / reasoning_effort / thinking；记住 10 分钟。
        private val noExtraParamsPreferred = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun apiKey(base: String, model: String): String =
            normalizeBase(base).lowercase() + "|" + model.lowercase()

        private fun checkTtl(map: java.util.concurrent.ConcurrentHashMap<String, Long>, base: String, model: String): Boolean {
            val key = apiKey(base, model)
            val at = map[key] ?: return false
            if (System.currentTimeMillis() - at > 10 * 60_000L) {
                map.remove(key)
                return false
            }
            return true
        }

        fun preferBlocking(base: String, model: String): Boolean = checkTtl(blockingPreferred, base, model)
        fun markStreamBroken(base: String, model: String) {
            blockingPreferred[apiKey(base, model)] = System.currentTimeMillis()
        }

        fun needsLargeBudget(base: String, model: String): Boolean = checkTtl(largeBudgetPreferred, base, model)
        fun markNeedsLargeBudget(base: String, model: String) {
            largeBudgetPreferred[apiKey(base, model)] = System.currentTimeMillis()
        }

        fun noExtraParams(base: String, model: String): Boolean = checkTtl(noExtraParamsPreferred, base, model)
        fun markNoExtraParams(base: String, model: String) {
            noExtraParamsPreferred[apiKey(base, model)] = System.currentTimeMillis()
        }

        // 所有 API 调用共用一个连接池，避免每句字幕都重新 TLS 握手并堆积空闲 socket。
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        fun normalizeBase(url: String): String {
            var base = url.trim().trimEnd('/')
            if (base.isBlank()) return ""
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                // 只对回环/局域网地址用明文 http；公网地址默认 https，避免 API Key 明文上网。
                val authority = base.substringBefore('/')
                val host = if (authority.startsWith("[")) {
                    authority.substringBefore(']').lowercase() + "]"
                } else {
                    authority.substringBefore(':').lowercase()
                }
                val local = host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ||
                    host == "[::1]" || host.endsWith(".local") ||
                    host.startsWith("10.") || host.startsWith("192.168.") ||
                    Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
                base = (if (local) "http://" else "https://") + base
            }
            return base
        }

        private fun hasReasoningField(obj: JSONObject?): Boolean {
            if (obj == null) return false
            return contentOf(obj, "reasoning_content").isNotEmpty() ||
                contentOf(obj, "reasoning").isNotEmpty() ||
                contentOf(obj, "thinking").isNotEmpty() ||
                contentOf(obj, "thoughts").isNotEmpty()
        }

        /** org.json 的 optString 会把 JSON null 变成字符串 "null"，这里统一判空；兼容数组式 content 与多模态 parts。 */
        private fun contentOf(obj: JSONObject?, key: String): String {
            if (obj == null || !obj.has(key) || obj.isNull(key)) return ""
            return when (val value = obj.opt(key)) {
                is String -> value
                is JSONArray -> buildString {
                    for (i in 0 until value.length()) {
                        when (val part = value.opt(i)) {
                            null, JSONObject.NULL -> Unit
                            is JSONObject -> {
                                val type = part.optString("type").lowercase()
                                if (type == "thinking" || type == "reasoning" || type == "thought" || part.optBoolean("thought", false)) {
                                    continue
                                }
                                append(
                                    contentOf(part, "text")
                                        .ifEmpty { contentOf(part, "output_text") }
                                        .ifEmpty { contentOf(part, "content") }
                                )
                            }
                            is String -> append(part)
                            else -> append(part.toString())
                        }
                    }
                }
                is JSONObject -> contentOf(value, "text").ifEmpty { contentOf(value, "content") }
                null, JSONObject.NULL -> ""
                else -> value.toString()
            }
        }

        private fun extractFromJsonObject(obj: JSONObject, depth: Int = 0): String {
            if (depth > 3) return ""
            // 1. 标准 OpenAI Chat Completions / 兼容网关（支持 message / delta / text / parts / 多 choice）
            obj.optJSONArray("choices")?.let { choices ->
                for (i in 0 until choices.length()) {
                    val choice = choices.optJSONObject(i) ?: continue
                    val msg = choice.optJSONObject("message")
                    val delta = choice.optJSONObject("delta")
                    val extracted = contentOf(msg, "content")
                        .ifBlank { contentOf(msg, "output_text") }
                        .ifBlank { contentOf(msg, "parts") }
                        .ifBlank { contentOf(delta, "content") }
                        .ifBlank { contentOf(choice, "text") }
                        .ifBlank { contentOf(choice, "content") }
                    if (extracted.isNotBlank()) return extracted
                }
            }
            // 2. OpenAI Responses API: {"id":"resp_...", "output_text":"..."} 或 output[].content[].text
            contentOf(obj, "output_text").takeIf { it.isNotBlank() }?.let { return it }
            obj.optJSONArray("output")?.let { outputArr ->
                val out = buildString {
                    for (i in 0 until outputArr.length()) {
                        val item = outputArr.optJSONObject(i) ?: continue
                        val type = item.optString("type").lowercase()
                        if (type == "reasoning" || type == "thinking") continue
                        append(
                            contentOf(item, "content")
                                .ifEmpty { contentOf(item, "text") }
                                .ifEmpty { contentOf(item, "output_text") }
                        )
                    }
                }
                if (out.isNotBlank()) return out
            }
            // 3. Anthropic Messages API: {"id":"msg_...", "content":[{"type":"text","text":"..."}]}
            contentOf(obj, "content").takeIf { it.isNotBlank() }?.let { return it }
            // 4. Ollama /api/chat: {"message":{"role":"assistant","content":"..."}}
            contentOf(obj.optJSONObject("message"), "content").takeIf { it.isNotBlank() }?.let { return it }
            // 5. 嵌套包装：{"code":0,"data":{"choices":[...]}}
            obj.optJSONObject("data")?.let { dataObj ->
                val nested = extractFromJsonObject(dataObj, depth + 1)
                if (nested.isNotBlank()) return nested
            }
            // 6. 极简自定义网关：{"id":"...","response":"..."} / {"result":"..."} / {"text":"..."}
            if (!obj.has("choices")) {
                contentOf(obj, "response").takeIf { it.isNotBlank() }?.let { return it }
                contentOf(obj, "result").takeIf { it.isNotBlank() }?.let { return it }
                contentOf(obj, "text").takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }

        private data class EmptyDiag(
            val error: String? = null,
            val refusal: String? = null,
            val finishReason: String = "",
            val hasReasoning: Boolean = false,
            val reasoningTokens: Int = 0,
            val summary: String = "",
        )

        private fun diagnoseEmptyJson(whole: String): EmptyDiag {
            val trimmed = whole.trim()
            if (!trimmed.startsWith("{")) return EmptyDiag(summary = "非 JSON 响应：${trimmed.take(120)}")
            return runCatching {
                val obj = JSONObject(trimmed)
                val errObj = obj.optJSONObject("error")
                val err = errObj?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: obj.optString("error").takeIf { it.isNotBlank() && it != "null" && !it.startsWith("{") }
                    ?: obj.optString("message").takeIf { it.isNotBlank() && it != "null" && obj.optJSONArray("choices") == null }
                    ?: obj.optString("msg").takeIf { it.isNotBlank() && it != "null" && obj.optJSONArray("choices") == null }
                val choices = obj.optJSONArray("choices")
                val firstChoice = choices?.optJSONObject(0)
                val msg = firstChoice?.optJSONObject("message") ?: firstChoice?.optJSONObject("delta")
                val finishReason = firstChoice?.optString("finish_reason")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: obj.optString("status").takeIf { it == "incomplete" }
                    ?: ""
                val refusal = contentOf(msg, "refusal").takeIf { it.isNotBlank() }
                val usage = obj.optJSONObject("usage")
                val details = usage?.optJSONObject("completion_tokens_details")
                    ?: usage?.optJSONObject("output_tokens_details")
                val reasoningTokens = details?.optInt("reasoning_tokens", 0) ?: 0
                val hasReasoning = reasoningTokens > 0 ||
                    hasReasoningField(msg) ||
                    hasReasoningField(firstChoice) ||
                    whole.contains("\"reasoning_content\"") ||
                    whole.contains("\"reasoning\":") ||
                    whole.contains("\"thinking\":")
                val summary = buildString {
                    if (finishReason.isNotBlank()) append("finish_reason=$finishReason")
                    if (choices != null) {
                        if (isNotEmpty()) append("，")
                        append("choices=${choices.length()}")
                    }
                    if (msg != null) {
                        val keys = mutableListOf<String>()
                        val it = msg.keys()
                        while (it.hasNext() && keys.size < 6) keys.add(it.next())
                        if (keys.isNotEmpty()) {
                            if (isNotEmpty()) append("，")
                            append("message=${keys.joinToString(",")}")
                        }
                    }
                    if (isEmpty()) {
                        val topKeys = mutableListOf<String>()
                        val it = obj.keys()
                        while (it.hasNext() && topKeys.size < 6) topKeys.add(it.next())
                        append("字段=${topKeys.joinToString(",")}")
                    }
                }
                EmptyDiag(
                    error = err,
                    refusal = refusal,
                    finishReason = finishReason,
                    hasReasoning = hasReasoning,
                    reasoningTokens = reasoningTokens,
                    summary = summary,
                )
            }.getOrElse { EmptyDiag(summary = "响应：${trimmed.take(120)}") }
        }

        private fun parseJsonLines(text: String): String {
            val sb = StringBuilder()
            text.lineSequence().forEach { line ->
                val t = line.trim()
                if (!t.startsWith("{")) return@forEach
                runCatching {
                    val piece = extractFromJsonObject(JSONObject(t))
                    if (piece.isNotEmpty()) sb.append(piece)
                }
            }
            return sb.toString()
        }

        private fun parseCompletion(text: String): String {
            return try {
                extractFromJsonObject(JSONObject(text.trim()))
            } catch (_: Exception) {
                ""
            }
        }

        /** 从一段完整的 SSE 文本里拼出内容；兼容 delta、message 与 Responses API 事件。 */
        private fun parseSseText(text: String): String {
            val sb = StringBuilder()
            text.lineSequence().forEach { line ->
                val t = line.trim()
                if (!t.startsWith("data:")) return@forEach
                val data = t.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") return@forEach
                runCatching {
                    val obj = JSONObject(data)
                    val piece = if (obj.optString("type") == "response.output_text.delta") {
                        contentOf(obj, "delta")
                    } else {
                        extractFromJsonObject(obj)
                    }
                    if (piece.isNotEmpty()) sb.append(piece)
                }
            }
            return sb.toString().ifBlank { parseCompletion(text) }
        }

        fun listModels(baseUrl: String, apiKey: String): List<String> {
            val client = sharedClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            var base = normalizeBase(baseUrl)
            if (base.isBlank()) return emptyList()
            if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
            val urls = mutableListOf("$base/models")
            if (!base.endsWith("/v1")) urls.add("$base/v1/models")
            for (url in urls) {
                val req = Request.Builder().url(url)
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
                val result = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val text = resp.body?.string() ?: return@use null
                    JSONObject(text).optJSONArray("data")?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
                        }
                    }
                }
                if (result != null) return result
            }
            return emptyList()
        }
    }
}

class LlmService(private val app: App) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translatorLock = Mutex()
    private val assistantLock = Mutex()
    @Volatile private var warmUpJob: Job? = null
    @Volatile private var translator: LocalLlm? = null
    @Volatile private var translatorPath: String = ""
    @Volatile private var translatorThreads: Int = 0
    @Volatile private var translatorUseGpu: Boolean = false
    @Volatile private var assistant: LocalLlm? = null
    @Volatile private var assistantPath: String = ""
    @Volatile private var assistantThreads: Int = 0
    @Volatile private var assistantUseGpu: Boolean = false

    private val apiTranslatorCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<ApiLlm>()
    private val apiAssistantCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<ApiLlm>()

    private val _translatorStatus = MutableStateFlow("未加载")
    val translatorStatus: StateFlow<String> = _translatorStatus.asStateFlow()
    private val _assistantStatus = MutableStateFlow("未加载")
    val assistantStatus: StateFlow<String> = _assistantStatus.asStateFlow()

    fun hasGpuBackend(): Boolean = LlamaNative.hasGpuBackend(app.applicationInfo.nativeLibraryDir)

    fun backendSummary(): String = LlamaNative.backendSummary(app.applicationInfo.nativeLibraryDir)

    private fun localTranslator(s: SettingsData = app.settings.value): LocalLlm? {
        val file = app.models.translatorModel(s.modelVariant)
        val kind = app.models.kindFor(s.modelVariant)
        val problem = app.models.fileProblem(kind)
        if (problem != null) {
            _translatorStatus.value = "翻译模型异常：$problem"
            AppLog.e("LLM", "翻译模型不可用：$problem path=$file")
            return null
        }
        val path = file.absolutePath
        if (translator?.loaded == true && translatorPath == path &&
            translatorThreads == s.llmThreads && translatorUseGpu == s.useGpu
        ) return translator
        return synchronized(this) {
            if (translatorPath != path || translator == null ||
                translatorThreads != s.llmThreads || translatorUseGpu != s.useGpu
            ) {
                val old = translator
                // 翻译只处理「一句原文 + 可选术语表」，1536 已经宽裕；再大只会让 KV 分配/拷贝变慢。
                translator = LocalLlm(file, 1536, s.llmThreads, "Hy-MT2", s.useGpu)
                translatorPath = path
                translatorThreads = s.llmThreads
                translatorUseGpu = s.useGpu
                old?.let { closeInBackground(it) }
            }
            translator
        }
    }

    private fun closeInBackground(llm: LocalLlm) {
        Thread({ runCatching { llm.close() } }, "llm-unload").apply { isDaemon = true }.start()
    }

    private suspend fun apiAnalyzeWithRetry(
        base: String,
        model: String,
        key: String,
        system: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onToken: ((String) -> Unit)?,
    ): String? {
        var last: Throwable? = null
        val collected = StringBuilder()
        val sink: ((String) -> Unit) = { piece ->
            if (piece.isNotEmpty()) {
                collected.append(piece)
                onToken?.invoke(piece)
            }
        }
        var curMaxTokens = maxTokens
        var forceDisableThinking = false
        for (attempt in 1..3) {
            val useStream = attempt == 1 && !ApiLlm.preferBlocking(base, model)
            val api = ApiLlm(base, model, key)
            apiAssistantCalls.add(api)
            try {
                return api.generate(
                    system, prompt, curMaxTokens, temperature, sink,
                    stream = useStream,
                    forceDisableThinking = forceDisableThinking,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiLlm.ApiReasoningBudgetException) {
                last = e
                if (collected.isNotEmpty()) return collected.toString()
                ApiLlm.markNeedsLargeBudget(base, model)
                curMaxTokens = if (curMaxTokens >= 2048) 4096 else maxOf(curMaxTokens * 6, 2048)
                forceDisableThinking = true
                if (attempt == 3) throw e
                AppLog.d("LLM", "API 助教思考耗尽预算，自动放大 max_tokens=$curMaxTokens 重试 $attempt/3")
                kotlinx.coroutines.delay(200L * attempt)
            } catch (e: Throwable) {
                last = e
                if (collected.isNotEmpty()) return collected.toString()
                val msg = e.message.orEmpty()
                val transient = e is java.net.SocketTimeoutException ||
                    e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)
                if (useStream && (msg.contains("没有返回任何内容") || msg.contains("返回为空") || transient)) {
                    // 首 token 超过 20 秒是长 prompt 的常态：记住这条链路，下一次直接走非流式（90 秒）。
                    ApiLlm.markStreamBroken(base, model)
                }
                val retryable = msg.contains("没有返回任何内容") || msg.contains("返回为空") ||
                    msg.contains("接口返回错误") || msg.contains("HTTP 429") || msg.contains("HTTP 5") ||
                    msg.contains("截断") || transient
                if (!retryable || attempt == 3) throw e
                AppLog.d("LLM", "API 助教失败，重试 $attempt/3 stream=$useStream：${msg.take(120)}")
                kotlinx.coroutines.delay(300L * attempt)
            } finally {
                apiAssistantCalls.remove(api)
            }
        }
        throw last ?: RuntimeException("助教 API 失败")
    }

    /** 兼容网关偶发返回空 SSE 及思考模型耗尽 max_tokens：自动切换非流式与放大预算重试。 */
    private suspend fun apiTranslateWithRetry(
        s: SettingsData,
        system: String,
        text: String,
        maxTokens: Int,
        temperature: Float,
        onToken: ((String) -> Unit)?,
    ): String {
        var last: Throwable? = null
        val collected = StringBuilder()
        val sink: ((String) -> Unit) = { piece ->
            if (piece.isNotEmpty()) {
                collected.append(piece)
                onToken?.invoke(piece)
            }
        }
        var curMaxTokens = maxTokens
        var forceDisableThinking = false
        for (attempt in 1..3) {
            // 第一次用流式；网关 SSE 偶发返回空时，后续改用非流式整包返回。
            val useStream = attempt == 1 && !ApiLlm.preferBlocking(s.apiBaseUrl, s.apiModel)
            val api = ApiLlm(s.apiBaseUrl, s.apiModel, s.apiKey)
            apiTranslatorCalls.add(api)
            try {
                return api.generate(
                    system, text, curMaxTokens, temperature, sink,
                    stream = useStream,
                    forceDisableThinking = forceDisableThinking,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiLlm.ApiReasoningBudgetException) {
                last = e
                if (collected.isNotEmpty()) return collected.toString()
                ApiLlm.markNeedsLargeBudget(s.apiBaseUrl, s.apiModel)
                curMaxTokens = if (curMaxTokens >= 2048) 4096 else maxOf(curMaxTokens * 8, 2048)
                forceDisableThinking = true
                if (attempt == 3) throw e
                _translatorStatus.value = "模型思考截断，放大额度重试 $attempt/3"
                AppLog.d("LLM", "API 翻译思考耗尽预算，自动关闭思考并放大 max_tokens=$curMaxTokens 重试 $attempt/3")
                kotlinx.coroutines.delay(200L * attempt)
            } catch (e: Throwable) {
                last = e
                if (collected.isNotEmpty()) {
                    AppLog.d("LLM", "API 翻译中断，保留已收到的 ${collected.length} 字：${e.message?.take(80)}")
                    return collected.toString()
                }
                val msg = e.message.orEmpty()
                val transient = e is java.net.SocketTimeoutException ||
                    e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)
                if (useStream && (msg.contains("没有返回任何内容") || msg.contains("返回为空") || transient)) {
                    ApiLlm.markStreamBroken(s.apiBaseUrl, s.apiModel)
                    AppLog.d("LLM", "网关卡流/超时，之后 10 分钟优先使用非流式请求")
                }
                val retryable = msg.contains("没有返回任何内容") || msg.contains("返回为空") ||
                    msg.contains("接口返回错误") || msg.contains("HTTP 429") || msg.contains("HTTP 5") ||
                    msg.contains("截断") || transient
                if (!retryable || attempt == 3) throw e
                _translatorStatus.value = if (useStream) "接口无响应，改用非流式重试 $attempt/3" else "接口重试 $attempt/3"
                AppLog.d("LLM", "API 翻译失败，重试 $attempt/3 stream=$useStream：${msg.take(120)}")
                kotlinx.coroutines.delay(300L * attempt)
            } finally {
                apiTranslatorCalls.remove(api)
            }
        }
        throw last ?: RuntimeException("API 翻译失败")
    }

    private fun localAssistant(): LocalLlm? {
        val s = app.settings.value
        val kind = app.models.assistantKind(s.assistantVariant)
        val file = app.models.assistantModel(s.assistantVariant)
        val problem = app.models.fileProblem(kind)
        if (problem != null) {
            _assistantStatus.value = "助教模型异常：$problem"
            AppLog.e("LLM", "助教模型不可用：$problem path=$file")
            return null
        }
        val label = if (s.assistantVariant == "1.5b") "Qwen2.5-1.5B" else "Qwen3-4B"
        val path = file.absolutePath
        if (assistant?.loaded == true && assistantPath == path &&
            assistantThreads == s.llmThreads && assistantUseGpu == s.useGpu
        ) return assistant
        return synchronized(this) {
            if (assistantPath != path || assistant == null ||
                assistantThreads != s.llmThreads || assistantUseGpu != s.useGpu
            ) {
                val old = assistant
                assistant = LocalLlm(file, 6144, s.llmThreads, label, s.useGpu)
                assistantPath = path
                assistantThreads = s.llmThreads
                assistantUseGpu = s.useGpu
                old?.let { closeInBackground(it) }
            }
            assistant
        }
    }

    /**
     * 设置里的线程数变了以后调用：先同步摘除旧的本地模型句柄（后台安全释放），
     * 可选立即按新线程数预热翻译模型，彻底避免卸载线程与预热协程竞态把新模型卸掉。
     */
    fun reloadLocalModelsForThreadChange(warmUp: Boolean = false) {
        warmUpJob?.cancel()
        warmUpJob = null
        unloadTranslator()
        unloadAssistant()
        AppLog.d("LLM", "线程数已变更，已卸载本地模型，下次使用按新线程数加载")
        if (warmUp) warmUpTranslator()
    }

    fun warmUpTranslator(glossary: String = "") {
        val s = app.settings.value
        if (s.llmMode != SettingsData.MODE_BUILTIN) return
        val llm = localTranslator(s) ?: return
        warmUpJob?.cancel()
        _translatorStatus.value = "加载中…"
        warmUpJob = serviceScope.launch {
            if (!llm.ensureLoaded()) {
                if (translator === llm) _translatorStatus.value = "内置模型加载失败"
                return@launch
            }
            // 只加载权重还不够：第一句的开销大头是 KV 缓冲分配和首个 System Prompt + 术语表 prefill。
            // 按当前目标语言与术语表跑一次极短贪心生成，让第一句真实字幕直接命中 KV Prefix Cache。
            val started = System.currentTimeMillis()
            val warmTarget = when {
                s.targetLang != "auto" -> s.targetLang
                s.sourceLang.startsWith("zh") || s.sourceLang.startsWith("yue") -> "en"
                else -> "zh"
            }
            var warmSystem = Prompts.translate(warmTarget)
            if (glossary.isNotBlank()) {
                val capped = if (glossary.length > 480) glossary.take(480) else glossary
                warmSystem += "\n\n术语表（保持译名一致）：$capped"
            }
            val warmUser = if (warmTarget == "en") "你好" else "Hello"
            runCatching {
                llm.generate(warmSystem, warmUser, 4, 0.0f)
            }.onFailure {
                if (it !is CancellationException) AppLog.e("LLM", "预热生成失败（不影响使用）", it)
            }
            if (translator === llm && llm.loaded) {
                _translatorStatus.value = "内置模型就绪"
                AppLog.d("LLM", "翻译模型预热完成 ${System.currentTimeMillis() - started}ms target=$warmTarget")
            }
        }
    }

    suspend fun translate(
        text: String,
        srcLang: String,
        target: String,
        glossary: String = "",
        maxTokens: Int = 260,
        temperature: Float = 0.0f,
        allowEmpty: Boolean = false,
        config: SettingsData? = null,
        context: String = "",
        onToken: ((String) -> Unit)? = null,
    ): String {
        if (text.isBlank()) return ""
        val s = config ?: app.settings.value
        val tgt = Langs.resolveTarget(srcLang, target)
        AppLog.d(
            "LLM",
            "翻译请求 mode=${s.llmMode} " +
                "model=${if (s.llmMode == SettingsData.MODE_API) s.apiModel else "Hy-MT2/${s.modelVariant}"} " +
                "base=${if (s.llmMode == SettingsData.MODE_API) s.apiBaseUrl else "local"} " +
                "src=$srcLang target=$tgt text=${text.length}字 ctx=${context.length}字 glossary=${glossary.length}字",
        )
        var system = Prompts.translate(tgt)
        if (glossary.isNotBlank()) {
            // 翻译模型只有 1536 上下文；术语表过长会把正文挤出去，导致整句没有输出。
            val capped = if (glossary.length > 480) glossary.take(480) else glossary
            system += "\n\n术语表（保持译名一致）：$capped"
        }
        // 前文上下文放在 user 消息中而非 system，确保 System Prompt + 术语表的 KV Prefix Cache 始终 100% 命中。
        val userPrompt = if (context.isNotBlank()) {
            val cappedCtx = if (context.length > 260) context.takeLast(260) else context
            "前文参考（仅用于理解代词与语境，不要重复翻译）：\n$cappedCtx\n\n请只翻译这一句：\n$text"
        } else {
            text
        }
        val filter = ThinkStreamFilter()
        val stream: ((String) -> Unit)? = if (onToken == null) null else { piece ->
            val clean = filter.feed(piece)
            if (clean.isNotEmpty()) onToken(clean)
        }
        val started = System.currentTimeMillis()
        val timedOut = AtomicBoolean(false)
        val activeLocal = AtomicReference<LocalLlm?>(null)
        val raw = try {
            kotlinx.coroutines.coroutineScope {
                // withTimeout 无法打断阻塞的 JNI/OkHttp 调用；用看门狗直接取消当前生成。
                val watchdog = launch {
                    // API 模式包含「流式失败 -> 非流式重试」的时间，比本地模型多留一点余量。
                    kotlinx.coroutines.delay(if (s.llmMode == SettingsData.MODE_API) 100_000 else 60_000)
                    timedOut.set(true)
                    AppLog.e("LLM", "翻译超时，正在中断 text=${text.take(40)}")
                    if (s.llmMode == SettingsData.MODE_API) {
                        apiTranslatorCalls.forEach { runCatching { it.cancel() } }
                    } else {
                        (activeLocal.get() ?: translator)?.cancel()
                    }
                }
                try {
                    if (s.llmMode == SettingsData.MODE_API) {
                        _translatorStatus.value = "API · ${s.apiModel.ifBlank { "未填模型" }}"
                        if (s.apiBaseUrl.isBlank() || s.apiModel.isBlank()) {
                            _translatorStatus.value = "API 未配置"
                            throw RuntimeException("请先在设置里填写接口地址和模型")
                        }
                        apiTranslateWithRetry(s, system, userPrompt, maxTokens, temperature, stream).also {
                            _translatorStatus.value = "API · ${s.apiModel.ifBlank { "未填模型" }}"
                        }
                    } else {
                        val llm = localTranslator(s)
                        if (llm == null) {
                            throw RuntimeException(_translatorStatus.value)
                        }
                        activeLocal.set(llm)
                        translatorLock.withLock {
                            _translatorStatus.value = if (llm.loaded) "内置模型就绪" else "加载中…"
                            val out = llm.generate(system, userPrompt, maxTokens, temperature, stream)
                            if (llm.loaded) _translatorStatus.value = "内置模型就绪"
                            out
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (timedOut.get()) {
                _translatorStatus.value = "翻译超时"
                AppLog.e("LLM", "翻译超时 after ${System.currentTimeMillis() - started}ms text=${text.take(40)}")
                if (allowEmpty) return ""
                throw RuntimeException("翻译超时，请检查设备内存或改用 2bit 模型/API")
            }
            if (allowEmpty && e is java.io.IOException &&
                (e.message?.contains("Canceled", ignoreCase = true) == true ||
                    e is java.io.InterruptedIOException)
            ) {
                AppLog.d("LLM", "草稿翻译已取消 ${System.currentTimeMillis() - started}ms")
                return ""
            }
            _translatorStatus.value = "翻译失败：${e.message?.take(40) ?: "未知错误"}"
            AppLog.e("LLM", "翻译失败 ${System.currentTimeMillis() - started}ms mode=${s.llmMode} text=${text.take(40)}", e)
            throw e
        }
        val tail = filter.finish()
        if (tail.isNotEmpty()) onToken?.invoke(tail)
        if (timedOut.get()) {
            val partial = Think.strip(raw)
            _translatorStatus.value = "翻译超时"
            AppLog.e("LLM", "翻译超时 after ${System.currentTimeMillis() - started}ms，已返回 ${partial.length}字")
            if (partial.isNotBlank()) return partial
            if (allowEmpty) return ""
            throw RuntimeException("翻译超时，请检查设备内存或改用 2bit 模型/API")
        }
        val clean = Think.strip(raw)
        if (clean.isBlank()) {
            if (allowEmpty) {
                AppLog.d("LLM", "草稿翻译为空（通常是被下一句取消） ${System.currentTimeMillis() - started}ms")
                return ""
            }
            val emptyMessage = if (s.llmMode == SettingsData.MODE_API) {
                "接口没有返回译文（请检查模型名、接口地址或服务端 thinking 设置）"
            } else {
                "翻译模型没有输出（可能内存不足）"
            }
            _translatorStatus.value = emptyMessage
            AppLog.e("LLM", "翻译输出为空 ${System.currentTimeMillis() - started}ms mode=${s.llmMode} text=${text.take(40)} ${AppLog.memory()}")
            throw RuntimeException(emptyMessage)
        }
        AppLog.d("LLM", "翻译完成 ${System.currentTimeMillis() - started}ms src=${text.length}字 dst=${clean.length}字")
        return clean
    }

    suspend fun analyze(
        system: String,
        prompt: String,
        maxTokens: Int = 360,
        temperature: Float = 0.3f,
        onToken: ((String) -> Unit)? = null,
    ): String? {
        val s = app.settings.value
        val filter = ThinkStreamFilter()
        val stream: ((String) -> Unit)? = if (onToken == null) null else { piece ->
            val clean = filter.feed(piece)
            if (clean.isNotEmpty()) onToken(clean)
        }
        val started = System.currentTimeMillis()
        // 平板 CPU 生成 400 字左右可能超过两分钟，本地模型给足时间；API 仍按网络超时处理。
        val timeoutMs = if (s.analysisMode == SettingsData.MODE_API) 120_000L else 240_000L
        val timedOut = AtomicBoolean(false)
        val activeLocal = AtomicReference<LocalLlm?>(null)
        val raw = try {
            kotlinx.coroutines.coroutineScope {
                val watchdog = launch {
                    kotlinx.coroutines.delay(timeoutMs)
                    timedOut.set(true)
                    AppLog.e("LLM", "助教生成超过 ${timeoutMs / 1000}s，正在中断")
                    if (s.analysisMode == SettingsData.MODE_API) {
                        apiAssistantCalls.forEach { runCatching { it.cancel() } }
                    } else {
                        (activeLocal.get() ?: assistant)?.cancel()
                    }
                }
                try {
                    if (s.analysisMode == SettingsData.MODE_API) {
                        val base = s.analysisBaseUrl.ifBlank { s.apiBaseUrl }
                        val model = s.analysisModel.ifBlank { s.apiModel }
                        val key = s.analysisKey.ifBlank { s.apiKey }
                        if (base.isBlank() || model.isBlank()) {
                            _assistantStatus.value = "助教 API 未配置"
                            null
                        } else {
                            val text = apiAnalyzeWithRetry(base, model, key, system, prompt, maxTokens, temperature, stream)
                            if (!text.isNullOrBlank()) _assistantStatus.value = "API · $model"
                            text
                        }
                    } else {
                        val llm = localAssistant() ?: throw RuntimeException(_assistantStatus.value)
                        activeLocal.set(llm)
                        assistantLock.withLock {
                            if (!llm.ensureLoaded()) {
                                throw RuntimeException("助教模型加载失败（文件损坏或内存不足）")
                            }
                            _assistantStatus.value = "${llm.label} 就绪"
                            llm.generate(system, prompt, maxTokens, temperature, stream)
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (timedOut.get()) {
                _assistantStatus.value = "助教生成超时"
                AppLog.e("LLM", "助教生成超时 ${System.currentTimeMillis() - started}ms")
                throw RuntimeException("助教生成超时，请检查设备内存或改用 API")
            }
            _assistantStatus.value = "助教失败：${e.message?.take(40) ?: "未知错误"}"
            AppLog.e("LLM", "助教生成失败 ${System.currentTimeMillis() - started}ms", e)
            throw e
        }
        val tail = filter.finish()
        if (tail.isNotEmpty()) onToken?.invoke(tail)
        if (timedOut.get()) {
            val partial = raw?.let { Think.strip(it) }.orEmpty()
            _assistantStatus.value = "助教生成超时"
            AppLog.e("LLM", "助教生成超时 ${System.currentTimeMillis() - started}ms，已生成 ${partial.length}字")
            if (partial.isNotBlank()) return partial
            throw RuntimeException("助教生成超时，请检查设备内存或改用 API")
        }
        AppLog.d("LLM", "助教生成完成 ${System.currentTimeMillis() - started}ms out=${raw?.length ?: 0}字")
        return raw?.let { Think.strip(it) }
    }

    suspend fun correct(text: String, context: String): String? {
        if (text.isBlank()) return null
        val s = app.settings.value
        val user = "上下文：${context.ifBlank { "（无）" }}\n\n识别文本：$text"
        val raw = try {
            if (s.llmMode == SettingsData.MODE_API) {
                if (s.apiBaseUrl.isBlank() || s.apiModel.isBlank()) return null
                val api = ApiLlm(s.apiBaseUrl, s.apiModel, s.apiKey)
                apiTranslatorCalls.add(api)
                try {
                    withTimeout(30_000) { api.generate(Prompts.CORRECT, user, 200, 0.1f) }
                } finally {
                    apiTranslatorCalls.remove(api)
                }
            } else {
                val llm = localTranslator() ?: return null
                translatorLock.withLock {
                    if (!llm.ensureLoaded()) return null
                    withTimeout(30_000) { llm.generate(Prompts.CORRECT, user, 200, 0.1f) }
                }
            }
        } catch (_: Throwable) {
            return null
        }
        val fixed = Think.strip(raw).trim('"', '“', '”', '「', '」', '\n', ' ')
        if (fixed.isBlank() || fixed == text) return text
        val cjk = fixed.count { it in '一'..'鿿' }
        val textCjk = text.count { it in '一'..'鿿' }
        if (textCjk >= 2) {
            if (fixed.length != text.length) return null
            if (cjk < textCjk * 0.7) return null
            var diff = 0
            for (i in text.indices) if (text[i] != fixed[i]) diff++
            if (diff > max(2, text.length / 4)) return null
        } else {
            if (fixed.length > text.length * 2 || fixed.length < text.length / 2) return null
        }
        return fixed
    }

    @Synchronized
    fun unloadTranslator() {
        val old = translator
        translator = null
        translatorPath = ""
        translatorThreads = 0
        _translatorStatus.value = "未加载"
        old?.let { closeInBackground(it) }
    }

    @Synchronized
    fun unloadAssistant() {
        val old = assistant
        assistant = null
        assistantPath = ""
        assistantThreads = 0
        _assistantStatus.value = "未加载"
        old?.let { closeInBackground(it) }
    }

    fun cancelTranslation() {
        translator?.cancel()
        apiTranslatorCalls.forEach { runCatching { it.cancel() } }
    }

    fun cancelAssistant() {
        assistant?.cancel()
        apiAssistantCalls.forEach { runCatching { it.cancel() } }
    }

    @Synchronized
    fun unloadAll() {
        val t = translator
        val a = assistant
        translator = null
        assistant = null
        translatorPath = ""
        assistantPath = ""
        translatorThreads = 0
        assistantThreads = 0
        _translatorStatus.value = "未加载"
        _assistantStatus.value = "未加载"
        t?.let { closeInBackground(it) }
        a?.let { closeInBackground(it) }
    }
}
