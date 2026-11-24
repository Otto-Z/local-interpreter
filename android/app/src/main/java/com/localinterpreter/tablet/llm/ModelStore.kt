package com.localinterpreter.tablet.llm

import android.content.Context
import android.os.StatFs
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ModelKind(val id: String, val title: String, val detail: String, val approxBytes: Long) {
    SENSEVOICE("sensevoice", "SenseVoice 识别模型", "中/英/日/韩/粤语，自带标点，约 240MB", 239_000_000),
    TRANSLATOR("translator", "翻译模型 Hy-MT2-1.8B", "本地流式翻译，完全离线，约 1.1GB", 1_133_000_000),
    TRANSLATOR_LITE("translator_lite", "翻译模型 三元 QAT 1.25bit", "腾讯混元官方三元量化 + QAT（STQ1_0），仅 440MB，提速省内存", 461_860_800),
    ASSISTANT("assistant", "助教模型 Qwen3-4B", "总结、问答、术语提取，约 2.5GB（可选）", 2_497_000_000),
    ASSISTANT_LITE("assistant_lite", "助教模型 轻量 Qwen2.5-1.5B", "更省内存，约 1.1GB（可选）", 1_117_000_000),
}

data class DownloadProgress(
    val state: String = "idle", // idle | downloading | done | error
    val downloaded: Long = 0,
    val total: Long = 0,
    val message: String = "",
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

class ModelStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val root: File = File(context.filesDir, "models").apply { mkdirs() }
    private val senseDir = File(root, "sensevoice").apply { mkdirs() }
    private val translatorDir = File(root, "translator").apply { mkdirs() }
    private val assistantDir = File(root, "assistant").apply { mkdirs() }
    private val vadDir = File(root, "vad").apply { mkdirs() }
    private val speakerDir = File(root, "speaker").apply { mkdirs() }

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()
    private data class ReadyCache(val size: Long, val tokensSize: Long, val ready: Boolean)
    private val readyCache = ConcurrentHashMap<String, ReadyCache>()

    val sileroVad: File = File(vadDir, "silero_vad.onnx")
    val speakerModel: File = File(speakerDir, "speaker_campplus.onnx")

    init {
        scope.launch { ensureBundled() }
    }

    @Synchronized
    fun ensureBundled() {
        copyAsset("silero_vad.onnx", sileroVad)
        copyAsset("speaker_campplus.onnx", speakerModel)
        // 清理已废弃的旧版 2bit 模型文件及其断点残留，避免长期占用 600MB 空间。
        for (oldName in listOf("hy-mt2-1.8b-2bit.gguf", "hy-mt2-1.8b-2bit-q2_0c.gguf")) {
            val legacy = File(translatorDir, oldName)
            if (legacy.exists()) {
                AppLog.d("Model", "删除旧版 2bit 模型 $oldName (${legacy.length() / 1_048_576}MB)")
                legacy.delete()
            }
            File(translatorDir, "$oldName.part").delete()
            File(translatorDir, "$oldName.part.tmp").delete()
        }
    }

    private fun copyAsset(name: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        try {
            context.assets.open(name).use { input ->
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                tmp.outputStream().use { out -> input.copyTo(out) }
                tmp.renameTo(dest)
            }
        } catch (t: Exception) {
            AppLog.e("Model", "复制内置资源失败 $name", t)
        }
    }

    fun senseVoiceModel(): File = File(senseDir, "model.int8.onnx")
    fun senseVoiceTokens(): File = File(senseDir, "tokens.txt")

    fun translatorModel(variant: String): File =
        File(translatorDir, if (variant == "q2") "hy-mt2-1.8b-1.25bit-stq1_0.gguf" else "hy-mt2-1.8b-q4.gguf")

    fun assistantModel(variant: String = "4b"): File =
        File(assistantDir, if (variant == "1.5b") "qwen2.5-1.5b-instruct-q4.gguf" else "qwen3-4b-instruct-2507-q4.gguf")

    fun assistantKind(variant: String = "4b"): ModelKind =
        if (variant == "1.5b") ModelKind.ASSISTANT_LITE else ModelKind.ASSISTANT

    fun kindFor(variant: String): ModelKind =
        if (variant == "q2") ModelKind.TRANSLATOR_LITE else ModelKind.TRANSLATOR

    fun fileFor(kind: ModelKind): File = when (kind) {
        ModelKind.SENSEVOICE -> senseVoiceModel()
        ModelKind.TRANSLATOR -> translatorModel("q4")
        ModelKind.TRANSLATOR_LITE -> translatorModel("q2")
        ModelKind.ASSISTANT -> assistantModel("4b")
        ModelKind.ASSISTANT_LITE -> assistantModel("1.5b")
    }

    fun isReady(kind: ModelKind): Boolean {
        val key = kind.id
        val f = if (kind == ModelKind.SENSEVOICE) senseVoiceModel() else fileFor(kind)
        val size = if (f.exists()) f.length() else 0L
        val tokensSize = if (kind == ModelKind.SENSEVOICE) senseVoiceTokens().length() else 0L
        readyCache[key]?.let { if (it.size == size && it.tokensSize == tokensSize) return it.ready }
        val ready = when (kind) {
            ModelKind.SENSEVOICE -> size >= MIN_SENSEVOICE && tokensSize > 0
            else -> size >= minBytes(kind) && hasGgufMagic(f)
        }
        readyCache[key] = ReadyCache(size, tokensSize, ready)
        return ready
    }

    fun fileProblem(kind: ModelKind): String? {
        val f = if (kind == ModelKind.SENSEVOICE) senseVoiceModel() else fileFor(kind)
        if (!f.exists()) return "未下载"
        if (f.length() < minBytes(kind)) {
            return "文件不完整：%.0fMB / 约 %.0fMB".format(
                f.length() / 1_048_576.0,
                minBytes(kind) / 1_048_576.0,
            )
        }
        if (kind != ModelKind.SENSEVOICE && !hasGgufMagic(f)) return "不是有效的 GGUF 文件"
        if (kind == ModelKind.SENSEVOICE && senseVoiceTokens().length() == 0L) return "缺少 tokens.txt"
        return null
    }

    fun translatorReady(variant: String): Boolean = isReady(kindFor(variant))
    fun assistantReady(variant: String = "4b"): Boolean = isReady(assistantKind(variant))
    fun asrReady(): Boolean = isReady(ModelKind.SENSEVOICE)

    private fun minBytes(kind: ModelKind): Long = when (kind) {
        ModelKind.SENSEVOICE -> MIN_SENSEVOICE
        ModelKind.TRANSLATOR -> 1_050_000_000L
        ModelKind.TRANSLATOR_LITE -> 450_000_000L
        ModelKind.ASSISTANT -> 2_300_000_000L
        ModelKind.ASSISTANT_LITE -> 1_050_000_000L
    }

    private fun hasGgufMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        return try {
            val buf = ByteArray(4)
            file.inputStream().use { it.read(buf) }
            String(buf, Charsets.US_ASCII) == "GGUF"
        } catch (_: Exception) {
            false
        }
    }

    fun state(kind: ModelKind): DownloadProgress {
        progress.value[kind.id]?.let { return it }
        return if (isReady(kind)) DownloadProgress("done", fileFor(kind).length(), fileFor(kind).length())
        else DownloadProgress()
    }

    @Synchronized
    fun download(kind: ModelKind, mirror: Boolean) {
        if (jobs[kind.id]?.isActive == true) return
        jobs[kind.id] = scope.launch {
            try {
                AppLog.d("Model", "开始下载 ${kind.id} mirror=$mirror ${AppLog.memory()}")
                val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
                val partialBytes = when (kind) {
                    ModelKind.SENSEVOICE -> File(senseDir, "model.int8.onnx.part.tmp").length()
                    else -> File(fileFor(kind).parentFile, fileFor(kind).name + ".part.tmp").length()
                }
                val remaining = (kind.approxBytes - partialBytes).coerceAtLeast(0L)
                if (free < remaining + 150L * 1024 * 1024) {
                    throw RuntimeException(
                        "存储空间不足：剩余 ${free / 1_048_576}MB，还需约 ${(remaining + 150L * 1024 * 1024) / 1_048_576}MB"
                    )
                }
                setState(kind, DownloadProgress("downloading", partialBytes, kind.approxBytes, "连接中…"))
                when (kind) {
                    ModelKind.SENSEVOICE -> {
                        val approx = 239_000_000L
                        downloadFile(
                            kind,
                            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                            "model.int8.onnx", File(senseDir, "model.int8.onnx.part"), mirror, approx
                        ) { done, total ->
                            setState(kind, DownloadProgress("downloading", done, total, "正在下载识别模型"))
                        }
                        downloadFile(
                            kind,
                            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                            "tokens.txt", File(senseDir, "tokens.txt.part"), mirror, 0
                        ) { _, _ -> }
                        val modelPart = File(senseDir, "model.int8.onnx.part")
                        if (modelPart.length() < MIN_SENSEVOICE) {
                            throw RuntimeException("识别模型下载不完整，已保留断点，请重新下载")
                        }
                        val tokenPart = File(senseDir, "tokens.txt.part")
                        if (tokenPart.length() == 0L) throw RuntimeException("识别词表下载不完整")
                        if (!modelPart.renameTo(senseVoiceModel())) throw RuntimeException("保存识别模型失败")
                        if (!tokenPart.renameTo(senseVoiceTokens())) throw RuntimeException("保存识别词表失败")
                    }
                    else -> {
                        val file = fileFor(kind)
                        val part = File(file.parentFile, file.name + ".part")
                        val repo = when (kind) {
                            ModelKind.TRANSLATOR -> "tencent/Hy-MT2-1.8B-GGUF"
                            ModelKind.TRANSLATOR_LITE -> "tencent/Hy-MT2-1.8B-1.25Bit-GGUF"
                            ModelKind.ASSISTANT -> "unsloth/Qwen3-4B-Instruct-2507-GGUF"
                            ModelKind.ASSISTANT_LITE -> "Qwen/Qwen2.5-1.5B-Instruct-GGUF"
                            ModelKind.SENSEVOICE -> ""
                        }
                        val remote = when (kind) {
                            ModelKind.TRANSLATOR -> "Hy-MT2-1.8B-Q4_K_M.gguf"
                            ModelKind.TRANSLATOR_LITE -> "Hy-MT2-1.8B-1.25Bit.gguf"
                            ModelKind.ASSISTANT -> "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
                            ModelKind.ASSISTANT_LITE -> "qwen2.5-1.5b-instruct-q4_k_m.gguf"
                            ModelKind.SENSEVOICE -> ""
                        }
                        downloadFile(kind, repo, remote, part, mirror, kind.approxBytes) { done, total ->
                            setState(kind, DownloadProgress("downloading", done, total, "正在下载 ${kind.title}"))
                        }
                        if (part.length() < minBytes(kind) || !hasGgufMagic(part)) {
                            throw RuntimeException("模型文件不完整或格式不正确，已保留断点，请重新下载")
                        }
                        if (!part.renameTo(file)) throw RuntimeException("保存模型文件失败，请检查存储空间")
                    }
                }
                readyCache.remove(kind.id)
                setState(kind, DownloadProgress("done", fileFor(kind).length(), fileFor(kind).length(), "已就绪"))
                AppLog.d("Model", "下载完成 ${kind.id} size=${fileFor(kind).length() / 1_048_576}MB")
            } catch (e: kotlinx.coroutines.CancellationException) {
                setState(kind, DownloadProgress("idle"))
            } catch (e: Exception) {
                if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    AppLog.e("Model", "下载失败 ${kind.id}", e)
                    setState(kind, DownloadProgress("error", 0, 0, e.message ?: "下载失败"))
                } else {
                    setState(kind, DownloadProgress("idle"))
                }
            } finally {
                calls.remove(kind.id)
                jobs.remove(kind.id, currentCoroutineContext()[Job])
            }
        }
    }

    @Synchronized
    fun cancel(kind: ModelKind) {
        calls.remove(kind.id)?.cancel()
        jobs[kind.id]?.cancel()
        setState(kind, DownloadProgress("idle"))
    }

    @Synchronized
    fun delete(kind: ModelKind) {
        readyCache.remove(kind.id)
        calls.remove(kind.id)?.cancel()
        jobs.remove(kind.id)?.cancel()
        when (kind) {
            ModelKind.SENSEVOICE -> {
                senseVoiceModel().delete()
                senseVoiceTokens().delete()
                File(senseDir, "model.int8.onnx.part").delete()
                File(senseDir, "tokens.txt.part").delete()
                File(senseDir, "model.int8.onnx.part.tmp").delete()
                File(senseDir, "tokens.txt.part.tmp").delete()
                File(senseDir, "model.int8.onnx.part.tmp.etag").delete()
                File(senseDir, "tokens.txt.part.tmp.etag").delete()
            }
            else -> {
                val f = fileFor(kind)
                f.delete()
                File(f.parentFile, f.name + ".part").delete()
                File(f.parentFile, f.name + ".part.tmp").delete()
                File(f.parentFile, f.name + ".part.tmp.etag").delete()
            }
        }
        setState(kind, DownloadProgress())
    }

    private fun setState(kind: ModelKind, p: DownloadProgress) {
        _progress.update { it + (kind.id to p) }
    }

    /** 只取响应头里的 ETag（回退 Last-Modified）；失败返回 null，调用方按「无法校验」处理（仍可续传）。 */
    private fun headVersionStamp(kind: ModelKind, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "LocalInterpreterTablet/1.0")
            .build()
        val call = client.newCall(request)
        calls[kind.id] = call
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.header("ETag")?.takeIf { it.isNotBlank() }
                    ?: resp.header("Last-Modified")?.takeIf { it.isNotBlank() }
            }
        } finally {
            calls.remove(kind.id, call)
        }
    }

    private suspend fun downloadFile(
        kind: ModelKind,
        repo: String,
        name: String,
        dest: File,
        mirror: Boolean,
        expectedTotal: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val hosts = if (mirror) listOf("https://hf-mirror.com", "https://huggingface.co")
        else listOf("https://huggingface.co")
        var lastError: Exception? = null
        var restarted = false
        var hostIndex = 0
        while (hostIndex < hosts.size) {
            val host = hosts[hostIndex]
            hostIndex++
            try {
                val url = "$host/$repo/resolve/main/$name?download=true"
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                val stamp = File(dest.parentFile, dest.name + ".tmp.etag")
                tmp.parentFile?.mkdirs()
                var existing = if (tmp.exists()) tmp.length() else 0L
                // 续传前校验远端版本：上游换了文件还接着旧断点拼，会得到「能下完但加载失败」的模型。
                val savedEtag = if (existing > 0) runCatching { stamp.readText() }.getOrNull().orEmpty() else ""
                if (existing > 0 && savedEtag.isNotBlank()) {
                    val remoteEtag = runCatching { headVersionStamp(kind, url) }.getOrNull().orEmpty()
                    if (remoteEtag.isNotBlank() && remoteEtag != savedEtag) {
                        AppLog.d("Model", "远端模型已更新，丢弃旧断点 ${tmp.length() / 1_048_576}MB $name")
                        tmp.delete()
                        stamp.delete()
                        existing = 0L
                    }
                }
                val builder = Request.Builder().url(url).header("User-Agent", "LocalInterpreterTablet/1.0")
                if (existing > 0) builder.header("Range", "bytes=$existing-")
                val call = client.newCall(builder.build())
                calls[kind.id] = call
                call.execute().use { resp ->
                    if (resp.code == 416 && existing > 0) {
                        // 416 只说明临时文件已经不小于远端文件。只有长度正好相等才可信；
                        // 用硬编码的近似大小判断会把完整文件判成残缺，导致永久卡在 416。
                        val remoteTotal = Regex("bytes \\*/(\\d+)")
                            .find(resp.header("Content-Range") ?: "")
                            ?.groupValues?.getOrNull(1)?.toLongOrNull()
                        val complete = remoteTotal != null && existing == remoteTotal
                        if (complete) {
                            if (name.endsWith(".gguf", ignoreCase = true) && !hasGgufMagic(tmp)) {
                                tmp.delete()
                                stamp.delete()
                                throw RuntimeException("断点文件不是有效的 GGUF，已清理请重试")
                            }
                            if (!tmp.renameTo(dest)) {
                                throw RuntimeException("保存下载文件失败，请检查存储空间")
                            }
                            stamp.delete()
                            calls.remove(kind.id)
                            return
                        }
                        tmp.delete()
                        stamp.delete()
                        // 丢掉坏断点，从 0 重下同一个源（只重来一次，避免死循环）。
                        if (!restarted) {
                            restarted = true
                            hostIndex--
                            throw RuntimeException("续传状态失效，已从零重新下载")
                        }
                        throw RuntimeException("HTTP 416")
                    }
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    if (resp.code == 200) {
                        // 全量重下：旧断点已经没用了，清掉它，避免和这次内容拼在一起。
                        if (tmp.exists() && existing > 0) {
                            AppLog.d("Model", "服务器返回完整文件，重置断点 $name")
                            tmp.delete()
                        }
                        existing = 0L
                    }
                    val etag = resp.header("ETag")?.takeIf { it.isNotBlank() }
                        ?: resp.header("Last-Modified").orEmpty()
                    if (etag.isNotBlank()) runCatching { stamp.writeText(etag) }
                    val append = existing > 0 && resp.code == 206
                    val contentRange = resp.header("Content-Range")
                    if (append && contentRange != null && !contentRange.startsWith("bytes $existing-")) {
                        // 续传偏移不符：清理损坏的断点文件并从头重下，而不是保留坏断点反复失败。
                        tmp.delete()
                        stamp.delete()
                        if (!restarted) {
                            restarted = true
                            hostIndex--
                        }
                        throw RuntimeException("服务器续传位置不正确，已清理断点重试")
                    }
                    val contentLength = resp.body?.contentLength() ?: -1
                    val total = if (contentLength > 0) {
                        contentLength + (if (append) existing else 0)
                    } else expectedTotal
                    if (total > 0) onProgress(if (append) existing else 0L, total)
                    resp.body?.byteStream()?.use { input ->
                        (if (append) tmp.appendStream() else tmp.outputStream()).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var done = if (append) existing else 0L
                            var lastReport = done
                            while (true) {
                                if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("cancelled")
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastReport > 512 * 1024) {
                                    lastReport = done
                                    onProgress(done, if (total > 0) maxOf(total, done) else expectedTotal)
                                }
                            }
                            if (contentLength > 0 && done < total) {
                                throw RuntimeException("下载不完整（${done}/${total}），已保留断点，请重新下载")
                            }
                            onProgress(done, if (total > 0) maxOf(total, done) else done)
                        }
                    }
                    // 镜像有时会用 200 返回错误网页；先验证 GGUF 头，别把 HTML 当成模型保存。
                    if (name.endsWith(".gguf", ignoreCase = true) && !hasGgufMagic(tmp)) {
                        tmp.delete()
                        stamp.delete()
                        throw RuntimeException("下载内容不是有效的 GGUF（镜像可能返回了错误页面）")
                    }
                    if (!tmp.renameTo(dest)) throw RuntimeException("保存下载文件失败，请检查存储空间")
                    stamp.delete()
                }
                calls.remove(kind.id)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("cancelled")
                }
                lastError = e
                calls.remove(kind.id)
            }
        }
        throw lastError ?: RuntimeException("下载失败")
    }
}

private fun File.appendStream(): FileOutputStream = FileOutputStream(this, true)

private const val MIN_SENSEVOICE = 220_000_000L
