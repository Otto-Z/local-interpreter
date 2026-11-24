package com.localinterpreter.tablet.live

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.asr.AsrEngine
import com.localinterpreter.tablet.asr.SpeakerEngine
import com.localinterpreter.tablet.asr.VadChunk
import com.localinterpreter.tablet.asr.VadSegmenter
import com.localinterpreter.tablet.assistant.CourseAssistant
import com.localinterpreter.tablet.util.AppLog
import com.localinterpreter.tablet.audio.AudioCapture
import com.localinterpreter.tablet.audio.FileAudioCapture
import com.localinterpreter.tablet.audio.MicCapture
import com.localinterpreter.tablet.audio.SystemAudioCapture
import com.localinterpreter.tablet.audio.WavUtil
import com.localinterpreter.tablet.data.Database
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.llm.Prompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

enum class LiveSource(val label: String, val id: String) {
    MIC("麦克风", "mic"),
    SYSTEM("系统音频", "system"),
    FILE("音频文件", "file"),
}

data class LiveSegment(
    val id: Long,
    val t0: Double,
    val t1: Double,
    val srcLang: String,
    val src: String,
    val tgt: String,
    val speaker: String? = null,
    val speakerColor: String? = null,
    val lowConfidence: Boolean = false,
    val audioPath: String? = null,
    val corrected: Boolean = false,
    val speakerId: Long? = null,
    val draftTgt: Boolean = false,
    val bookmarked: Boolean = false,
)

data class LiveQuestion(
    val id: Long,
    val dbId: Long? = null,
    val question: String,
    val questionTgt: String,
    val answer: String,
    val answerAlt: String = "",
    val source: String = "",
    val pending: Boolean = true,
)

data class LiveState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val paused: Boolean = false,
    val sessionId: Long? = null,
    val courseId: Long? = null,
    val source: LiveSource = LiveSource.MIC,
    val segments: List<LiveSegment> = emptyList(),
    val partialSrc: String = "",
    val partialTgt: String = "",
    val partialDraft: Boolean = false,
    val summary: String = "",
    val questions: List<LiveQuestion> = emptyList(),
    val status: String = "",
    val error: String? = null,
    val startedAt: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastAsrMs: Long = 0L,
    val audioLevel: Float = 0f,
    val speaking: Boolean = false,
    val assistantBusy: Boolean = false,
    val pendingQueue: Int = 0,
)

