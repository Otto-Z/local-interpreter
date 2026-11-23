package com.localinterpreter.tablet.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class AsrResult(
    val text: String,
    val lang: String,
    val event: String = "",
) {
    companion object {
        val EMPTY = AsrResult("", "", "")
    }
}

class AsrEngine {
    private val _status = MutableStateFlow("语音模型未加载")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedLanguage: String = ""
    @Volatile private var loadedThreads: Int = 0
    @Volatile private var loading = false
    private val lock = Any()
    /** 正在等待或执行最终识别的任务数：存在时预览识别立即让路，避免拖慢最终字幕。 */
    private val finalWaiting = AtomicInteger(0)

    suspend fun ensureLoaded(language: String): Boolean = withContext(Dispatchers.IO) {
        val app = App.instance
        val model = app.models.senseVoiceModel()
        val tokens = app.models.senseVoiceTokens()
        if (!model.exists() || !tokens.exists()) {
            _status.value = "识别模型未下载"
            return@withContext false
        }
        val lang = if (language == "auto") "auto" else language
        val threads = app.settings.value.asrThreads
        recognizer?.let {
            if (loadedLanguage == lang && loadedThreads == threads) return@withContext true
        }
        // 同一时间只允许一个加载者；其他调用等待它完成，而不是直接返回失败。
        val deadline = System.currentTimeMillis() + 30_000
        var owner = false
        while (!owner) {
            synchronized(lock) {
                if (recognizer != null && loadedLanguage == lang && loadedThreads == threads) {
                    return@withContext true
                }
                if (!loading) {
                    loading = true
                    owner = true
                }
            }
            if (!owner) {
                if (System.currentTimeMillis() > deadline) return@withContext false
                kotlinx.coroutines.delay(50)
            }
        }
        val started = System.currentTimeMillis()
        AppLog.d("ASR", "开始加载 SenseVoice lang=$lang threads=$threads ${AppLog.memory()}")
        try {
            _status.value = "识别模型加载中…"
            // 先释放旧实例再加载新模型，避免切换语言/线程时内存瞬时翻倍（~480MB）。
            synchronized(lock) {
                val old = recognizer
                recognizer = null
                loadedLanguage = ""
                loadedThreads = 0
                runCatching { old?.release() }
            }
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = model.absolutePath,
                        language = lang,
                        useInverseTextNormalization = true,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = threads,
                    provider = "cpu",
                    debug = false,
                )
            )
            val rec = OfflineRecognizer(null, config)
            synchronized(lock) {
                recognizer = rec
                loadedLanguage = lang
                loadedThreads = threads
            }
            _status.value = "SenseVoice 就绪"
            AppLog.d("ASR", "加载成功 ${System.currentTimeMillis() - started}ms")
            true
        } catch (e: Throwable) {
            _status.value = "识别模型加载失败：${e.message?.take(60) ?: ""}"
            AppLog.e("ASR", "加载失败 ${System.currentTimeMillis() - started}ms", e)
            false
        } finally {
            synchronized(lock) { loading = false }
        }
    }

    suspend fun transcribe(samples: FloatArray, language: String): Pair<String, String> {
        val res = transcribeDetailed(samples, language, isPartial = false)
        return res.text to res.lang
    }

    suspend fun transcribeDetailed(
        samples: FloatArray,
        language: String,
        isPartial: Boolean = false,
    ): AsrResult = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext AsrResult.EMPTY
        if (isPartial && finalWaiting.get() > 0) return@withContext AsrResult.EMPTY
        if (!ensureLoaded(language)) return@withContext AsrResult.EMPTY
        if (!isPartial) finalWaiting.incrementAndGet()
        try {
            synchronized(lock) {
                if (isPartial && finalWaiting.get() > 0) return@withContext AsrResult.EMPTY
                val rec = recognizer ?: return@withContext AsrResult.EMPTY
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val cleanText = SPECIAL_TAG_REGEX.replace(result.text, "").trim()
                    val rawLang = result.lang.trim('<', '|', '>', ' ')
                    val rawEvent = result.event.trim('<', '|', '>', ' ')
                    AsrResult(
                        text = cleanText,
                        lang = rawLang.ifBlank { if (language != "auto") language else "" },
                        event = rawEvent,
                    )
                } finally {
                    stream.release()
                }
            }
        } finally {
            if (!isPartial) finalWaiting.decrementAndGet()
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { recognizer?.release() }
            recognizer = null
            loadedLanguage = ""
            loadedThreads = 0
        }
        _status.value = "语音模型未加载"
    }

    companion object {
        private val SPECIAL_TAG_REGEX = Regex("<\\|[^|<>]*\\|>")
    }
}

data class VadChunk(val startSamples: Long, val samples: FloatArray)

class VadSegmenter(
    pauseMs: Int,
    maxSpeech: Float = 14f,
    threshold: Float = 0.45f,
    minSpeech: Float = 0.28f,
) {
    val vad: Vad
    val windowSize: Int
    private val config: VadModelConfig

    init {
        val model = App.instance.models.sileroVad
        App.instance.models.ensureBundled()
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.absolutePath,
                // 麦克风默认 0.45 / 0.28s 兼顾远场人声与环境杂音抑制；数字内录/文件可传更低阈值。
                threshold = threshold.coerceIn(0.25f, 0.75f),
                minSilenceDuration = (pauseMs / 1000f).coerceIn(0.2f, 1.2f),
                minSpeechDuration = minSpeech.coerceIn(0.15f, 0.6f),
                windowSize = 512,
                maxSpeechDuration = maxSpeech,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        vad = Vad(null, config)
        windowSize = config.sileroVadModelConfig.windowSize
    }

    fun accept(frame: FloatArray) {
        vad.acceptWaveform(frame)
    }

    fun isSpeech(): Boolean = vad.isSpeechDetected()

    fun nextSegment(): VadChunk? {
        if (vad.empty()) return null
        val seg = vad.front()
        vad.pop()
        return VadChunk(seg.start, seg.samples)
    }

    fun flush() {
        vad.flush()
    }

    fun release() {
        vad.release()
    }
}

class SpeakerEngine {
    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var failed = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (extractor != null) return true
        if (failed) return false
        App.instance.models.ensureBundled()
        val model = App.instance.models.speakerModel
        if (!model.exists()) return false
        return try {
            // 声纹属于非关键路径后处理，限制为 1 线程，避免与 SenseVoice / Hy-MT2 争抢大核。
            extractor = SpeakerEmbeddingExtractor(
                null,
                SpeakerEmbeddingExtractorConfig(model = model.absolutePath, numThreads = 1),
            )
            true
        } catch (_: Throwable) {
            failed = true
            false
        }
    }

    /** 内存紧张时释放声纹模型（约 28MB），下次用到会重新加载。 */
    @Synchronized
    fun release() {
        runCatching { extractor?.release() }
        extractor = null
        failed = false
    }

    @Synchronized
    fun embed(samples: FloatArray): FloatArray? {
        if (!ensureLoaded()) return null
        val ex = extractor ?: return null
        return try {
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                stream.inputFinished()
                if (!ex.isReady(stream)) return null
                ex.compute(stream)
            } finally {
                stream.release()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
