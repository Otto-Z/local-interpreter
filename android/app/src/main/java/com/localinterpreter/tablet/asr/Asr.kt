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

class AsrEngine {
    private val _status = MutableStateFlow("语音模型未加载")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedLanguage: String = ""
    @Volatile private var loadedThreads: Int = 0
    @Volatile private var loading = false
    private val lock = Any()

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
            // 替换与释放都在锁内：transcribe 在锁内取 recognizer 并完成 decode，不会用到已释放的对象。
            synchronized(lock) {
                val old = recognizer
                recognizer = rec
                loadedLanguage = lang
                loadedThreads = threads
                runCatching { old?.release() }
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

    suspend fun transcribe(samples: FloatArray, language: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (!ensureLoaded(language)) return@withContext "" to ""
            synchronized(lock) {
                val rec = recognizer ?: return@withContext "" to ""
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val text = result.text.trim()
                    val rawLang = result.lang.trim('<', '|', '>', ' ')
                    text to (rawLang.ifBlank { if (language != "auto") language else "" })
                } finally {
                    stream.release()
                }
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
}

data class VadChunk(val startSamples: Long, val samples: FloatArray)

class VadSegmenter(pauseMs: Int, maxSpeech: Float = 14f) {
    val vad: Vad
    val windowSize: Int
    private val config: VadModelConfig

    init {
        val model = App.instance.models.sileroVad
        App.instance.models.ensureBundled()
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.absolutePath,
                // 教室远场/笔记本外放的音量偏低，0.5 容易整段检测不到；0.4 更稳。
                threshold = 0.35f,
                minSilenceDuration = (pauseMs / 1000f).coerceIn(0.2f, 1.2f),
                minSpeechDuration = 0.22f,
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
            extractor = SpeakerEmbeddingExtractor(
                null,
                SpeakerEmbeddingExtractorConfig(model = model.absolutePath, numThreads = 2),
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