class LiveController(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()
    val asrStatus: StateFlow<String> get() = asr.status

    private data class NoiseProfile(
        val vadThreshold: Float,
        val vadMinSpeech: Float,
        val speechFloor: Float,
        val speechMult: Float,
        val segFloor: Float,
        val segMult: Float,
        val partialFloor: Float,
        val partialMult: Float,
        val hallucinationEnergy: Float,
    )

    private fun noiseProfile(): NoiseProfile = when (app.settings.value.noiseSuppression) {
        SettingsData.NOISE_SENSITIVE -> NoiseProfile(
            vadThreshold = 0.36f, vadMinSpeech = 0.22f,
            speechFloor = 0.0026f, speechMult = 1.6f,
            segFloor = 0.0024f, segMult = 1.4f,
            partialFloor = 0.0028f, partialMult = 1.6f,
            hallucinationEnergy = 0.010f,
        )
        SettingsData.NOISE_STRICT -> NoiseProfile(
            vadThreshold = 0.56f, vadMinSpeech = 0.34f,
            speechFloor = 0.0075f, speechMult = 2.6f,
            segFloor = 0.0068f, segMult = 2.2f,
            partialFloor = 0.0080f, partialMult = 2.5f,
            hallucinationEnergy = 0.022f,
        )
        else -> NoiseProfile(
            vadThreshold = 0.45f, vadMinSpeech = 0.28f,
            speechFloor = 0.0042f, speechMult = 2.0f,
            segFloor = 0.0038f, segMult = 1.6f,
            partialFloor = 0.0045f, partialMult = 1.9f,
            hallucinationEnergy = 0.015f,
        )
    }

    private fun refreshPendingQueue() {
        val total = (finalPending.get() + translatePending.get()).coerceAtLeast(0)
        if (_state.value.pendingQueue != total) {
            _state.update { if (it.pendingQueue == total) it else it.copy(pendingQueue = total) }
        }
    }

    private val asr = AsrEngine()
    private val speakerEngine = SpeakerEngine()
    @Volatile private var vad: VadSegmenter? = null
    @Volatile private var capture: AudioCapture? = null
    @Volatile private var routeMonitor: com.localinterpreter.tablet.audio.AudioRouteMonitor? = null
    @Volatile private var sessionJob: Job? = null
    @Volatile private var startJob: Job? = null
    @Volatile private var audioChannel: Channel<FloatArray>? = null

    private val finalChannel = Channel<PendingFinal>(Channel.UNLIMITED)
    private val translateChannel = Channel<TranslateJob>(Channel.UNLIMITED)
    private val questionChannel = Channel<QuestionJob>(Channel.UNLIMITED)
    private val diarizeChannel = Channel<DiarizeJob>(Channel.UNLIMITED)
    private val draftChannel = Channel<DraftJob>(Channel.CONFLATED)
    private var workersStarted = false
    private val finalPending = AtomicInteger(0)
    private val translatePending = AtomicInteger(0)
    private val nextTempId = java.util.concurrent.atomic.AtomicLong(-1L)
    private val analysisMutex = Mutex()
    private val speakerMutex = Mutex()
    /** 串行化「开始 / 恢复」的状态占位，避免恢复课次和用户点开始时互相覆盖。 */
    private val sessionStartLock = Any()

    @Volatile private var segmentIndex = 0
    @Volatile private var draftGeneration = 0
    private val backgroundTasks = AtomicInteger(0)
    /** 只有草稿在翻译时才为 true：正式译文到达时只该取消草稿，不能打断校正和答案翻译。 */
    private val draftInFlight = AtomicInteger(0)
    @Volatile private var translationEpoch = 0
    @Volatile private var finalTranslating = false
    @Volatile private var stopping = false
    @Volatile private var startToken = 0
    @Volatile private var partialInFlight = false
    @Volatile private var bufferEpoch = 0
    @Volatile private var draftSuppressed = false
    @Volatile private var lastAudioAt = 0L
    /** 测速开关：只在「设置 → 诊断」打开或启动参数带 lat=1 时打印 Lat 行，平时不增加开销。 */
    @Volatile private var latProbe = false
    @Volatile private var emptyFinals = 0
    @Volatile private var silenceWarned = false
    @Volatile private var lastPartialAt = 0L
    @Volatile private var lastDraftAt = 0L
    @Volatile private var lastSummaryAt = 0L
    @Volatile private var summaryIndex = 0
    @Volatile private var lastPartialText = ""
    private val askedQuestions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val speakers = java.util.concurrent.CopyOnWriteArrayList<LiveSpeaker>()
    @Volatile private var glossaryText = ""
    @Volatile private var courseId: Long? = null
    @Volatile private var sourceLangPref = "auto"
    @Volatile private var targetLangPref = "auto"

    private data class LiveSpeaker(val id: Long, val label: String, val color: String, val embedding: FloatArray)
    private data class DiarizeJob(val sessionId: Long, val segmentId: Long, val samples: FloatArray)
    private data class PendingFinal(
        val sessionId: Long,
        val samples: FloatArray,
        val t0: Double,
        val t1: Double,
        val endedAtMs: Long,
        val fallbackText: String,
        val sourceLang: String,
        val targetLang: String,
        val glossary: String,
        val courseId: Long?,
        val provisionalId: Long? = null,
        /** 测速用：分句结束时的挂钟毫秒；0 表示不测。 */
        val vadEndWallMs: Long = 0L,
    )
    private data class TranslateJob(
        val sessionId: Long,
        val segmentId: Long,
        val text: String,
        val srcLang: String,
        val finalizedAt: Long,
        val targetLang: String,
        val glossary: String,
        val courseId: Long?,
        /** 入队时的翻译配置；排队期间用户切换内置/API 不应影响已经排队的句子。 */
        val settings: SettingsData,
        val vadEndWallMs: Long = 0L,
        val asrDoneWallMs: Long = 0L,
    )
    private data class QuestionJob(
        val localId: Long,
        val sessionId: Long,
        val segmentId: Long?,
        val question: String,
        val questionTgt: String,
        val manual: Boolean,
        val courseId: Long?,
    )
    private data class DraftJob(val text: String, val srcLang: String, val generation: Int)

    fun start(
        context: Context,
        source: LiveSource,
        courseId: Long?,
        projection: MediaProjection? = null,
        fileUri: android.net.Uri? = null,
    ) {
        synchronized(sessionStartLock) {
            if (_state.value.running || _state.value.starting) return
            _state.value = LiveState(starting = true, source = source, courseId = courseId)
        }
        if (!app.models.asrReady()) {
            _state.value = LiveState(
                starting = false,
                source = source,
                error = "识别模型未下载。请到「设置 → 模型管理」下载 SenseVoice。",
            )
            return
        }
        startWorkers()
        val settings = app.settings.value
        latProbe = settings.latencyProbe
        this.courseId = courseId
        sourceLangPref = settings.sourceLang
        targetLangPref = settings.targetLang
        glossaryText = ""
        AppLog.d(
            "Live",
            "开始同传 source=$source course=${courseId ?: "-"} src=${settings.sourceLang} tgt=${settings.targetLang} " +
                "mode=${settings.llmMode}/${settings.modelVariant} asrThreads=${settings.asrThreads} llmThreads=${settings.llmThreads} " +
                "pause=${settings.pauseMs} noise=${settings.noiseSuppression} ctx=${settings.translationContext} " +
                "draft=${settings.draftTranslate} diar=${settings.diarization} " +
                "sense=${app.models.senseVoiceModel().length() / 1_048_576}MB",
        )
        speakers.clear()
        segmentIndex = 0
        lastPartialAt = 0
        lastDraftAt = 0
        lastSummaryAt = 0
        summaryIndex = 0
        lastPartialText = ""
        askedQuestions.clear()
        draftGeneration++
        translationEpoch++
        partialInFlight = false
        bufferEpoch = 0
        draftSuppressed = false
        emptyFinals = 0
        silenceWarned = false
        lastAudioAt = SystemClock.elapsedRealtime()
        // 新会话开始：中止上一场可能还在跑的翻译/助教生成，避免占用模型锁和 CPU。
        app.llm.cancelTranslation()
        app.llm.cancelAssistant()

        val token = ++startToken
        startJob = scope.launch {
            var sessionId: Long? = null
            var localSegmenter: VadSegmenter? = null
            var localChannel: Channel<FloatArray>? = null
            var localCap: AudioCapture? = null
            try {
                val (sessionCourse, loadedGlossary) = withContext(Dispatchers.IO) {
                    val validCourse = courseId?.takeIf {
                        runCatching { app.db.getCourse(it) != null }.getOrDefault(false)
                    }
                    val gloss = if (validCourse != null) {
                        runCatching { app.db.glossaryPrompt(validCourse) }.getOrDefault("")
                    } else ""
                    validCourse to gloss
                }
                this@LiveController.courseId = sessionCourse
                glossaryText = loadedGlossary
                val ok = asr.ensureLoaded(sourceLangPref)
                if (!ok) {
                    _state.update { it.copy(starting = false, error = asr.status.value) }
                    return@launch
                }
                sessionId = withContext(Dispatchers.IO) {
                    app.db.createSession(sessionCourse, source.id, sourceLangPref, targetLangPref)
                }
                val np = noiseProfile()
                val segmenter = if (source == LiveSource.MIC) {
                    VadSegmenter(settings.pauseMs, threshold = np.vadThreshold, minSpeech = np.vadMinSpeech)
                } else {
                    VadSegmenter(settings.pauseMs, threshold = 0.38f, minSpeech = 0.24f)
                }
                localSegmenter = segmenter
                vad = segmenter
                val channel = Channel<FloatArray>(
                    if (source == LiveSource.FILE) 256 else Channel.BUFFERED
                )
                localChannel = channel
                audioChannel = channel
                val cap = when (source) {
                    LiveSource.MIC -> MicCapture(
                        context,
                        { frames -> lastAudioAt = SystemClock.elapsedRealtime(); channel.trySend(frames) },
                        { message ->
                            scope.launch {
                                if (token != startToken) return@launch
                                _state.update { it.copy(error = message) }
                                if (_state.value.running) stop()
                            }
                        },
                    )
                    LiveSource.SYSTEM -> {
                        val p = projection ?: throw RuntimeException("没有屏幕录制授权")
                        SystemAudioCapture(
                            context, p,
                            { frames -> lastAudioAt = SystemClock.elapsedRealtime(); channel.trySend(frames) },
                            {
                                scope.launch {
                                    if (token != startToken) return@launch
                                    if (_state.value.running) {
                                        _state.update { it.copy(error = "系统音频已停止（录屏授权被结束），本次记录已保存") }
                                        stop()
                                    }
                                }
                            },
                            { message ->
                                scope.launch {
                                    if (token != startToken) return@launch
                                    _state.update { it.copy(error = message) }
                                    if (_state.value.running) stop()
                                }
                            },
                        )
                    }
                    LiveSource.FILE -> {
                        val u = fileUri ?: throw RuntimeException("没有选择音频文件")
                        FileAudioCapture(
                            context, u,
                            { frames ->
                                lastAudioAt = SystemClock.elapsedRealtime()
                                // 全速喂入时用待识别队列做反压，避免长音频把内存堆满。
                                var guard = 0
                                while (_state.value.running && !_state.value.stopping &&
                                    finalPending.get() > 6 && guard < 600
                                ) {
                                    try {
                                        Thread.sleep(50)
                                    } catch (_: InterruptedException) {
                                        break
                                    }
                                    guard++
                                }
                                var sent = channel.trySend(frames)
                                // 真正的阻塞反压：文件线程宁可等 VAD 消费，也绝不丢音频帧。
                                while (sent.isFailure && !sent.isClosed &&
                                    _state.value.running && !_state.value.stopping
                                ) {
                                    try {
                                        Thread.sleep(10)
                                    } catch (_: InterruptedException) {
                                        break
                                    }
                                    sent = channel.trySend(frames)
                                }
                            },
                            {
                                scope.launch {
                                    _state.update {
                                        if (it.sessionId == sessionId && !it.stopping)
                                            it.copy(status = "转写完成，正在生成译文…") else it
                                    }
                                    // 文件读完不代表处理完：等识别和翻译队列排空再结束课次。
                                    val deadline = SystemClock.elapsedRealtime() + 30L * 60_000
                                    while (token == startToken && _state.value.running &&
                                        !_state.value.stopping &&
                                        (finalPending.get() > 0 || translatePending.get() > 0) &&
                                        SystemClock.elapsedRealtime() < deadline
                                    ) {
                                        delay(200)
                                    }
                                    if (token == startToken && _state.value.running) stop()
                                }
                            },
                            { message ->
                                scope.launch {
                                    if (token != startToken) return@launch
                                    _state.update { it.copy(error = message) }
                                    if (_state.value.running) stop()
                                }
                            },
                        )
                    }
                }
                localCap = cap
                capture = cap
                cap.start()
                if (source == LiveSource.MIC) {
                    // 蓝牙/有线麦克风切换后，AudioRecord 不会自动跟着换设备：重建采集，避免静音继续录。
                    routeMonitor?.stop()
                    routeMonitor = com.localinterpreter.tablet.audio.AudioRouteMonitor(context) { reason ->
                        scope.launch { restartMicCapture(reason) }
                    }.also { it.start() }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (stopping || token != startToken) throw kotlinx.coroutines.CancellationException("start cancelled")
                _state.value = LiveState(
                    running = true,
                    starting = false,
                    sessionId = sessionId,
                    courseId = sessionCourse,
                    source = source,
                    startedAt = System.currentTimeMillis(),
                    status = when (source) {
                        LiveSource.MIC -> "正在听麦克风"
                        LiveSource.SYSTEM -> "正在听系统声音"
                        LiveSource.FILE -> "正在全速转写音频文件"
                    },
                )
                // 启动过程中用户点了「结束」：把刚发布的状态收回，交给 catch 做资源/课次清理。
                if (stopping || token != startToken) {
                    _state.update {
                        if (it.sessionId == sessionId) it.copy(running = false, starting = false, status = "已结束") else it
                    }
                    throw kotlinx.coroutines.CancellationException("start cancelled")
                }
                app.llm.warmUpTranslator(glossaryText)
                sessionJob = scope.launch {
                    try {
                        processAudio(
                            sessionId, segmenter, channel,
                            sourceLangPref, targetLangPref, glossaryText, sessionCourse,
                            wallClockSource = source != LiveSource.FILE,
                            fileMode = source == LiveSource.FILE,
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        AppLog.e("Live", "音频处理线程异常", t)
                        _state.update {
                            if (it.sessionId == sessionId) {
                                it.copy(error = "音频处理异常：${t.message?.take(100) ?: "未知错误"}")
                            } else it
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLog.d("Live", "启动已取消")
                runCatching { routeMonitor?.stop() }
                routeMonitor = null
                runCatching { localCap?.stop() }
                if (capture === localCap) capture = null
                if (audioChannel === localChannel) {
                    runCatching { localChannel?.close() }
                    audioChannel = null
                }
                if (vad === localSegmenter) {
                    runCatching { localSegmenter?.release() }
                    vad = null
                }
                if (sessionId != null) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        app.repo.deleteSession(sessionId, force = true)
                    }
                }
                if (token == startToken) {
                    _state.update { if (it.starting) it.copy(starting = false, status = "已结束") else it }
                }
            } catch (e: Throwable) {
                AppLog.e("Live", "启动失败", e)
                runCatching { localCap?.stop() }
                if (capture === localCap) capture = null
                if (audioChannel === localChannel) {
                    runCatching { localChannel?.close() }
                    audioChannel = null
                }
                if (vad === localSegmenter) {
                    runCatching { localSegmenter?.release() }
                    vad = null
                }
                if (sessionId != null) {
                    withContext(Dispatchers.IO) { app.repo.deleteSession(sessionId, force = true) }
                }
                if (token == startToken) {
                    _state.update { it.copy(starting = false, error = e.message ?: "启动失败") }
                }
            }
        }
    }

    private class FloatBuf(capacity: Int) {
        var data = FloatArray(capacity)
        var size = 0
        var maxSize = capacity

        fun append(src: FloatArray, len: Int) {
            if (size + len > data.size) {
                var cap = data.size
                while (cap < size + len) cap *= 2
                data = data.copyOf(cap)
            }
            System.arraycopy(src, 0, data, size, len)
            size += len
            if (size > maxSize) {
                val drop = size - maxSize
                System.arraycopy(data, drop, data, 0, size - drop)
                size -= drop
            }
        }

        /** 无人说话时仅保留极短的前导滚动缓冲，防止环境杂音长期累积被误送去识别。 */
        fun trimToTail(keep: Int) {
            if (size > keep) {
                val drop = size - keep
                System.arraycopy(data, drop, data, 0, keep)
                size = keep
            }
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)
        fun clear() { size = 0 }
        fun subListFrom(from: Int): FloatArray = data.copyOfRange(from, size)
    }

    private suspend fun processAudio(
        sessionId: Long,
        segmenter: VadSegmenter,
        channel: Channel<FloatArray>,
        sessionSourceLang: String,
        sessionTargetLang: String,
        sessionGlossary: String,
        sessionCourse: Long?,
        wallClockSource: Boolean,
        fileMode: Boolean,
    ) {
        val window = segmenter.windowSize
        val chunk = FloatArray(window)
        var filled = 0
        var windowCount = 0L
        val current = FloatBuf(16000 * 14)
        val preRollSamples = 16000 / 2 // 0.5 秒前导缓冲，保证开口首字不丢失
        var wasPaused = false
        var lastSafetyAt = 0L
        var pausedAtWall = 0L
        var pausedTotalMs = 0L
        var quietWindows = 0L
        // 自适应底噪基线（EMA）：若用户做过 2 秒环境测噪校准，直接以实测值作为初始基线，第 0 秒即精准抑噪。
        val calibrated = app.settings.value.calibratedNoiseFloor
        var noiseFloor = if (!fileMode && calibrated in 0.0005f..0.03f) calibrated else 0.002f
        var speechWindowsInBuf = 0
        var nonSpeechRun = 0
        var lastPartialSampleSize = 0
        // 跨 drainSegments 保留 <0.4s 的短片段，等待下一句合并；静音超过 2s 或暂停/结束时自动刷出。
        var carry: FloatArray? = null
        var carryStart = 0L
        var carrySinceMs = 0L
        bufferEpoch++

        fun timeOffset(): Double = if (wallClockSource) pausedTotalMs / 1000.0 else 0.0

        fun clearCurrent() {
            current.clear()
            speechWindowsInBuf = 0
            nonSpeechRun = 0
            lastPartialSampleSize = 0
            bufferEpoch++
        }

        suspend fun drainSegments(flushCarry: Boolean = false): Int {
            val batch = mutableListOf<VadChunk>()
            val np = noiseProfile()
            val minSegRms = if (fileMode) 0.0012f else max(np.segFloor, noiseFloor * np.segMult)
            while (true) {
                val seg = segmenter.nextSegment() ?: break
                // 实时模式下二次声学校验：过滤 VAD 误切出的微弱底噪/敲击/气息声。
                if (!fileMode) {
                    val segRms = rms(seg.samples)
                    val segPeak = peak(seg.samples)
                    if (segRms < minSegRms && segPeak < 0.02f) {
                        continue
                    }
                }
                val merged = if (carry != null) {
                    // 把 <0.4s 的碎片并到下一句，避免一次完整模型调用只换两三个字。
                    val audio = FloatArray(carry!!.size + seg.samples.size)
                    carry!!.copyInto(audio)
                    seg.samples.copyInto(audio, carry!!.size)
                    VadChunk(carryStart, audio)
                } else seg
                if (merged.samples.size < MIN_SEGMENT_SAMPLES && !flushCarry) {
                    carry = merged.samples
                    carryStart = merged.startSamples
                    carrySinceMs = SystemClock.elapsedRealtime()
                    continue
                }
                batch += merged
                carry = null
                carrySinceMs = 0L
            }
            val nowMs = SystemClock.elapsedRealtime()
            val carryExpired = carry != null && carrySinceMs > 0L && (nowMs - carrySinceMs >= 2000L)
            if (carry != null && (flushCarry || carryExpired)) {
                // 尾巴上/停顿 2 秒后的碎片：够长（>=0.28s）且能量达标才单独识别，太短多为杂音直接丢弃。
                val c = carry!!
                val carryOk = c.size >= MIN_CARRY_FLUSH_SAMPLES &&
                    (fileMode || rms(c) >= minSegRms || peak(c) >= 0.025f)
                if (carryOk) {
                    batch += VadChunk(carryStart, c)
                }
                carry = null
                carrySinceMs = 0L
            }
            if (batch.isEmpty()) return 0
            // 预览文本只对应最近几秒；一次 flush 出多句时归给最后一句，避免塞进第一句。
            val preview = lastPartialText
            lastPartialText = ""
            val offset = timeOffset()
            val curSourceLang = sourceLangPref
            val curTargetLang = targetLangPref
            val curGlossary = glossaryText
            batch.forEachIndexed { index, seg ->
                val t0 = seg.startSamples / 16000.0 + offset
                val t1 = t0 + seg.samples.size / 16000.0
                val fallback = if (index == batch.lastIndex) preview else ""
                // VAD 切句时先把预览提升成正式字幕行，最终 ASR 完成后再原地替换，
                // 这样慢设备上不必等最终识别才看到文字。
                val provisionalId = if (fallback.isNotBlank()) {
                    val pid = nextTempId.decrementAndGet()
                    _state.update { st ->
                        if (st.sessionId != sessionId) st
                        else {
                            val samePartial = st.partialSrc == fallback
                            // 把已经算好的草稿译文一起带进正式行，先给用户一个译稿，最终译文到达后替换。
                            val draftTgt = if (samePartial) st.partialTgt else ""
                            st.copy(
                                segments = st.segments + LiveSegment(
                                    id = pid, t0 = t0, t1 = t1, srcLang = "", src = fallback,
                                    tgt = draftTgt, draftTgt = draftTgt.isNotBlank(),
                                ),
                                partialSrc = if (samePartial) "" else st.partialSrc,
                                partialTgt = if (samePartial) "" else st.partialTgt,
                                partialDraft = if (samePartial) false else st.partialDraft,
                            )
                        }
                    }
                    pid
                } else null
                finalPending.incrementAndGet()
                refreshPendingQueue()
                AppLog.d("Live", "VAD 分句 start=${"%.2f".format(t0)}s len=${seg.samples.size / 16000.0}s fallback=${fallback.length}字")
                try {
                    finalChannel.send(
                        PendingFinal(
                            sessionId, seg.samples, t0, t1, SystemClock.elapsedRealtime(),
                            fallback, curSourceLang, curTargetLang, curGlossary, sessionCourse,
                            provisionalId,
                            if (latProbe) System.currentTimeMillis() else 0L,
                        )
                    )
                } catch (t: Throwable) {
                    finalPending.decrementAndGet()
                    refreshPendingQueue()
                    if (provisionalId != null) {
                        _state.update { st ->
                            st.copy(segments = st.segments.filterNot { it.id == provisionalId })
                        }
                    }
                    AppLog.e("Live", "分句入队失败", t)
                }
            }
            return batch.size
        }

        /**
         * VAD 在嘈杂/远场音频上可能一直不触发分句，导致文字只停留在预览行里被下一段顶掉。
         * 这里把当前缓冲强制送入最终识别，但严格校验缓冲内确实存在足够人声活动与信噪比，
         * 彻底杜绝无人说话时底噪每 6 秒触发一次兜底识别与幻觉字幕。
         */
        fun commitCurrent(reason: String): Boolean {
            if (!fileMode && speechWindowsInBuf < MIN_SPEECH_WINDOWS) return false
            val preview = lastPartialText
            val available = current.toFloatArray()
            val maxLen = 16000 * 14
            val samples = if (available.size > maxLen) {
                available.copyOfRange(available.size - maxLen, available.size)
            } else available
            if (samples.size < 8000) return false
            val energy = rms(samples)
            val np = noiseProfile()
            val minCommitEnergy = if (fileMode) 0.0015f else max(np.partialFloor, noiseFloor * np.partialMult)
            if (energy < minCommitEnergy) return false
            // 噪声环境下识别会反复产生同一句幻觉，和最近字幕相同就丢弃，避免刷屏。
            val key = preview.filter { it.isLetterOrDigit() }
            if (key.length >= 2 &&
                _state.value.segments.takeLast(3).any { seg ->
                    seg.src.filter { it.isLetterOrDigit() } == key
                }
            ) {
                if (current.size >= 16000 * 12) {
                    clearCurrent()
                    lastPartialText = ""
                }
                return false
            }
            val t1 = windowCount * window.toDouble() / 16000.0 + timeOffset()
            val t0 = (t1 - samples.size / 16000.0).coerceAtLeast(0.0)
            lastPartialText = ""
            finalPending.incrementAndGet()
            refreshPendingQueue()
            AppLog.d(
                "Live",
                "$reason start=${"%.2f".format(t0)}s len=${samples.size / 16000.0}s energy=${"%.4f".format(energy)} " +
                    "floor=${"%.4f".format(noiseFloor)} speechWin=$speechWindowsInBuf preview=${preview.length}字"
            )
            try {
                finalChannel.trySend(
                    PendingFinal(
                        sessionId, samples, t0, t1, SystemClock.elapsedRealtime(),
                        preview, sourceLangPref, targetLangPref, glossaryText, sessionCourse,
                        null,
                        if (latProbe) System.currentTimeMillis() else 0L,
                    )
                ).getOrThrow()
            } catch (t: Throwable) {
                finalPending.decrementAndGet()
                refreshPendingQueue()
                AppLog.e("Live", "兜底提交失败", t)
                return false
            }
            clearCurrent()
            draftGeneration++
            return true
        }

        try {
            for (frames in channel) {
                if (_state.value.sessionId != sessionId) continue
                // 音频重新进来后，自动清掉之前的「没有收到音频数据」告警条。
                if (_state.value.error?.startsWith("没有收到音频数据") == true) {
                    _state.update {
                        if (it.sessionId == sessionId && it.error?.startsWith("没有收到音频数据") == true) {
                            it.copy(error = null)
                        } else it
                    }
                }
                if (_state.value.paused) {
                    if (!wasPaused) {
                        wasPaused = true
                        // 不要在会话中途 flush VAD：部分 sherpa 版本 flush 后不再分句。
                        val emitted = drainSegments(flushCarry = true)
                        if (emitted == 0) commitCurrent("暂停兜底")
                        clearCurrent()
                        pausedAtWall = SystemClock.elapsedRealtime()
                        _state.update { it.copy(speaking = false) }
                    }
                    continue
                }
                if (wasPaused && pausedAtWall > 0) {
                    // 麦克风/系统音频暂停期间采集仍在继续，把暂停墙钟时间补进时间轴。
                    pausedTotalMs += SystemClock.elapsedRealtime() - pausedAtWall
                    pausedAtWall = 0
                }
                wasPaused = false
                var offset = 0
                while (offset < frames.size) {
                    val n = minOf(window - filled, frames.size - offset)
                    System.arraycopy(frames, offset, chunk, filled, n)
                    filled += n
                    offset += n
                    if (filled == window) {
                        segmenter.accept(chunk.copyOf())
                        filled = 0
                        windowCount++
                        val level = rms(chunk)
                        val isSpeechNow = segmenter.isSpeech()

                        // 动态估计环境底噪基线：只在非说话帧更新，快速适应安静、慢速跟踪稳态风扇/空调底噪。
                        if (!fileMode && !isSpeechNow && level > 0.0002f) {
                            noiseFloor = when {
                                level < noiseFloor -> noiseFloor * 0.88f + level * 0.12f
                                level < noiseFloor * 3.0f -> noiseFloor * 0.97f + level * 0.03f
                                else -> noiseFloor
                            }.coerceIn(0.0012f, 0.03f)
                        }
                        val np = noiseProfile()
                        val speechEnergyGate = if (fileMode) 0.0012f else max(np.speechFloor, noiseFloor * np.speechMult)

                        if (isSpeechNow) {
                            nonSpeechRun = 0
                        } else {
                            nonSpeechRun++
                        }

                        if (fileMode) {
                            current.append(chunk, window)
                            if (isSpeechNow) speechWindowsInBuf++
                        } else if (isSpeechNow) {
                            current.append(chunk, window)
                            if (level >= speechEnergyGate) speechWindowsInBuf++
                        } else if (speechWindowsInBuf > 0) {
                            // 刚说完话处于停顿等待 VAD 切句阶段：继续保留尾音直到切句或超时。
                            current.append(chunk, window)
                            // 连续静音超过 ~2.1 秒仍未切出正式句且未形成有效预览，说明只是短暂杂音（如咳嗽/敲桌），直接回退到静音前导状态。
                            if (nonSpeechRun >= 65 && (speechWindowsInBuf < MIN_SPEECH_WINDOWS || lastPartialText.isBlank())) {
                                clearCurrent()
                                lastPartialText = ""
                                _state.update {
                                    if (it.sessionId == sessionId && it.partialSrc.isNotBlank()) {
                                        it.copy(partialSrc = "", partialTgt = "", partialDraft = false)
                                    } else it
                                }
                            }
                        } else {
                            // 无人说话：只保留 0.5 秒滚动前导缓冲，不积累长段环境杂音。
                            current.append(chunk, window)
                            current.trimToTail(preRollSamples)
                        }

                        val emitted = drainSegments()
                        if (emitted > 0) {
                            clearCurrent()
                            draftGeneration++
                        } else if (speechWindowsInBuf >= MIN_SPEECH_WINDOWS &&
                            lastPartialText.isNotBlank() &&
                            SystemClock.elapsedRealtime() - lastSafetyAt > 2000 &&
                            (current.size >= 16000 * 10 ||
                                (!isSpeechNow && (current.size >= 16000 * 6 || nonSpeechRun >= 75)))
                        ) {
                            lastSafetyAt = SystemClock.elapsedRealtime()
                            // 同样不要中途 flush，直接排空已产生的分句或兜底提交。
                            val flushed = drainSegments(flushCarry = true)
                            if (flushed > 0) {
                                clearCurrent()
                                draftGeneration++
                            } else {
                                commitCurrent(
                                    if (isSpeechNow) "强制兜底" else "识别兜底"
                                )
                            }
                        }

                        if (level > 0.001f) {
                            quietWindows = 0
                            if (silenceWarned) {
                                silenceWarned = false
                                _state.update {
                                    if (it.sessionId == sessionId && it.error?.contains("输入电平≈0") == true) {
                                        it.copy(error = null)
                                    } else it
                                }
                            }
                        } else if (level < 0.0005f) {
                            quietWindows++
                        }
                        if (!fileMode && quietWindows >= 160 && !silenceWarned) {
                            silenceWarned = true
                            val st = _state.value
                            val sourceName = if (st.source == LiveSource.SYSTEM) "系统音频" else "麦克风"
                            _state.update {
                                if (it.sessionId == sessionId && it.error == null) {
                                    it.copy(
                                        error = "$sourceName 没有收到声音（输入电平≈0）：请检查系统麦克风/音量开关、其它应用是否占用，或改用音频文件"
                                    )
                                } else it
                            }
                        }
                        if (windowCount % 8 == 0L) {
                            val activeSpeaking = isSpeechNow && (fileMode || level >= speechEnergyGate * 0.7f || speechWindowsInBuf >= 3)
                            _state.update { st ->
                                if (st.sessionId == sessionId) {
                                    st.copy(audioLevel = level, speaking = activeSpeaking)
                                } else st
                            }
                        }
                        if (windowCount % 320 == 0L) {
                            AppLog.d(
                                "Live",
                                "音频电平 rms=${"%.4f".format(level)} floor=${"%.4f".format(noiseFloor)} speech=$isSpeechNow windows=$windowCount",
                            )
                        }
                        // 文件转写全速喂入，实时预览会严重滞后且抢 CPU，直接专注最终识别。
                        if (!fileMode && isSpeechNow && speechWindowsInBuf >= MIN_PARTIAL_SPEECH_WINDOWS) {
                            if (current.size - lastPartialSampleSize >= 7200 &&
                                maybePartial(current, noiseFloor)
                            ) {
                                lastPartialSampleSize = current.size
                            }
                        }
                    }
                }
            }
            runCatching { segmenter.flush() }
            val emitted = drainSegments(flushCarry = true)
            if (emitted == 0) commitCurrent("结束兜底")
        } finally {
            runCatching { segmenter.release() }
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return Math.sqrt(sum / samples.size).toFloat()
    }

    private fun peak(samples: FloatArray): Float {
        var m = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > m) m = a
        }
        return m
    }

    private fun maybePartial(current: FloatBuf, noiseFloor: Float): Boolean {
        val settings = app.settings.value
        val now = SystemClock.elapsedRealtime()
        if (current.size < 16000 * 0.65) return false
        val slowAsr = _state.value.lastAsrMs > 1200
        val interval = if (slowAsr) 1600 else 850
        if (now - lastPartialAt < interval) return false
        if (finalPending.get() > 0) return false
        if (slowAsr && (translatePending.get() > 0 || backgroundTasks.get() > 0)) return false
        if (partialInFlight) return false
        val sid = _state.value.sessionId ?: return false
        if (!_state.value.running || _state.value.paused) return false
        // 预览只取最近 4 秒（慢设备 3 秒）：SenseVoice 自注意力与时长呈超线性关系，
        // 缩短预览窗可提速 ~45%，同时减少占用 ASR 锁时间，让最终分句识别零等待。
        val partialWindow = if (slowAsr) 16000 * 3 else 16000 * 4
        val samples = if (current.size > partialWindow) current.subListFrom(current.size - partialWindow) else current.toFloatArray()
        val energy = rms(samples)
        val np = noiseProfile()
        val minEnergy = max(np.partialFloor, noiseFloor * np.partialMult)
        if (energy < minEnergy) return false
        lastPartialAt = now
        val epoch = bufferEpoch
        partialInFlight = true
        scope.launch(Dispatchers.IO) {
            try {
                val partialStart = SystemClock.elapsedRealtime()
                val res = asr.transcribeDetailed(samples, sourceLangPref, isPartial = true)
                val text = res.text
                if (text.isBlank()) return@launch
                if (isHallucination(text, energy, samples.size / 16000.0, res.event, fileMode = false)) {
                    AppLog.d("Live", "忽略预览杂音幻觉 text=${text.take(20)} event=${res.event} rms=${"%.4f".format(energy)}")
                    return@launch
                }
                if (_state.value.sessionId != sid) return@launch
                if (!_state.value.running || _state.value.paused) return@launch
                // 缓冲已经换句/换会话时，这个结果属于上一段，不能写回预览或 fallback。
                if (epoch != bufferEpoch) return@launch
                if (text == lastPartialText) return@launch
                lastPartialText = text
                val lang = res.lang
                AppLog.d(
                    "Live",
                    "预览识别 ${SystemClock.elapsedRealtime() - partialStart}ms text=${text.length}字 lang=${lang.ifBlank { sourceLangPref }}",
                )
                draftGeneration++
                val draftGen = draftGeneration
                _state.update {
                    if (it.running && !it.paused && it.sessionId == sid) {
                        it.copy(partialSrc = text, partialTgt = "", partialDraft = false)
                    } else it
                }
                val hasEnoughForDraft = text.count { it in '\u4E00'..'\u9FFF' } >= 4 || text.length >= 10
                if (settings.draftTranslate && settings.llmMode != SettingsData.MODE_API &&
                    !draftSuppressed && hasEnoughForDraft &&
                    SystemClock.elapsedRealtime() - lastDraftAt > DRAFT_INTERVAL_MS
                ) {
                    lastDraftAt = SystemClock.elapsedRealtime()
                    val draftLang = lang.ifBlank {
                        if (sourceLangPref != "auto") sourceLangPref else guessLanguage(text)
                    }
                    draftChannel.trySend(DraftJob(text, draftLang, draftGen))
                }
            } catch (_: Throwable) {
            } finally {
                if (_state.value.sessionId == sid) partialInFlight = false
            }
        }
        return true
    }

    /** 设置里的语言变化时立即生效：不必结束后重开一场。 */
    private var settingsWatcherStarted = false
    private fun watchSettings() {
        if (settingsWatcherStarted) return
        settingsWatcherStarted = true
        scope.launch {
            app.settings.state.collect { st ->
                if (!_state.value.running) return@collect
                if (st.sourceLang != sourceLangPref) {
                    sourceLangPref = st.sourceLang
                    AppLog.d("Live", "识别语言已在会话中切换为 ${st.sourceLang}")
                }
                if (st.targetLang != targetLangPref) {
                    targetLangPref = st.targetLang
                    AppLog.d("Live", "翻译目标已切换为 ${st.targetLang}")
                }
            }
        }
    }

    private fun startWorkers() {
        if (workersStarted) return
        workersStarted = true
        watchSettings()

        scope.launch {
            for (job in finalChannel) {
                try {
                    handleFinal(job)
                } catch (t: Throwable) {
                    AppLog.e("Live", "最终识别任务异常", t)
                } finally {
                    finalPending.decrementAndGet()
                    refreshPendingQueue()
                }
            }
        }

        scope.launch {
            for (job in translateChannel) {
                try {
                    handleTranslation(job)
                } catch (t: Throwable) {
                    AppLog.e("Live", "翻译任务异常", t)
                } finally {
                    translatePending.decrementAndGet()
                    refreshPendingQueue()
                }
            }
        }

        scope.launch {
            for (job in draftChannel) {
                try {
                    handleDraft(job)
                } catch (_: Throwable) {
                }
            }
        }

        // 声纹识别走低优先级串行队列，在实时识别/翻译忙碌时短暂让路，避免抢占 CPU 大核。
        scope.launch(Dispatchers.IO) {
            for (job in diarizeChannel) {
                try {
                    var waited = 0
                    while ((finalPending.get() > 0 || translatePending.get() > 0) &&
                        _state.value.sessionId == job.sessionId && waited < 10
                    ) {
                        delay(150)
                        waited++
                    }
                    if (_state.value.sessionId == job.sessionId) {
                        diarize(job.sessionId, job.segmentId, job.samples)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        scope.launch {
            for (job in questionChannel) {
                try {
                    handleQuestion(job)
                } catch (e: Throwable) {
                    updateQuestion(job.localId) {
                        it.copy(pending = false, answer = "回答失败：${e.message?.take(80) ?: ""}")
                    }
                    _state.update { it.copy(assistantBusy = false) }
                }
            }
        }

        scope.launch {
            while (true) {
                delay(2_000)
                val st = _state.value
                if (st.running && !st.stopping && st.source != LiveSource.FILE && lastAudioAt > 0 &&
                    SystemClock.elapsedRealtime() - lastAudioAt > 5_000 && st.error == null
                ) {
                    _state.update {
                        if (it.running && it.error == null) {
                            it.copy(error = "没有收到音频数据：请检查录音权限，或确认音源正在播放")
                        } else it
                    }
                }
            }
        }

        scope.launch {
            while (true) {
                delay(12_000)
                if (!_state.value.running || _state.value.paused) continue
                try {
                    maybeSummarize()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private suspend fun handleFinal(job: PendingFinal) {
        val settings = app.settings.value
        if (_state.value.sessionId != job.sessionId) {
            AppLog.d("Live", "丢弃过期字幕 session=${job.sessionId} 当前=${_state.value.sessionId}")
            return
        }
        val fileMode = _state.value.source == LiveSource.FILE
        val segEnergy = rms(job.samples)
        val segDurSec = job.samples.size / 16000.0
        val asrStarted = SystemClock.elapsedRealtime()
        var finalText = ""
        var lang = ""
        var event = ""
        var asrFailed = false
        try {
            if (job.samples.isNotEmpty()) {
                val res = asr.transcribeDetailed(job.samples, job.sourceLang, isPartial = false)
                finalText = res.text
                lang = res.lang
                event = res.event
            }
        } catch (t: Throwable) {
            asrFailed = true
            AppLog.e("Live", "最终识别异常 len=${job.samples.size} samples", t)
        }
        val asrMs = SystemClock.elapsedRealtime() - asrStarted
        val asrDoneWallMs = if (job.vadEndWallMs > 0L) System.currentTimeMillis() else 0L
        if (asrMs > 1800 && !draftSuppressed) {
            draftSuppressed = true
            AppLog.d("Live", "识别较慢（${asrMs}ms），本场暂停边说边译草稿，把 CPU 留给识别")
        }
        _state.update {
            if (it.sessionId == job.sessionId) it.copy(lastAsrMs = asrMs) else it
        }
        // 识别结果若被判定为环境杂音/非语音事件/低能量幻觉，直接丢弃，不要回退到同样可能失真的预览文本。
        val filteredNoise = !asrFailed && finalText.isNotBlank() &&
            isHallucination(finalText, segEnergy, segDurSec, event, fileMode = fileMode)
        if (filteredNoise) {
            AppLog.d(
                "Live",
                "过滤杂音幻觉 text=${finalText.take(24)} event=$event rms=${"%.4f".format(segEnergy)} dur=${"%.2f".format(segDurSec)}s",
            )
            _state.update { st ->
                val cleaned = if (job.provisionalId != null) {
                    st.segments.filterNot { it.id == job.provisionalId }
                } else st.segments
                val clearPartial = st.partialSrc == job.fallbackText
                st.copy(
                    segments = cleaned,
                    partialSrc = if (clearPartial) "" else st.partialSrc,
                    partialTgt = if (clearPartial) "" else st.partialTgt,
                    partialDraft = if (clearPartial) false else st.partialDraft,
                )
            }
            return
        }
        // 仅当 ASR 抛异常或样本为空时才使用 fallbackText；若 SenseVoice 正常把静音解为空，不再用预览幻觉顶替。
        val usedFallback = finalText.isBlank() && (asrFailed || job.samples.isEmpty()) && job.fallbackText.isNotBlank()
        val text = if (usedFallback) job.fallbackText else finalText
        if (lang.isBlank()) {
            lang = if (job.sourceLang != "auto") job.sourceLang else guessLanguage(text)
        }
        AppLog.d(
            "Live",
            "最终识别 ${asrMs}ms len=${"%.2f".format(segDurSec)}s rms=${"%.4f".format(segEnergy)} event=$event " +
                "text=${text.length}字 fallback=$usedFallback asrStatus=${asr.status.value}"
        )
        if (text.isBlank()) {
            // 仅在音频有明显人声能量（>=0.015）却连续解出空文本时告警，避免安静杂音误报故障。
            if (segEnergy >= 0.015f || asrFailed) emptyFinals++
            _state.update { st ->
                val cleaned = if (job.provisionalId != null) {
                    st.segments.filterNot { it.id == job.provisionalId }
                } else st.segments
                val clearPartial = st.partialSrc == job.fallbackText
                st.copy(
                    segments = cleaned,
                    partialSrc = if (clearPartial) "" else st.partialSrc,
                    partialTgt = if (clearPartial) "" else st.partialTgt,
                    partialDraft = if (clearPartial) false else st.partialDraft,
                    error = if (emptyFinals >= 3 && st.error == null) {
                        "连续多句没有识别到文字：请检查音源音量、识别模型或识别线程设置"
                    } else st.error,
                )
            }
            return
        }
        emptyFinals = 0
        val low = looksLowConfidence(text) || usedFallback
        var saved = true
        val id = try {
            withContext(Dispatchers.IO) {
                app.db.addSegment(
                    job.sessionId, job.t0, job.t1,
                    lang,
                    text, null, low,
                )
            }
        } catch (t: Throwable) {
            saved = false
            AppLog.e("Live", "写入字幕失败（可能存储空间不足）", t)
            _state.update {
                it.copy(error = "字幕保存失败：可能存储空间不足。本句仍会显示，请清理空间后重试。")
            }
            nextTempId.decrementAndGet()
        }
        val seg = LiveSegment(
            id = id, t0 = job.t0, t1 = job.t1, srcLang = lang, src = text, tgt = "",
            lowConfidence = low, audioPath = null,
        )
        AppLog.d("Live", if (saved) "字幕入库 id=$id t0=${"%.2f".format(job.t0)}" else "字幕仅显示未入库 id=$id")
        _state.update {
            if (it.sessionId != job.sessionId) it
            else {
                val clearPartial = it.partialSrc == job.fallbackText || it.partialSrc.isBlank()
                val provisionalIndex = job.provisionalId?.let { pid ->
                    it.segments.indexOfLast { s -> s.id == pid }
                } ?: -1
                val segments = if (provisionalIndex >= 0) {
                    val existing = it.segments[provisionalIndex]
                    // 保留临时行里已有的草稿译文（斜体过渡），避免先退回「译文中…」闪烁再出现最终译文。
                    val merged = if (existing.draftTgt && existing.tgt.isNotBlank()) {
                        seg.copy(tgt = existing.tgt, draftTgt = true)
                    } else seg
                    it.segments.toMutableList().also { list -> list[provisionalIndex] = merged }
                } else {
                    it.segments + seg
                }
                it.copy(
                    segments = segments,
                    partialSrc = if (clearPartial) "" else it.partialSrc,
                    partialTgt = if (clearPartial) "" else it.partialTgt,
                    partialDraft = if (clearPartial) false else it.partialDraft,
                )
            }
        }
        // 先取消正在进行的草稿并立即下发正式翻译，把 WAV 落盘和声纹识别移出关键路径，最小化首字延迟。
        draftGeneration++
        translationEpoch++
        if (draftInFlight.get() > 0 && !finalTranslating) app.llm.cancelTranslation()
        translatePending.incrementAndGet()
        refreshPendingQueue()
        translateChannel.send(
            TranslateJob(
                job.sessionId, id, text, lang, job.endedAtMs,
                job.targetLang, glossaryText.ifBlank { job.glossary }, job.courseId, settings,
                job.vadEndWallMs, asrDoneWallMs,
            )
        )
        if (settings.keepAudio && job.samples.isNotEmpty() && saved && id > 0) {
            val idx = segmentIndex++
            scope.launch(Dispatchers.IO) {
                val f: File = app.repo.audioFile(job.sessionId, idx)
                if (runCatching { WavUtil.write(f, job.samples) }.isSuccess) {
                    val path = f.absolutePath
                    runCatching { app.db.updateSegmentAudioPath(id, path) }
                    updateSegment(id) { it.copy(audioPath = path) }
                }
            }
        }
        if (settings.diarization && job.samples.isNotEmpty()) {
            diarizeChannel.trySend(DiarizeJob(job.sessionId, id, job.samples))
        }
    }

    private suspend fun handleTranslation(job: TranslateJob) {
        val settings = app.settings.value
        // 新会话已经开始或正在启动时，旧会话积压的翻译任务直接跳过，别让新字幕排队。
        val curState = _state.value
        if ((curState.running || curState.starting) && curState.sessionId != job.sessionId) return
        AppLog.d(
            "Live",
            "翻译开始 segment=${job.segmentId} text=${job.text.length}字 src=${job.srcLang} " +
                "target=${job.targetLang} glossary=${job.glossary.length}字",
        )
        val sb = StringBuilder()
        var firstAt = 0L
        var lastUiPushAt = 0L
        finalTranslating = true
        val translationStartedAt = SystemClock.elapsedRealtime()
        val queuedMs = translationStartedAt - job.finalizedAt
        if (queuedMs > 3000 && _state.value.source != LiveSource.FILE) {
            AppLog.d("Live", "翻译排队 ${queuedMs}ms segment=${job.segmentId} pending=${translatePending.get()}")
        }
        try {
            // 模式/API 地址按入队时快照，避免排队期间切换内置/API 把旧句子发错地方；
            // 本地模型版本和线程跟随当前设置，避免旧任务把刚加载的新模型又换回去。
            val cfg = if (job.settings.llmMode == SettingsData.MODE_API) job.settings
            else job.settings.copy(modelVariant = settings.modelVariant, llmThreads = settings.llmThreads)
            // 按设置提取当前句之前的 N 句原文作为轻量上下文，显著提升代词与跨句连贯度，
            // 且上下文放在 user 消息中，不破坏 System Prompt + 术语表的 KV Prefix Cache。
            val ctxCount = settings.translationContext.coerceIn(0, 3)
            val prevContext = if (ctxCount > 0 && _state.value.sessionId == job.sessionId) {
                val segs = _state.value.segments
                val idx = segs.indexOfLast { it.id == job.segmentId }
                val prior = if (idx > 0) segs.subList((idx - ctxCount).coerceAtLeast(0), idx) else emptyList()
                prior.map { it.src.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            } else ""
            // 按源句长度动态设定 token 上限：短句不给冗余预算，长句充足，同时防止异常复读拖死队列。
            val dynamicMaxTokens = (job.text.length * 3 + 48).coerceIn(64, 280)
            val result = app.llm.translate(
                text = job.text,
                srcLang = job.srcLang,
                target = job.targetLang,
                glossary = job.glossary,
                maxTokens = dynamicMaxTokens,
                // 本地模型使用 0.0f 贪心解码（跳过全词表 top_p 排序与采样，速度最快且译文稳定）。
                temperature = if (cfg.llmMode == SettingsData.MODE_API) 0.1f else 0.0f,
                config = cfg,
                context = prevContext,
            ) { piece ->
                val now = SystemClock.elapsedRealtime()
                val isFirst = sb.isEmpty() && firstAt == 0L
                if (isFirst) {
                    firstAt = now
                    if (job.vadEndWallMs > 0L) {
                        val wall = System.currentTimeMillis()
                        AppLog.d(
                            "Lat",
                            "segId=${job.segmentId} vadEnd=${job.vadEndWallMs} asrDone=${job.asrDoneWallMs} " +
                                "firstToken=$wall finalDone=0",
                        )
                    }
                }
                sb.append(piece)
                // 首字 0ms 立即上屏；后续流式每 45ms 合并刷新一次，减少 Compose 重组开销与 CPU 争抢。
                if (isFirst || now - lastUiPushAt >= 45L) {
                    lastUiPushAt = now
                    updateSegment(job.segmentId) { it.copy(tgt = sb.toString(), draftTgt = false) }
                }
            }
            val finalText = result.ifBlank { sb.toString() }.trim()
            if (job.vadEndWallMs > 0L) {
                AppLog.d(
                    "Lat",
                    "segId=${job.segmentId} vadEnd=${job.vadEndWallMs} asrDone=${job.asrDoneWallMs} " +
                        "firstToken=0 finalDone=${System.currentTimeMillis()}",
                )
            }
            if (finalText.isNotBlank()) {
                updateSegment(job.segmentId) { it.copy(tgt = finalText, draftTgt = false) }
                runCatching {
                    withContext(Dispatchers.IO) { app.db.updateSegmentTranslation(job.segmentId, finalText) }
                }.onFailure { AppLog.e("Live", "保存译文失败", it) }
                if (firstAt > 0 && _state.value.sessionId == job.sessionId) {
                    // 文件是全速转写，排队时间不代表翻译速度；实时模式才显示从说完到首字的端到端延迟。
                    val latencyFrom = if (_state.value.source == LiveSource.FILE) translationStartedAt else job.finalizedAt
                    _state.update { it.copy(lastLatencyMs = firstAt - latencyFrom) }
                }
                if (settings.autoQuestion && settings.assistantEnabled &&
                    _state.value.sessionId == job.sessionId
                ) {
                    maybeEnqueueQuestion(job.segmentId, job.text, finalText, job.courseId)
                }
                if (settings.autoCorrect && settings.llmMode != SettingsData.MODE_API &&
                    _state.value.sessionId == job.sessionId
                ) {
                    scope.launch { maybeCorrect(job.segmentId, job.text) }
                }
            }
        } catch (e: Throwable) {
            AppLog.e("Live", "翻译失败 segment=${job.segmentId} text=${job.text.take(40)}", e)
            updateSegment(job.segmentId) {
                it.copy(
                    tgt = if (it.tgt.isBlank() || it.tgt == "译文中…" || it.draftTgt)
                        "（翻译失败：${e.message?.take(80) ?: "未知错误"}）"
                    else it.tgt,
                    draftTgt = false,
                )
            }
            if (settings.autoQuestion && settings.assistantEnabled &&
                _state.value.sessionId == job.sessionId
            ) {
                maybeEnqueueQuestion(job.segmentId, job.text, "", job.courseId)
            }
        } finally {
            finalTranslating = false
            // 补译模式：最后一条任务完成时自动将状态从「正在补译…」更新为「补译完成」。
            if (!_state.value.running && translatePending.get() <= 1 &&
                _state.value.sessionId == job.sessionId &&
                _state.value.status.startsWith("正在补译")
            ) {
                _state.update {
                    if (!it.running && it.sessionId == job.sessionId && it.status.startsWith("正在补译")) {
                        it.copy(status = "补译完成")
                    } else it
                }
            }
        }
    }

    private suspend fun handleDraft(job: DraftJob) {
        if (job.generation != draftGeneration || draftSuppressed) return
        // API 模式下草稿会和正式翻译抢同一个接口，容易触发网关空响应/限流，直接跳过。
        if (app.settings.value.llmMode == SettingsData.MODE_API) return
        if (translatePending.get() > 0 || finalPending.get() > 0) return
        val sb = StringBuilder()
        var lastUiPushAt = 0L
        backgroundTasks.incrementAndGet()
        draftInFlight.incrementAndGet()
        try {
            app.llm.translate(
                text = job.text,
                srcLang = job.srcLang,
                target = targetLangPref,
                glossary = glossaryText,
                // 草稿只求「先看到大意」：贪心 + 小 token 预算，避免抢走最终翻译的算力。
                maxTokens = DRAFT_MAX_TOKENS,
                temperature = 0.0f,
                allowEmpty = true,
            ) { piece ->
                if (job.generation != draftGeneration || job.text != lastPartialText ||
                    translatePending.get() > 0 || finalPending.get() > 0
                ) {
                    if (!finalTranslating) app.llm.cancelTranslation()
                    return@translate
                }
                sb.append(piece)
                val now = SystemClock.elapsedRealtime()
                if (lastUiPushAt == 0L || now - lastUiPushAt >= 50L || sb.length >= DRAFT_MAX_CHARS) {
                    lastUiPushAt = now
                    val preview = sb.toString()
                    _state.update {
                        if (it.running && !it.paused) it.copy(partialTgt = preview, partialDraft = true) else it
                    }
                }
                // 草稿够长立刻中止原生推理，不再在后台继续生成消耗 CPU 与占锁。
                if (sb.length >= DRAFT_MAX_CHARS && !finalTranslating) {
                    app.llm.cancelTranslation()
                }
            }
        } catch (_: Throwable) {
        } finally {
            draftInFlight.decrementAndGet()
            backgroundTasks.decrementAndGet()
        }
    }

    private suspend fun maybeCorrect(segmentId: Long, text: String) {
        if (app.settings.value.llmMode == SettingsData.MODE_API) return
        val initial = _state.value
        if (!initial.running || initial.paused) return
        if (translatePending.get() > 0 || finalPending.get() > 0) return
        delay(1500)
        val st = _state.value
        if (!st.running || st.paused) return
        if (translatePending.get() > 0 || finalPending.get() > 0) return
        val context = st.segments.takeLast(3).joinToString(" / ") { it.src }
        val epoch = translationEpoch
        backgroundTasks.incrementAndGet()
        if (epoch != translationEpoch || translatePending.get() > 0 || finalPending.get() > 0) {
            backgroundTasks.decrementAndGet()
            return
        }
        val fixed = try {
            app.llm.correct(text, context)
        } finally {
            backgroundTasks.decrementAndGet()
        } ?: return
        if (epoch != translationEpoch) return
        if (fixed == text) return
        val seg = _state.value.segments.firstOrNull { it.id == segmentId } ?: return
        if (seg.src != text) return
        updateSegment(segmentId) { it.copy(src = fixed, corrected = true) }
        runCatching {
            withContext(Dispatchers.IO) { app.db.updateSegmentSource(segmentId, fixed) }
        }.onFailure { AppLog.e("Live", "保存校正文本失败", it) }
    }

    private suspend fun diarize(sessionId: Long, segmentId: Long, samples: FloatArray) {
        if (_state.value.sessionId != sessionId) return
        val emb = speakerEngine.embed(samples) ?: return
        if (_state.value.sessionId != sessionId) return
        speakerMutex.withLock {
            if (_state.value.sessionId != sessionId) return
            var best: LiveSpeaker? = null
            var bestScore = 0.0
            for (s in speakers) {
                val score = Database.cosine(s.embedding, emb)
                if (score > bestScore) {
                    bestScore = score
                    best = s
                }
            }
            val chosen: LiveSpeaker = when {
                best != null && bestScore >= 0.62 -> best!!
                // 短于 0.8 秒的碎片声纹不稳定：优先归到最接近的已有说话人，避免误拆出很多假说话人。
                samples.size < 12800 && best != null -> best!!
                speakers.size >= 6 -> best ?: return
                else -> {
                    val idx = speakers.size
                    val label = "说话人 ${idx + 1}"
                    val color = speakerColors[idx % speakerColors.size]
                    val id = runCatching {
                        withContext(Dispatchers.IO) { app.db.addSpeaker(sessionId, label, color, emb) }
                    }.getOrDefault(-(idx + 1L))
                    // 查库期间可能已经结束并开始了新会话，旧说话人不能追加进新列表。
                    if (_state.value.sessionId != sessionId) return
                    LiveSpeaker(id, label, color, emb).also { speakers += it }
                }
            }
            if (_state.value.sessionId != sessionId) return
            runCatching {
                withContext(Dispatchers.IO) { app.db.setSegmentSpeaker(segmentId, chosen.id) }
            }.onFailure { AppLog.e("Live", "保存说话人失败", it) }
            updateSegment(segmentId) {
                it.copy(speaker = chosen.label, speakerColor = chosen.color, speakerId = chosen.id)
            }
        }
    }

    private fun maybeEnqueueQuestion(
        segmentId: Long,
        src: String,
        tgt: String,
        sessionCourse: Long? = courseId,
    ) {
        val text = tgt.ifBlank { src }.trim()
        if (text.length < 3 || text.length > 90) return
        if (!looksLikeQuestion(src) && !looksLikeQuestion(tgt)) return
        val key = text.replace(Regex("[\\s，。？！,.?!]"), "")
        val now = System.currentTimeMillis()
        val last = askedQuestions[key]
        if (last != null && now - last < 120_000) return
        askedQuestions[key] = now
        enqueueQuestion(segmentId, src, tgt, false, sessionCourse)
    }

    private fun enqueueQuestion(
        segmentId: Long?,
        src: String,
        tgt: String,
        manual: Boolean,
        sessionCourse: Long? = courseId,
    ) {
        val st = _state.value
        val sessionId = st.sessionId ?: return
        if (!st.running && !manual) return
        val localId = nextTempId.decrementAndGet()
        _state.update {
            it.copy(
                questions = listOf(
                    LiveQuestion(localId, null, src, tgt, if (manual) "" else "思考中…", pending = true)
                ) + it.questions
            )
        }
        questionChannel.trySend(
            QuestionJob(localId, sessionId, segmentId, src, tgt, manual, sessionCourse)
        )
    }

    fun ask(text: String) {
        val q = text.trim()
        if (q.isBlank()) return
        enqueueQuestion(null, q, "", true, courseId)
    }

    private suspend fun handleQuestion(job: QuestionJob) {
        if (_state.value.sessionId != job.sessionId) return
        try {
            analysisMutex.withLock {
            if (_state.value.sessionId != job.sessionId) return
            val settings = app.settings.value
            if (!settings.assistantEnabled && !job.manual) {
                // 不能直接返回：卡片会一直转圈。明确告诉用户助教已关闭。
                updateQuestion(job.localId) {
                    it.copy(pending = false, answer = "助教已关闭，可在设置里重新开启。")
                }
                return
            }
            val history = _state.value.segments.takeLast(20)
                .joinToString("\n") { "- ${it.src} / ${it.tgt}" }
            val cid = job.courseId
            var courseCtx = ""
            var materialText = ""
            if (cid != null) {
                val chunks = withContext(Dispatchers.IO) { app.db.topMaterialChunks(cid, job.question, 4) }
                courseCtx = withContext(Dispatchers.IO) { app.db.courseContext(cid) }.trim()
                if (chunks.isNotEmpty()) {
                    materialText = chunks.joinToString("\n") {
                        "[${it.materialName} 第${it.page}页] ${it.text}"
                    }.trim()
                }
            }
            // 三段独立限额：课堂记录保留末尾（最新）2200 字，课件片段独立预留 1600 字，
            // 课程背景 1000 字，避免长课程背景或旧字幕把刚检索到的课件片段裁掉。
            val historyCap = 2200
            val materialCap = 1600
            val ctxCap = 1000
            val trimmed = buildString {
                val h = history.ifBlank { "（暂无）" }
                appendLine("课堂记录：\n${if (h.length > historyCap) h.takeLast(historyCap) else h}")
                if (materialText.isNotBlank()) {
                    appendLine("\n课件片段：\n${if (materialText.length > materialCap) materialText.take(materialCap) else materialText}")
                }
                if (courseCtx.isNotBlank()) {
                    appendLine("\n课程背景：\n${if (courseCtx.length > ctxCap) courseCtx.take(ctxCap) else courseCtx}")
                }
            }
            val prompt = "$trimmed\n\n问题：${job.question}"
            _state.update { it.copy(assistantBusy = true) }
            val sb = StringBuilder()
            val text = try {
                app.llm.analyze(
                    Prompts.QA_SYSTEM, prompt, maxTokens = 420, temperature = 0.3f,
                ) { piece ->
                    sb.append(piece)
                    updateQuestion(job.localId) { it.copy(answer = sb.toString()) }
                }
            } catch (e: Throwable) {
                updateQuestion(job.localId) { it.copy(pending = false, answer = "回答失败：${e.message?.take(80) ?: ""}") }
                _state.update { it.copy(assistantBusy = false) }
                return
            }
            if (text == null) {
                updateQuestion(job.localId) {
                    it.copy(pending = false, answer = "助教模型未就绪。请到设置下载助教模型，或改用 API。")
                }
                _state.update { it.copy(assistantBusy = false) }
                return
            }
            if (text.isBlank()) {
                updateQuestion(job.localId) {
                    it.copy(pending = false, answer = "模型这次没有给出答案。请再试一次，或在设置里换用更大的助教模型 / API。")
                }
                _state.update { it.copy(assistantBusy = false) }
                return
            }
            var source = "综合"
            var answer = text.trim()
            val lines = answer.split("\n")
            val first = lines.firstOrNull()?.trim() ?: ""
            if (first.length <= 24 && listOf("来源", "课堂", "模型", "综合", "记录", "课件").any { it in first }) {
                source = when {
                    "课堂" in first || "记录" in first -> "课堂内容"
                    "课件" in first -> "课件"
                    "模型" in first -> "模型知识"
                    else -> "综合"
                }
                answer = lines.drop(1).joinToString("\n").trim()
            }
            // 小模型有时会把提示词里的课堂记录和问题复述一遍，只保留「答案」之后的内容。
            val answerMarkers = listOf("答案：", "答案:", "Answer:", "Answer：")
            val marker = answerMarkers
                .mapNotNull { m -> answer.lastIndexOf(m).takeIf { it > 0 }?.let { it to m } }
                .maxByOrNull { it.first }
            if (marker != null) {
                val before = answer.substring(0, marker.first)
                if (before.contains("问题") || before.contains("Question", ignoreCase = true)) {
                    answer = answer.substring(marker.first + marker.second.length).trim()
                }
            }
            // 小模型偶尔只回一个来源标签或极短碎片，此时重试一次，要求直接作答。
            val trivial = answer.length < 4 || answer in setOf("课堂内容", "课件", "综合", "模型知识", "Class content")
            if (trivial) {
                val retry = runCatching {
                    app.llm.analyze(
                        "你是课堂助教。只根据给定内容回答，用中文给出 2–4 句直接答案，不要复述记录，不要输出来源标签。",
                        prompt, 320, 0.2f,
                    )
                }.getOrNull()
                if (!retry.isNullOrBlank()) answer = retry.trim()
            }
            // 没有课件时模型偶尔会凭空加一行页码引用，去掉。
            if (job.courseId == null) {
                answer = answer.replaceFirst(Regex("^\\s*(第\\s*\\d+\\s*页|Page\\s*\\d+)\\s*\\n+"), "").trim()
            }
            if (answer.isBlank()) answer = text.trim()
            AppLog.d("Assistant", "回答 ${answer.length}字：${answer.take(120).replace("\n", " / ")}")
            val id = runCatching {
                withContext(Dispatchers.IO) {
                    app.db.addQuestion(job.sessionId, job.segmentId, job.question, job.questionTgt, answer, "", source)
                }
            }.onFailure { AppLog.e("Live", "保存问答失败", it) }.getOrNull()
            updateQuestion(job.localId) { it.copy(dbId = id, answer = answer, source = source, pending = false) }
            _state.update { it.copy(assistantBusy = false) }
            scope.launch { translateAnswer(job.sessionId, id, job.localId, answer) }
            }
        } finally {
            // 数据库/取消等异常也要清掉忙碌指示，否则助教侧栏会一直转圈。
            if (_state.value.sessionId == job.sessionId) {
                _state.update { it.copy(assistantBusy = false) }
            }
        }
    }

    private suspend fun translateAnswer(sessionId: Long, dbId: Long?, localId: Long, answer: String) {
        if (_state.value.sessionId != sessionId) return
        val cjk = answer.count { it in '一'..'鿿' }
        if (cjk < answer.length * 0.2) return
        val altTarget = com.localinterpreter.tablet.llm.Langs.resolveTarget("zh", targetLangPref)
        if (altTarget == "zh") return
        if (translatePending.get() > 0 || finalPending.get() > 0) return
        delay(1200)
        if (translatePending.get() > 0 || finalPending.get() > 0) return
        val epoch = translationEpoch
        try {
            backgroundTasks.incrementAndGet()
            if (epoch != translationEpoch || translatePending.get() > 0 || finalPending.get() > 0) {
                backgroundTasks.decrementAndGet()
                return
            }
            val alt = try {
                app.llm.translate(answer, "zh", altTarget, maxTokens = 400, temperature = 0.2f).trim()
            } finally {
                backgroundTasks.decrementAndGet()
            }
            if (epoch != translationEpoch) return
            if (_state.value.sessionId != sessionId) return
            if (alt.isNotBlank()) {
                updateQuestion(localId) { it.copy(answerAlt = alt) }
                if (dbId != null && dbId > 0) {
                    runCatching {
                        withContext(Dispatchers.IO) { app.db.updateQuestionAlt(dbId, alt) }
                    }.onFailure { AppLog.e("Live", "保存英文答案失败", it) }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private suspend fun maybeSummarize() {
        val settings = app.settings.value
        if (!settings.assistantEnabled) return
        if (_state.value.assistantBusy) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSummaryAt < 60_000) return
        val sessionAtStart = _state.value.sessionId ?: return
        val segmentsAtStart = _state.value.segments
        if (segmentsAtStart.size < 3) return
        if (segmentsAtStart.size - summaryIndex < 3) return
        if (_state.value.speaking || translatePending.get() > 0 || finalPending.get() > 0) return
        analysisMutex.withLock {
            if (!_state.value.running || _state.value.sessionId != sessionAtStart) return
            val segments = _state.value.segments
            val start = summaryIndex.coerceAtMost(max(0, segments.size - 8))
            val recent = segments.drop(start)
            if (recent.size < 3) return
            val lines = recent.joinToString("\n") { "- 原文：${it.src}\n  译文：${it.tgt}" }
            val previous = _state.value.summary
            val prompt = if (previous.isBlank()) "课堂字幕（按时间顺序）：\n$lines"
            else "上一版要点：\n$previous\n\n最近的字幕：\n$lines"
            _state.update { it.copy(assistantBusy = true) }
            val sb = StringBuilder()
            try {
                val text = app.llm.analyze(Prompts.SUMMARY_SYSTEM, prompt, maxTokens = 300, temperature = 0.3f) { piece ->
                    sb.append(piece)
                    if (_state.value.sessionId == sessionAtStart) {
                        _state.update { it.copy(summary = sb.toString()) }
                    }
                }
                if (!text.isNullOrBlank()) {
                    if (_state.value.sessionId != sessionAtStart) return
                    _state.update { it.copy(summary = text.trim()) }
                    summaryIndex = segments.size
                    lastSummaryAt = now
                    runCatching {
                        withContext(Dispatchers.IO) { app.db.addSummary(sessionAtStart, text.trim()) }
                    }.onFailure { AppLog.e("Live", "保存要点失败", it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.e("Live", "生成要点失败", t)
                if (_state.value.sessionId == sessionAtStart) {
                    lastSummaryAt = now
                }
            } finally {
                if (_state.value.sessionId == sessionAtStart) {
                    _state.update { it.copy(assistantBusy = false) }
                }
            }
        }
    }

    fun recap(minutes: Int = 5) {
        scope.launch {
            val st = _state.value
            val sessionAtStart = st.sessionId ?: return@launch
            if (st.assistantBusy || st.segments.isEmpty()) return@launch
            val cutoff = st.segments.lastOrNull()?.t1?.minus(minutes * 60.0)
            val items = st.segments.filter { cutoff == null || it.t1 >= cutoff }.takeLast(12)
                .ifEmpty { st.segments.takeLast(10) }
            if (items.isEmpty()) return@launch
            analysisMutex.withLock {
                if (_state.value.sessionId != sessionAtStart) return@launch
                _state.update { it.copy(assistantBusy = true) }
                val body = items.joinToString("\n") { "- ${it.src} / ${it.tgt}" }
                val sb = StringBuilder()
                try {
                    val result = app.llm.analyze(Prompts.RECAP_SYSTEM, "课堂内容：\n$body", 340, 0.3f) { piece ->
                        sb.append(piece)
                        if (_state.value.sessionId == sessionAtStart) {
                            _state.update { it.copy(summary = sb.toString()) }
                        }
                    }
                    val finalText = (result ?: sb.toString()).trim()
                    if (finalText.isNotBlank() && _state.value.sessionId == sessionAtStart) {
                        _state.update { it.copy(summary = finalText) }
                        runCatching {
                            withContext(Dispatchers.IO) { app.db.addSummary(sessionAtStart, finalText) }
                        }
                    } else if (result == null && _state.value.sessionId == sessionAtStart && _state.value.summary.isBlank()) {
                        _state.update { it.copy(summary = "助教模型未就绪，请到设置下载助教模型或配置 API。") }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLog.e("Live", "最近总结失败", t)
                    if (_state.value.sessionId == sessionAtStart && _state.value.summary.isBlank()) {
                        _state.update { it.copy(summary = "总结失败：${t.message?.take(60) ?: "未知错误"}") }
                    }
                } finally {
                    if (_state.value.sessionId == sessionAtStart) {
                        _state.update { it.copy(assistantBusy = false) }
                    }
                }
            }
        }
    }

    /**
     * 麦克风路由变化（插拔耳机、蓝牙切换、AUDIO_BECOMING_NOISY）后重建采集对象。
     * 方法本身串行执行，短时间内多次事件只重建一次。
     */
    @Volatile private var routeRestarting = false
    private suspend fun restartMicCapture(reason: String) {
        if (routeRestarting) return
        val st = _state.value
        val old = capture
        if (!st.running || st.stopping || st.paused || st.source != LiveSource.MIC || old == null) return
        routeRestarting = true
        try {
            val ctx = app.applicationContext
            val channel = audioChannel ?: return
            AppLog.d("Live", "麦克风路由变化（$reason），重建采集")
            runCatching { old.stop() }
            if (!_state.value.running || _state.value.stopping || audioChannel !== channel) return
            val fresh = com.localinterpreter.tablet.audio.MicCapture(
                ctx,
                { frames -> lastAudioAt = SystemClock.elapsedRealtime(); channel.trySend(frames) },
                { message ->
                    scope.launch {
                        if (_state.value.running) {
                            _state.update { it.copy(error = message) }
                        }
                    }
                },
            )
            fresh.start()
            // 防止重建期间 stop() 已结束会话，避免泄漏一个正在后台录音的 AudioRecord。
            if (!_state.value.running || _state.value.stopping || audioChannel !== channel) {
                runCatching { fresh.stop() }
                return
            }
            capture = fresh
            AppLog.d("Live", "麦克风采集已重建")
        } catch (t: Throwable) {
            AppLog.e("Live", "重建麦克风失败", t)
            _state.update { it.copy(error = "麦克风切换后无法继续录音：${t.message?.take(80) ?: ""}") }
        } finally {
            routeRestarting = false
        }
    }

    fun pause() {
        val st = _state.value
        if (!st.running || st.paused) return
        _state.update { it.copy(paused = true, status = "已暂停", speaking = false) }
        (capture as? FileAudioCapture)?.setPaused(true)
        draftGeneration++
        translationEpoch++
        if (draftInFlight.get() > 0 && !finalTranslating) app.llm.cancelTranslation()
    }

    fun resume() {
        val st = _state.value
        if (!st.running || !st.paused) return
        _state.update { it.copy(paused = false, status = "继续中") }
        (capture as? FileAudioCapture)?.setPaused(false)
    }

    fun stop() {
        val st = _state.value
        if (!st.running && !st.starting) return
        if (stopping) return
        stopping = true
        if (st.starting && st.sessionId == null) {
            startToken++
            startJob?.cancel()
            startJob = null
            runCatching { routeMonitor?.stop() }
            routeMonitor = null
            runCatching { capture?.stop() }
            capture = null
            partialInFlight = false
            _state.update {
                it.copy(
                    starting = false, running = false, stopping = false,
                    status = "已结束", audioLevel = 0f, speaking = false,
                )
            }
            stopping = false
            return
        }
        val stopJob = scope.launch {
          try {
            _state.update { it.copy(stopping = true, status = "正在收尾…") }
            app.llm.cancelAssistant()
            runCatching { routeMonitor?.stop() }
            routeMonitor = null
            runCatching { capture?.stop() }
            capture = null
            audioChannel?.close()
            audioChannel = null
            // 正常情况下 stop() 会关闭音频通道让 processAudio 退出；极端情况下卡在原生 VAD/解码里，
            // 不能无限等，否则界面会永远停在「正在收尾…」。
            val joined = withTimeoutOrNull(15_000) { sessionJob?.join(); true } ?: false
            if (!joined) AppLog.e("Live", "等待音频处理线程超时，继续收尾")
            sessionJob = null
            vad = null
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while ((finalPending.get() > 0 || translatePending.get() > 0) && SystemClock.elapsedRealtime() < deadline) {
                delay(120)
            }
            val sid = _state.value.sessionId
            AppLog.d("Live", "结束会话 id=$sid segments=${_state.value.segments.size} pendingFinal=${finalPending.get()} pendingTranslate=${translatePending.get()}")
            if (sid != null) {
                val segments = _state.value.segments
                val first = segments.firstOrNull { it.id > 0 }
                val busy = finalPending.get() > 0 || translatePending.get() > 0
                if (first == null) {
                    if (busy) {
                        // 还有识别/翻译在途，保留课次，避免后续字幕写进已删除的会话。
                        runCatching {
                            withContext(Dispatchers.IO) { app.db.endSession(sid, "", _state.value.summary, "finished") }
                        }.onFailure { AppLog.e("Live", "结束空课次写入失败", it) }
                    } else {
                        runCatching {
                            withContext(Dispatchers.IO) { app.repo.deleteSession(sid, force = true) }
                        }.onFailure { AppLog.e("Live", "清理空课次失败", it) }
                    }
                } else {
                    val title = if (first.tgt.isNotBlank()) first.tgt.take(40) else first.src.take(40)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            app.db.endSession(sid, title, _state.value.summary, "finished")
                        }
                    }.onFailure { AppLog.e("Live", "结束会话写入失败", it) }
                }
            }
            draftGeneration++
            translationEpoch++
            partialInFlight = false
            if (backgroundTasks.get() > 0 && !finalTranslating) app.llm.cancelTranslation()
            val finishedSid = sid
            _state.update {
                it.copy(
                    running = false, starting = false, stopping = false, paused = false,
                    status = "已结束", speaking = false, audioLevel = 0f,
                    partialSrc = "", partialTgt = "", partialDraft = false,
                )
            }
            stopping = false
            if (finishedSid != null) {
                scope.launch { postProcess(finishedSid) }
            }
          } catch (t: Throwable) {
            AppLog.e("Live", "结束会话异常", t)
            _state.update {
                it.copy(
                    running = false, starting = false, stopping = false, paused = false,
                    status = "已结束（收尾异常）", speaking = false, audioLevel = 0f,
                    partialSrc = "", partialTgt = "", partialDraft = false,
                    error = "结束会话时出错：${t.message?.take(80) ?: "未知错误"}",
                )
            }
          }
        }
        stopJob.invokeOnCompletion {
            stopping = false
            _state.update { if (it.stopping) it.copy(stopping = false) else it }
        }
    }

    private suspend fun postProcess(sessionId: Long) {
        try {
            if (_state.value.running || _state.value.starting) return
            val settings = app.settings.value
            val apiReady = settings.analysisMode == SettingsData.MODE_API &&
                (settings.analysisBaseUrl.ifBlank { settings.apiBaseUrl }).isNotBlank() &&
                (settings.analysisModel.ifBlank { settings.apiModel }).isNotBlank()
            val assistantReady = app.models.assistantReady(settings.assistantVariant) || apiReady
            if (!assistantReady) return
            val assistant = CourseAssistant(app)
            val sessionCourseId = withContext(Dispatchers.IO) { app.db.getSession(sessionId)?.courseId }
            val count = withContext(Dispatchers.IO) { app.db.listSegments(sessionId).size }
            if (count >= 3 && !_state.value.running && !_state.value.starting) {
                val summary = assistant.summarizeSession(sessionId)
                if (summary.isNotBlank()) {
                    withContext(Dispatchers.IO) { app.db.updateSessionSummary(sessionId, summary) }
                    _state.update { if (it.sessionId == sessionId) it.copy(summary = summary) else it }
                }
                if (!_state.value.running && !_state.value.starting) {
                    val title = assistant.titleFor(sessionId)
                    if (title.isNotBlank()) withContext(Dispatchers.IO) { app.db.updateSessionTitleIfNotUser(sessionId, title) }
                }
            }
            val cid = sessionCourseId
            if (cid != null && settings.autoTerms && !_state.value.running && !_state.value.starting) {
                val pairs = assistant.extractTerms(sessionId)
                if (pairs.isNotEmpty()) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val existing = app.db.listGlossary(cid).map { it.term }.toSet()
                            pairs.filter { it.first !in existing }.take(8).forEach {
                                app.db.addGlossary(cid, it.first, it.second)
                            }
                        }
                    }.onFailure { AppLog.e("Live", "保存自动术语失败", it) }
                }
            }
            if (settings.cloudAutoSync && settings.cloudSyncUrl.isNotBlank()) {
                app.cloudSync.syncNow()
            }
        } catch (_: Throwable) {
        }
    }

    fun restoreInterrupted(sessionId: Long) {
        if (_state.value.running || _state.value.starting || _state.value.sessionId != null) return
        startWorkers()
        try {
            val session = app.db.getSession(sessionId) ?: return
            if (System.currentTimeMillis() - session.startedAt > 24L * 3600 * 1000) return
            val segs = app.db.listSegments(sessionId)
            if (segs.isEmpty()) return
            val speakers = app.db.listSpeakers(sessionId).associateBy { it.id }
            val bookmarkedIds = app.db.listBookmarks(sessionId).map { it.segmentId }.toSet()
            val questions = app.db.listQuestions(sessionId).map { q ->
                LiveQuestion(
                    id = q.id,
                    dbId = q.id,
                    question = q.question,
                    questionTgt = q.questionTgt,
                    answer = q.answer,
                    answerAlt = q.answerAlt,
                    source = q.source,
                    pending = false,
                )
            }
            val summary = app.db.latestSummary(sessionId)?.content ?: session.summary
            this.courseId = session.courseId
            this.sourceLangPref = session.sourceLang
            this.targetLangPref = session.targetLang
            this.glossaryText = if (session.courseId != null) app.db.glossaryPrompt(session.courseId) else ""
            this.speakers.clear()
            val embeddings = app.db.speakerEmbeddings(sessionId).toMap()
            this.speakers.addAll(
                app.db.listSpeakers(sessionId).mapNotNull { sp ->
                    val emb = embeddings[sp.id]
                    if (emb != null) LiveSpeaker(sp.id, sp.label, sp.color, emb) else null
                }
            )
            val restoredSource = LiveSource.entries.firstOrNull { it.id == session.audioSource } ?: LiveSource.MIC
            val restored = LiveState(
                running = false,
                starting = false,
                paused = false,
                sessionId = sessionId,
                courseId = session.courseId,
                source = restoredSource,
                startedAt = session.startedAt,
                segments = segs.map { g ->
                    val sp = g.speakerId?.let { speakers[it] }
                    LiveSegment(
                        id = g.id,
                        t0 = g.t0,
                        t1 = g.t1,
                        srcLang = g.srcLang,
                        src = g.srcText,
                        tgt = if (g.tgtText.isBlank()) "（未翻译，点「补译」）" else g.tgtText,
                        speaker = sp?.label,
                        speakerColor = sp?.color,
                        lowConfidence = g.lowConfidence,
                        audioPath = g.audioPath,
                        speakerId = g.speakerId,
                        bookmarked = g.id in bookmarkedIds,
                    )
                },
                summary = summary,
                questions = questions,
                status = "上次会话未正常结束，记录已恢复",
            )
            synchronized(sessionStartLock) {
                if (_state.value.running || _state.value.starting || _state.value.sessionId != null) return
                _state.value = restored
            }
            AppLog.d("Live", "恢复未结束会话 id=$sessionId segments=${segs.size}")
        } catch (t: Throwable) {
            AppLog.e("Live", "恢复会话失败 id=$sessionId", t)
        }
    }

    fun retranslateMissing() {
        val st = _state.value
        if (st.running || st.starting || translatePending.get() > 0) return
        val sid = st.sessionId ?: return
        val missing = st.segments.filter {
            it.id > 0 && (it.tgt.isBlank() || it.tgt.startsWith("（翻译失败") || it.tgt.startsWith("（未翻译"))
        }
        if (missing.isEmpty()) return
        val config = app.settings.value
        val target = config.targetLang.ifBlank { targetLangPref }
        missing.forEach { seg ->
            updateSegment(seg.id) { it.copy(tgt = "译文中…", draftTgt = false) }
            translatePending.incrementAndGet()
            translateChannel.trySend(
                TranslateJob(
                    sid, seg.id, seg.src, seg.srcLang.ifBlank { guessLanguage(seg.src) },
                    SystemClock.elapsedRealtime(), target, glossaryText, courseId, config,
                )
            )
        }
        refreshPendingQueue()
        draftGeneration++
        translationEpoch++
        _state.update { it.copy(status = "正在补译 ${missing.size} 句…") }
    }

    fun clear() {
        synchronized(sessionStartLock) {
            if (_state.value.running || _state.value.starting || _state.value.stopping) return
            _state.value = LiveState()
        }
        lastPartialText = ""
        lastPartialAt = 0
        lastDraftAt = 0
    }

    fun dismissError() {
        _state.update { if (it.error == null) it else it.copy(error = null) }
    }

    /** 模型自检会另开一个 SenseVoice 实例；先释放主流程缓存的识别器，避免同时占用两份内存。 */
    fun releaseAsrCache() {
        if (_state.value.running || _state.value.starting) return
        asr.release()
    }

    /**
     * 系统内存紧张时调用：先丢掉非必要的声纹模型；如果正在录音且内存极低，
     * 再把助教模型卸载（下次提问会重新加载）。界面会说明降级了什么。
     */
    fun onMemoryPressure(level: Int) {
        val running = _state.value.running
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching { speakerEngine.release() }
            AppLog.d("Memory", "内存紧张（level=$level），已释放声纹模型")
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            app.llm.unloadAssistant()
            AppLog.d("Memory", "内存极低（level=$level），已释放助教模型")
            if (running) {
                _state.update {
                    it.copy(error = "设备内存不足：已释放助教模型，问答可能变慢；建议改用轻量模型或结束其它应用")
                }
            }
        }
    }

    /** 前台服务/权限等启动失败时，把错误显示到主界面。 */
    fun reportError(message: String) {
        _state.update {
            it.copy(starting = false, running = false, stopping = false, status = "已结束", error = message)
        }
    }

    fun bookmark(): Boolean {
        val st = _state.value
        val sid = st.sessionId ?: return false
        val seg = st.segments.lastOrNull { it.id > 0 } ?: return false
        if (!seg.bookmarked) {
            _state.update { cur ->
                cur.copy(segments = cur.segments.map {
                    if (it.id == seg.id) it.copy(bookmarked = true) else it
                })
            }
            scope.launch(Dispatchers.IO) {
                runCatching { app.db.addBookmark(sid, seg.id) }
                    .onFailure { AppLog.e("Live", "保存标记失败", it) }
            }
        }
        return true
    }

    /** 切换单句的书签状态；返回切换后的状态（true=已标记，false=已取消），找不到句子返回 null。 */
    fun toggleBookmarkSegment(segmentId: Long): Boolean? {
        val st = _state.value
        val sid = st.sessionId ?: return null
        val seg = st.segments.firstOrNull { it.id == segmentId && it.id > 0 } ?: return null
        val next = !seg.bookmarked
        _state.update { cur ->
            cur.copy(segments = cur.segments.map {
                if (it.id == seg.id) it.copy(bookmarked = next) else it
            })
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (next) app.db.addBookmark(sid, seg.id) else app.db.removeBookmark(sid, seg.id)
            }.onFailure { AppLog.e("Live", "更新标记失败", it) }
        }
        return next
    }

    fun updateAssistantEnabled(enabled: Boolean) {
        app.settings.update { it.copy(assistantEnabled = enabled) }
    }

    fun renameSpeaker(speakerId: Long, label: String) {
        val clean = label.trim()
        if (clean.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { app.db.renameSpeaker(speakerId, clean) }
                .onFailure { AppLog.e("Live", "重命名说话人失败", it) }
            val old = speakers.firstOrNull { it.id == speakerId }?.label
            speakers.indexOfFirst { it.id == speakerId }.takeIf { it >= 0 }?.let { idx ->
                speakers[idx] = speakers[idx].copy(label = clean)
            }
            _state.update { st ->
                st.copy(segments = st.segments.map {
                    if (it.speakerId == speakerId || (old != null && it.speaker == old)) {
                        it.copy(speaker = clean)
                    } else it
                })
            }
        }
    }

    /** 课中/课后单句快速重译（可带入最新术语表与目标语言）。 */
    fun retranslateSegment(segmentId: Long): Boolean {
        val st = _state.value
        val sid = st.sessionId ?: return false
        val seg = st.segments.lastOrNull { it.id == segmentId } ?: return false
        if (seg.src.isBlank() || seg.id <= 0) return false
        val config = app.settings.value
        val target = config.targetLang.ifBlank { targetLangPref }
        updateSegment(seg.id) { it.copy(tgt = "译文中…", draftTgt = false) }
        translatePending.incrementAndGet()
        refreshPendingQueue()
        translateChannel.trySend(
            TranslateJob(
                sid, seg.id, seg.src, seg.srcLang.ifBlank { guessLanguage(seg.src) },
                SystemClock.elapsedRealtime(), target, glossaryText, courseId, config,
            )
        )
        draftGeneration++
        translationEpoch++
        return true
    }

    /** 修正单句识别原文并立即保存、触发重新翻译。 */
    fun editAndRetranslateSegment(segmentId: Long, newSource: String): Boolean {
        val clean = newSource.trim()
        if (clean.isBlank()) return false
        val st = _state.value
        val sid = st.sessionId ?: return false
        val seg = st.segments.lastOrNull { it.id == segmentId } ?: return false
        if (seg.id <= 0) return false
        val lang = seg.srcLang.ifBlank { guessLanguage(clean) }
        updateSegment(seg.id) { it.copy(src = clean, tgt = "译文中…", corrected = true, draftTgt = false) }
        scope.launch(Dispatchers.IO) {
            runCatching { app.db.updateSegmentSource(seg.id, clean) }
                .onFailure { AppLog.e("Live", "保存修改原文失败", it) }
        }
        val config = app.settings.value
        val target = config.targetLang.ifBlank { targetLangPref }
        translatePending.incrementAndGet()
        refreshPendingQueue()
        translateChannel.trySend(
            TranslateJob(
                sid, seg.id, clean, lang, SystemClock.elapsedRealtime(),
                target, glossaryText, courseId, config,
            )
        )
        draftGeneration++
        translationEpoch++
        return true
    }

    /** 同传进行中向当前绑定课程快速添加术语，立即刷新内存术语表，下一句翻译立刻生效。 */
    fun addLiveGlossaryTerm(term: String, translation: String, onDone: (Boolean) -> Unit = {}) {
        val cid = courseId ?: _state.value.courseId
        val cleanTerm = term.trim()
        val cleanTrans = translation.trim()
        if (cid == null || cleanTerm.isBlank() || cleanTrans.isBlank()) {
            onDone(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                app.db.addGlossary(cid, cleanTerm, cleanTrans)
                glossaryText = app.db.glossaryPrompt(cid)
            }.onFailure { AppLog.e("Live", "实时添加术语失败", it) }.isSuccess
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    private fun updateSegment(id: Long, block: (LiveSegment) -> LiveSegment) {
        _state.update { st ->
            val idx = st.segments.indexOfLast { it.id == id }
            if (idx < 0) return@update st
            val updated = block(st.segments[idx])
            if (updated == st.segments[idx]) return@update st
            val copy = st.segments.toMutableList()
            copy[idx] = updated
            st.copy(segments = copy)
        }
    }

    private fun updateQuestion(localId: Long, block: (LiveQuestion) -> LiveQuestion) {
        _state.update { st ->
            val idx = st.questions.indexOfFirst { it.id == localId }
            if (idx < 0) return@update st
            val copy = st.questions.toMutableList()
            copy[idx] = block(copy[idx])
            st.copy(questions = copy)
        }
    }

    private fun guessLanguage(text: String): String =
        com.localinterpreter.tablet.llm.Langs.guessLanguage(text)

    /**
     * 过滤 SenseVoice 在无人说话或轻微环境杂音（空调、翻页、敲击、远场底噪）下产生的乱码与固定幻觉。
     * 结合 SenseVoice 事件标签、音频能量、时长及重复/单字特征综合判定，尽量做到“杂音不乱出字，正常说话不漏字”。
     */
    private fun isHallucination(
        text: String,
        energy: Float,
        durationSec: Double,
        event: String,
        fileMode: Boolean,
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val stripped = trimmed.filter { it.isLetterOrDigit() }
        // 纯标点、空白或音乐符号（如 "。"、"..."、"♪"）直接丢弃。
        if (stripped.isEmpty()) return true

        val halluEnergy = noiseProfile().hallucinationEnergy

        // SenseVoice 显式标记为非语音事件（BGM/掌声/笑声/咳嗽/喷嚏/噪声等）时，短片段直接视为杂音。
        val isNonSpeechEvent = event.isNotBlank() &&
            !event.equals("Speech", ignoreCase = true) &&
            !event.equals("Event_UNK", ignoreCase = true)
        if (isNonSpeechEvent) {
            if (stripped.length <= 4) return true
            if (energy < halluEnergy && stripped.length <= 8) return true
        }

        // 连续单字符重复幻觉（如 "啊啊啊啊啊"、"嗯嗯嗯嗯嗯"、"eeeee"）。
        if (stripped.length >= 4) {
            var maxRun = 1
            var run = 1
            for (i in 1 until stripped.length) {
                if (stripped[i].lowercaseChar() == stripped[i - 1].lowercaseChar() && !stripped[i].isDigit()) {
                    run++
                    if (run > maxRun) maxRun = run
                } else {
                    run = 1
                }
            }
            if (maxRun >= 5 && maxRun >= stripped.length * 0.6) return true
        }

        // 连续重复同一个短词（如 "the the the the" / "la la la la"）。
        val words = trimmed.lowercase().split(Regex("[\\s,，.。!！?？]+")).filter { it.isNotEmpty() }
        if (words.size >= 4 && words.distinct().size == 1 && words[0].length <= 4) {
            return true
        }

        // 文件转写通常是清晰录制的音频，仅拦截上述明显纯符号/非语音/死循环幻觉，避免误伤。
        if (fileMode) return false

        // 单个英文字母（如 "a" / "t" / "o"）在实时同传里几乎必然是杂音误识。
        if (stripped.length == 1 && (stripped[0] in 'a'..'z' || stripped[0] in 'A'..'Z')) {
            return true
        }

        // 单个汉字/假名/韩文在偏低能量或极短片段下多为杂音幻觉；大声清晰说出仍放行。
        if (stripped.length == 1 && (energy < halluEnergy * 0.95f || durationSec < 0.65)) {
            return true
        }

        // SenseVoice 在轻微底噪下高频吐出的固定语气词/单字幻觉：仅当能量偏低、过短或非 Speech 事件时丢弃。
        val normalized = stripped.lowercase()
        if (normalized in NOISE_HALLUCINATIONS &&
            (energy < halluEnergy || durationSec < 0.8 || isNonSpeechEvent)
        ) {
            return true
        }

        return false
    }

    private fun looksLowConfidence(text: String): Boolean {
        if (text.length < 2) return true
        var run = 1
        for (i in 1 until text.length) {
            if (text[i] == text[i - 1]) {
                run++
                if (run >= 5) return true
            } else run = 1
        }
        if (text.length < 6 && !text.any { it in "，。！？,.?!" }) return true
        return false
    }

    private fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains("？") || t.contains("?")) return true
        val marks = listOf("吗", "呢", "为什么", "为何", "如何", "是否", "谁", "哪里", "哪种", "多少", "几个", "几种", "能不能", "可不可以", "有没有", "是不是")
        if (marks.any { t.contains(it) }) return true
        return EN_QUESTION_REGEX.containsMatchIn(t.lowercase())
    }

    companion object {
        private val EN_QUESTION_REGEX = Regex(
            "^\\s*(so |and |but )?(what|why|how|when|where|who|which|can|could|should|do|does|did|is|are|will|would)\\b"
        )
        private val speakerColors = listOf("#4fd1a5", "#7aa2ff", "#f0b45c", "#e07ad1", "#8bd46a", "#f07070")
        /** 小于 0.4 秒（16k 采样）的 VAD 片段先攒着，与下一句合并后再识别。 */
        private const val MIN_SEGMENT_SAMPLES = 6400
        /** 尾巴/超时刷出碎片的最短时长（0.28 秒），短于此多为瞬时杂音。 */
        private const val MIN_CARRY_FLUSH_SAMPLES = 4480
        /** 缓冲内至少包含 14 帧（约 0.45 秒）真实人声窗口，才允许兜底提交。 */
        private const val MIN_SPEECH_WINDOWS = 14
        /** 预览识别至少需要 12 帧（约 0.38 秒）真实人声窗口，避免轻微杂音触发预览幻觉。 */
        private const val MIN_PARTIAL_SPEECH_WINDOWS = 12
        private const val DRAFT_INTERVAL_MS = 1200L
        private const val DRAFT_MAX_TOKENS = 32
        private const val DRAFT_MAX_CHARS = 72
        private val NOISE_HALLUCINATIONS = setOf(
            "嗯", "啊", "哦", "呃", "诶", "哎", "呀", "吧", "呢", "嘛", "哈", "呵", "哼",
            "唔", "噢", "喂", "的", "了", "一", "是", "不", "好", "对", "是的", "嗯嗯", "啊啊",
            "uh", "um", "ah", "oh", "eh", "hm", "hmm", "mm", "mmm", "mhm",
            "yeah", "yea", "yep", "yes", "no", "the", "it", "is", "you", "so", "and", "or",
            "but", "hey", "hi", "ok", "okay", "thankyou", "thanks",
            "あ", "え", "お", "ん", "う", "はい", "ええ", "あの",
            "어", "아", "음", "응", "네", "예",
        )
    }
}
