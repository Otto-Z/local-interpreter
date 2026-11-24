package com.localinterpreter.tablet.audio

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.localinterpreter.tablet.util.AppLog
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.max

interface AudioCapture {
    fun start()
    fun stop()
    val label: String
}

/**
 * 连续流式重采样器：跨 read() 缓冲区保持相位和余数样本，消除 48k→16k 每帧丢 2 样本产生的
 * 周期性咔嗒声与混叠噪声。48k→16k（整 3 倍）使用 3 点盒式均值抗混叠降采样，既更清晰又更快。
 */
internal class StreamResampler(private val from: Int, private val to: Int) {
    private val exactRatio = if (from > to && from % to == 0) from / to else 0
    private var accSum = 0.0
    private var accCount = 0
    private var hasPrev = false
    private var prevSample = 0f
    private var phase = 0.0

    fun process(src: ShortArray, n: Int): FloatArray {
        if (n <= 0 || from <= 0 || to <= 0) return FloatArray(0)
        if (from == to) return FloatArray(n) { src[it] / 32768f }
        if (exactRatio > 1) {
            val total = accCount + n
            val outLen = total / exactRatio
            val out = FloatArray(outLen)
            var outIdx = 0
            var sum = accSum
            var cnt = accCount
            val invScale = 1.0 / (exactRatio * 32768.0)
            for (i in 0 until n) {
                sum += src[i].toDouble()
                cnt++
                if (cnt == exactRatio) {
                    out[outIdx++] = (sum * invScale).toFloat()
                    sum = 0.0
                    cnt = 0
                }
            }
            accSum = sum
            accCount = cnt
            return out
        }
        val step = from.toDouble() / to.toDouble()
        val estOut = ((n / step) + 4).toInt().coerceAtLeast(1)
        val out = FloatArray(estOut)
        var outIdx = 0
        var pos = phase
        while (pos < n) {
            val sample = if (pos < 0.0) {
                val frac = pos + 1.0
                val s0 = if (hasPrev) prevSample else src[0] / 32768f
                val s1 = src[0] / 32768f
                (s0 * (1.0 - frac) + s1 * frac).toFloat()
            } else {
                val idx = pos.toInt()
                val next = idx + 1
                if (next >= n) break
                val frac = pos - idx
                ((src[idx] * (1.0 - frac) + src[next] * frac) / 32768.0).toFloat()
            }
            if (outIdx < out.size) out[outIdx++] = sample
            pos += step
        }
        prevSample = src[n - 1] / 32768f
        hasPrev = true
        phase = pos - n
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    fun processFloat(src: FloatArray, n: Int = src.size): FloatArray {
        if (n <= 0 || from <= 0 || to <= 0) return FloatArray(0)
        if (from == to) return if (n == src.size) src else src.copyOf(n)
        if (exactRatio > 1) {
            val total = accCount + n
            val outLen = total / exactRatio
            val out = FloatArray(outLen)
            var outIdx = 0
            var sum = accSum
            var cnt = accCount
            val invScale = 1.0 / exactRatio
            for (i in 0 until n) {
                sum += src[i].toDouble()
                cnt++
                if (cnt == exactRatio) {
                    out[outIdx++] = (sum * invScale).toFloat()
                    sum = 0.0
                    cnt = 0
                }
            }
            accSum = sum
            accCount = cnt
            return out
        }
        val step = from.toDouble() / to.toDouble()
        val estOut = ((n / step) + 4).toInt().coerceAtLeast(1)
        val out = FloatArray(estOut)
        var outIdx = 0
        var pos = phase
        while (pos < n) {
            val sample = if (pos < 0.0) {
                val frac = pos + 1.0
                val s0 = if (hasPrev) prevSample else src[0]
                val s1 = src[0]
                (s0 * (1.0 - frac) + s1 * frac).toFloat()
            } else {
                val idx = pos.toInt()
                val next = idx + 1
                if (next >= n) break
                val frac = pos - idx
                (src[idx] * (1.0 - frac) + src[next] * frac).toFloat()
            }
            if (outIdx < out.size) out[outIdx++] = sample
            pos += step
        }
        prevSample = src[n - 1]
        hasPrev = true
        phase = pos - n
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }
}

class MicCapture(
    context: Context,
    private val onFrames: (FloatArray) -> Unit,
    private val onError: (String) -> Unit = {},
    private val source: Int = MediaRecorder.AudioSource.MIC,
    private val preferredRate: Int = 48000,
) : AudioCapture {
    override val label = "麦克风"
    private val appContext = context.applicationContext
    private val targetRate = 16000
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    /** 采集线程持有它自己创建的 AudioRecord；stop() 只发停止信号，释放由线程退出时完成。 */
    private val releaseLock = Any()

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val opMode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_RECORD_AUDIO,
                Process.myUid(),
                appContext.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_ALLOWED)
        AppLog.d(
            "Audio",
            "麦克风检查 source=$source rate=$preferredRate appOps=$opMode micMute=${runCatching { audioManager.isMicrophoneMute }.getOrDefault(false)}",
        )
        if (runCatching { audioManager.isMicrophoneMute }.getOrDefault(false)) {
            throw RuntimeException("系统麦克风处于静音状态：请在控制中心/系统设置中打开麦克风")
        }
        if (opMode == AppOpsManager.MODE_IGNORED) {
            throw RuntimeException("系统隐私设置禁止了麦克风（AppOps=ignored）：请在系统设置中允许本地同传使用麦克风")
        }
        // 部分 HyperOS/Android 16 机型上 VOICE_RECOGNITION + 16k 会返回全零；
        // 改用通用 MIC 音源，并用设备普遍支持的 48k/44.1k 采集后重采样到 16k。
        var rate = preferredRate
        var minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0 && rate != 44100) {
            rate = 44100
            minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
        }
        if (minBuf <= 0) {
            rate = 16000
            minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
        }
        if (minBuf <= 0) throw RuntimeException("麦克风不支持可用的采样率")
        val bufSize = max(minBuf * 2, rate / 4 * 2)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw RuntimeException("麦克风初始化失败，请检查录音权限")
        }
        val actualRate = if (rec.sampleRate > 0) rec.sampleRate else rate
        // 优先启用系统 DSP 硬件降噪（零 CPU 占用），显著压制空调/风扇/轻微环境底噪，
        // 防止无人说话时杂音触发 VAD 或诱导 SenseVoice 产生乱码幻觉。
        val suppressor = runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            } else null
        }.getOrNull()
        record = rec
        running = true
        AppLog.d(
            "Audio",
            "麦克风采集开始 source=$source rate=$actualRate buffer=$bufSize ns=${suppressor?.enabled == true}",
        )
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            running = false
            record = null
            runCatching { suppressor?.release() }
            runCatching { rec.release() }
            throw RuntimeException("麦克风启动失败：${t.message ?: "请检查录音权限"}", t)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            record = null
            runCatching { suppressor?.release() }
            runCatching { rec.release() }
            throw RuntimeException("麦克风没有进入录音状态：可能被系统隐私开关或其它应用阻止")
        }
        AppLog.d(
            "Audio",
            "麦克风路由 device=${runCatching { rec.routedDevice?.productName }.getOrNull() ?: "-"} " +
                "type=${runCatching { rec.routedDevice?.type }.getOrNull() ?: -1}",
        )
        thread = thread(name = "mic-capture", isDaemon = true) {
            val shorts = ShortArray(2048)
            val resampler = StreamResampler(actualRate, targetRate)
            try {
                while (running) {
                    val n = try {
                        rec.read(shorts, 0, shorts.size)
                    } catch (t: Throwable) {
                        // 录音对象被系统回收/权限被撤销时会抛异常；上报后退出，不再在坏对象上循环。
                        if (running) onError("麦克风读取异常：${t.message ?: "录音设备已断开"}")
                        break
                    }
                    if (n > 0) {
                        val out = resampler.process(shorts, n)
                        if (out.isNotEmpty()) {
                            try {
                                onFrames(out)
                            } catch (t: Throwable) {
                                if (running) onError("麦克风数据处理失败：${t.message ?: "未知错误"}")
                                break
                            }
                        }
                    } else if (n < 0) {
                        if (running) onError("麦克风读取失败（错误码 $n），请检查权限或录音设备")
                        break
                    }
                }
            } finally {
                // 采集线程退出后自己释放，保证不会出现「还在 read，对象已被 stop() 释放」。
                synchronized(releaseLock) {
                    runCatching { suppressor?.release() }
                    runCatching { rec.release() }
                }
            }
        }
    }

    override fun stop() {
        running = false
        val rec = record
        val t = thread
        record = null
        thread = null
        // 先 stop() 解除 read() 阻塞并唤醒采集线程。
        runCatching { if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
        if (t != null && Thread.currentThread() !== t) {
            // 最多等 2 秒；即使超时也绝不在这里 release —— 采集线程退出的 finally 会释放，
            // 否则会出现在已释放的 AudioRecord 上继续 read 而闪退（A1）。
            runCatching { t.join(2000) }
            if (t.isAlive) {
                AppLog.e("Audio", "采集线程 2 秒内未退出，交由线程自行释放 AudioRecord")
            }
        }
    }
}

