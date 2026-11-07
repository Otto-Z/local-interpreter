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
        record = rec
        running = true
        AppLog.d("Audio", "麦克风采集开始 source=$source rate=$actualRate buffer=$bufSize")
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            running = false
            record = null
            runCatching { rec.release() }
            throw RuntimeException("麦克风启动失败：${t.message ?: "请检查录音权限"}", t)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            record = null
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
                        val out = if (actualRate == targetRate) {
                            FloatArray(n) { shorts[it] / 32768f }
                        } else {
                            resample(shorts, n, actualRate, targetRate)
                        }
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
                    runCatching { rec.release() }
                }
            }
        }
    }

    private fun resample(src: ShortArray, n: Int, from: Int, to: Int): FloatArray {
        if (n <= 0 || from <= 0 || to <= 0) return FloatArray(0)
        if (from == to) return FloatArray(n) { src[it] / 32768f }
        val outLen = (n.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * from / to
            val idx = pos.toInt().coerceIn(0, n - 1)
            val next = (idx + 1).coerceAtMost(n - 1)
            val frac = pos - idx
            out[i] = (src[idx] * (1 - frac) + src[next] * frac).toFloat() / 32768f
        }
        return out
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw RuntimeException("系统音频采集需要 Android 10 及以上")
        }
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
        thread = thread(name = "system-audio-capture", isDaemon = true) {
            AppLog.d("Audio", "系统音频采集开始 capture=$captureRate actual=$actualRate buf=$bufSize")
            val shorts = ShortArray(2048)
            try {
                while (running) {
                    val n = try {
                        rec.read(shorts, 0, shorts.size)
                    } catch (t: Throwable) {
                        if (running) onError("系统音频读取异常：${t.message ?: "录屏授权可能已撤回"}")
                        break
                    }
                    if (n > 0) {
                        try {
                            onFrames(resample(shorts, n, actualRate, targetRate))
                        } catch (t: Throwable) {
                            if (running) onError("系统音频处理失败：${t.message ?: "未知错误"}")
                            break
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

    private fun resample(src: ShortArray, n: Int, from: Int, to: Int): FloatArray {
        if (from == to) return FloatArray(n) { src[it] / 32768f }
        if (from <= 0 || to <= 0) return FloatArray(0)
        val outLen = (n.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * from / to
            val idx = pos.toInt().coerceIn(0, n - 1)
            val next = (idx + 1).coerceAtMost(n - 1)
            val frac = pos - idx
            out[i] = (src[idx] * (1 - frac) + src[next] * frac).toFloat() / 32768f
        }
        return out
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