class SystemAudioCapture(
    context: Context,
    private val projection: MediaProjection,
    private val onFrames: (FloatArray) -> Unit,
    private val onProjectionStopped: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) : AudioCapture {
    override val label = "系统音频"
    private val captureRate = 48000
    private val targetRate = 16000
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var callback: MediaProjection.Callback? = null
    @Volatile private var stopping = false
    private val releaseLock = Any()

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            captureRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf * 2, captureRate / 4 * 2)
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(captureRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw RuntimeException("系统音频初始化失败；请确认已在弹窗中选择「开始录制」")
        }
        record = rec
        running = true
        val actualRate = if (rec.sampleRate > 0) rec.sampleRate else captureRate
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                val wasStopping = stopping
                // 回调在主线程；stop() 里会等采集线程，放到后台避免阻塞 UI。
                thread(name = "projection-stop", isDaemon = true) {
                    stop()
                    if (!wasStopping) onProjectionStopped()
                }
            }
        }
        callback = cb
        projection.registerCallback(cb, Handler(Looper.getMainLooper()))
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            running = false
            record = null
            runCatching { projection.unregisterCallback(cb) }
            callback = null
            runCatching { rec.release() }
            throw RuntimeException("系统音频启动失败：${t.message ?: "请确认录屏授权"}", t)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            record = null
            runCatching { projection.unregisterCallback(cb) }
            callback = null
            runCatching { rec.release() }
            throw RuntimeException("系统音频未进入录制状态：请确认录屏授权且没有其它应用独占内录")
        }
        thread = thread(name = "system-audio-capture", isDaemon = true) {
            AppLog.d("Audio", "系统音频采集开始 capture=$captureRate actual=$actualRate buf=$bufSize")
            val shorts = ShortArray(2048)
            val resampler = StreamResampler(actualRate, targetRate)
            try {
                while (running) {
                    val n = try {
                        rec.read(shorts, 0, shorts.size)
                    } catch (t: Throwable) {
                        if (running) onError("系统音频读取异常：${t.message ?: "录屏授权可能已撤回"}")
                        break
                    }
                    if (n > 0) {
                        val out = resampler.process(shorts, n)
                        if (out.isNotEmpty()) {
                            try {
                                onFrames(out)
                            } catch (t: Throwable) {
                                if (running) onError("系统音频处理失败：${t.message ?: "未知错误"}")
                                break
                            }
                        }
                    } else if (n < 0) {
                        if (running) onError("系统音频读取失败（错误码 $n），请重新开始并确认录屏授权")
                        break
                    }
                }
            } finally {
                // 同 MicCapture：释放由采集线程完成，stop() 不再释放正在被 read 的对象。
                synchronized(releaseLock) {
                    runCatching { rec.release() }
                }
            }
        }
    }

    override fun stop() {
        synchronized(this) {
            if (stopping) return
            stopping = true
        }
        running = false
        val rec = record
        val t = thread
        record = null
        thread = null
        // 先 stop() 解除 read() 阻塞并唤醒采集线程。
        runCatching { if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
        if (t != null && Thread.currentThread() !== t) {
            runCatching { t.join(2000) }
            if (t.isAlive) {
                AppLog.e("Audio", "系统音频采集线程 2 秒内未退出，交由线程自行释放 AudioRecord")
            }
        }
        callback?.let {
            try {
                projection.unregisterCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        // 采集线程已退出时这里释放是安全的；仍在跑时由线程 finally 负责，避免重复释放。
        if (t == null || !t.isAlive) {
            synchronized(releaseLock) {
                runCatching { rec?.release() }
            }
        }
        try {
            projection.stop()
        } catch (_: Exception) {
        }
        stopping = false
    }
}

object WavUtil {
    fun write(file: File, samples: FloatArray, sampleRate: Int = 16000) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val dataLen = samples.size * 2
            val header = ByteArray(44)
            fun putInt(off: Int, v: Int) {
                header[off] = (v and 0xff).toByte()
                header[off + 1] = ((v shr 8) and 0xff).toByte()
                header[off + 2] = ((v shr 16) and 0xff).toByte()
                header[off + 3] = ((v shr 24) and 0xff).toByte()
            }
            fun putShort(off: Int, v: Int) {
                header[off] = (v and 0xff).toByte()
                header[off + 1] = ((v shr 8) and 0xff).toByte()
            }
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            putInt(4, 36 + dataLen)
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, 1)
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2)
            putShort(32, 2)
            putShort(34, 16)
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            putInt(40, dataLen)
            raf.write(header)
            val buf = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                buf[i * 2] = (v and 0xff).toByte()
                buf[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            raf.write(buf)
        }
    }
}

data class NoiseCalibrationResult(
    val ambientRms: Float,
    val p90Rms: Float,
    val dbfs: Int,
    val recommendedMode: String,
    val windowCount: Int,
)

/**
 * 2 秒环境噪声校准：直接复用 MicCapture（含系统硬件 NoiseSuppressor 与重采样）采样当前环境底噪，
 * 剔除前 250ms 点击杂音及瞬时毛刺后计算稳态底噪基线，并给出最匹配的抗噪档位推荐。
 */
object NoiseCalibrator {
    suspend fun measure(
        context: Context,
        durationMs: Long = 2000L,
        onProgress: (Float) -> Unit = {},
    ): NoiseCalibrationResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val perm = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        )
        if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw RuntimeException("尚未授予麦克风权限，请先允许录音权限后再校准")
        }
        val frameRms = java.util.Collections.synchronizedList(mutableListOf<Float>())
        val captureError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val windowSize = 512
        val acc = FloatArray(windowSize)
        var filled = 0

        val cap = MicCapture(
            context = context,
            onFrames = { frames ->
                // 跳过前 250ms，避免用户手指点击校准按钮的敲击声污染底噪统计。
                if (android.os.SystemClock.elapsedRealtime() - startedAt < 250L) return@MicCapture
                var off = 0
                while (off < frames.size) {
                    val take = minOf(windowSize - filled, frames.size - off)
                    System.arraycopy(frames, off, acc, filled, take)
                    filled += take
                    off += take
                    if (filled == windowSize) {
                        var sum = 0.0
                        for (v in acc) sum += v.toDouble() * v
                        frameRms.add(kotlin.math.sqrt(sum / windowSize).toFloat())
                        filled = 0
                    }
                }
            },
            onError = { msg -> captureError.compareAndSet(null, msg) },
        )
        cap.start()
        try {
            val total = durationMs.coerceIn(1000L, 4000L)
            while (true) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= total || captureError.get() != null) break
                onProgress((elapsed.toFloat() / total).coerceIn(0f, 1f))
                kotlinx.coroutines.delay(80L)
            }
            onProgress(1f)
        } finally {
            runCatching { cap.stop() }
        }
        captureError.get()?.let { throw RuntimeException(it) }
        val sorted = synchronized(frameRms) { frameRms.toList() }.sorted()
        if (sorted.size < 8) {
            throw RuntimeException("没有采到有效音频数据，请检查麦克风是否被静音或被其它应用占用")
        }
        // 取中位数作为稳态环境底噪，90% 分位用于识别高波动杂音环境。
        val median = sorted[sorted.size / 2]
        val p90 = sorted[((sorted.size - 1) * 0.90f).toInt()]
        val floor = median.coerceIn(0.0010f, 0.025f)
        val dbfs = (20.0 * kotlin.math.log10(floor.toDouble().coerceAtLeast(1e-6))).toInt()
        val mode = when {
            floor < 0.0022f && p90 < 0.0045f -> com.localinterpreter.tablet.data.SettingsData.NOISE_SENSITIVE
            floor > 0.0065f || p90 > 0.013f -> com.localinterpreter.tablet.data.SettingsData.NOISE_STRICT
            else -> com.localinterpreter.tablet.data.SettingsData.NOISE_STANDARD
        }
        AppLog.d(
            "Audio",
            "环境测噪校准完成 windows=${sorted.size} median=${"%.4f".format(median)} " +
                "p90=${"%.4f".format(p90)} dbfs=$dbfs recommended=$mode",
        )
        NoiseCalibrationResult(
            ambientRms = floor,
            p90Rms = p90,
            dbfs = dbfs,
            recommendedMode = mode,
            windowCount = sorted.size,
        )
    }
}
