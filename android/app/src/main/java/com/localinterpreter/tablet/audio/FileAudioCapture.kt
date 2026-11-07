package com.localinterpreter.tablet.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.util.AppLog
import java.io.DataInputStream
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Reads a PCM WAV file and feeds it to the live pipeline faster than real time. */
class FileAudioCapture(
    context: Context,
    private val uri: Uri,
    private val onFrames: (FloatArray) -> Unit,
    private val onEnded: () -> Unit,
    private val onError: (String) -> Unit = {},
) : AudioCapture {
    override val label = "音频文件"
    /**
     * 文件转写照常按设备能力全速喂入；只有在「延迟测量」模式（1 倍速）下才按真实时间回放，
     * 这样测出来的首字/整句延迟和真实课堂可比。
     */
    private val speed = if (App.instance.settings.value.latencyProbe) 1.0 else 1000.0
    @Volatile private var running = false
    @Volatile private var paused = false
    private var pauseStartedAt = 0L
    private var pausedTotalMs = 0L
    private var thread: Thread? = null
    private val resolver = context.contentResolver
    private val appContext = context.applicationContext

    fun setPaused(value: Boolean) {
        if (value && !paused) {
            paused = true
            pauseStartedAt = System.currentTimeMillis()
        } else if (!value && paused) {
            paused = false
            if (pauseStartedAt > 0) pausedTotalMs += System.currentTimeMillis() - pauseStartedAt
            pauseStartedAt = 0
        }
    }

    override fun start() {
        if (running) return
        running = true
        thread = thread(name = "file-audio", isDaemon = true) {
            var input: DataInputStream? = null
            var normalEnd = false
            try {
                val stream = resolver.openInputStream(uri) ?: throw RuntimeException("无法打开音频文件")
                input = DataInputStream(stream.buffered())
                val header = ByteArray(12)
                input.readFully(header)
                if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                    input.close()
                    input = null
                    decodeCompressed()
                    normalEnd = true
                    return@thread
                }
                var sampleRate = 16000
                var channels = 1
                var bits = 16
                var audioFormat = 1
                var dataLeft = -1L
                while (true) {
                    val idBytes = ByteArray(4)
                    if (input.read(idBytes) != 4) break
                    val id = String(idBytes, Charsets.US_ASCII)
                    val size = readLe32(input)
                    if (id == "fmt ") {
                        if (size < 16 || size > 4096) throw RuntimeException("WAV 格式头损坏")
                        val fmt = ByteArray(size)
                        input.readFully(fmt)
                        audioFormat = readLe16(fmt, 0)
                        channels = readLe16(fmt, 2)
                        sampleRate = readLe32(fmt, 4)
                        bits = readLe16(fmt, 14)
                    } else if (id == "data") {
                        // 0 / 0xFFFFFFFF 是流式 WAV 未回填长度，按读到文件尾处理。
                        dataLeft = if (size <= 0) Long.MAX_VALUE else size.toLong()
                        break
                    } else {
                        var skip = size.toLong()
                        while (skip > 0) {
                            val n = input.skip(skip)
                            if (n <= 0) break
                            skip -= n
                        }
                        if (size % 2 == 1) input.skip(1)
                    }
                }
                if (dataLeft < 0) throw RuntimeException("WAV 文件没有音频数据")
                if (dataLeft == 0L) throw RuntimeException("WAV 文件没有音频数据")
                if (audioFormat != 1) {
                    input.close()
                    input = null
                    decodeCompressed()
                    normalEnd = true
                    return@thread
                }
                if (bits != 16) throw RuntimeException("只支持 16 位 PCM WAV（当前 $bits 位）")
                if (channels !in 1..2) throw RuntimeException("只支持单声道或双声道 WAV（当前 $channels 声道）")
                if (sampleRate !in 8000..192000) throw RuntimeException("WAV 采样率异常：$sampleRate")
                val frameBytes = channels * 2
                AppLog.d(
                    "Audio",
                    "WAV 文件解析 rate=$sampleRate ch=$channels bits=$bits dataBytes=$dataLeft",
                )
                val chunkFrames = max(256, sampleRate / 50)
                val buf = ByteArray(chunkFrames * frameBytes)
                val start = System.currentTimeMillis()
                var fedSamples = 0L
                while (running && dataLeft > 0) {
                    while (paused && running) Thread.sleep(80)
                    if (!running) break
                    val want = min(buf.size.toLong(), dataLeft).toInt() / frameBytes * frameBytes
                    if (want <= 0) break
                    val n = input.read(buf, 0, want)
                    if (n <= 0) break
                    dataLeft -= n
                    val frames = n / frameBytes
                    val mono = FloatArray(frames)
                    for (i in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            val lo = buf[i * frameBytes + c * 2].toInt() and 0xff
                            val hi = buf[i * frameBytes + c * 2 + 1].toInt()
                            sum += ((hi shl 8) or lo).toShort() / 32768f
                        }
                        mono[i] = sum / channels
                    }
                    val out = if (sampleRate == 16000) mono else resample(mono, sampleRate, 16000)
                    if (out.isNotEmpty()) {
                        onFrames(out)
                        fedSamples += frames
                        val targetMs = fedSamples * 1000.0 / sampleRate / speed
                        val elapsed = System.currentTimeMillis() - start - pausedTotalMs
                        if (targetMs > elapsed) {
                            Thread.sleep(min(200L, (targetMs - elapsed).toLong()))
                        }
                    }
                }
                normalEnd = true
            } catch (t: Throwable) {
                AppLog.e("Audio", "读取音频文件失败", t)
                if (running) {
                    val msg = if (t is SecurityException || t.cause is SecurityException) {
                        "没有读取该文件的权限，请用主界面的「音频文件」重新选择"
                    } else {
                        "无法读取音频文件：${t.message ?: "格式不支持"}"
                    }
                    onError(msg)
                }
            } finally {
                try { input?.close() } catch (_: Exception) {}
                running = false
                if (normalEnd) onEnded()
            }
        }
    }


    /** Decode MP3/M4A/AAC/OGG/FLAC and other MediaExtractor-supported audio. */
    private fun decodeCompressed() {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw RuntimeException("文件里没有音轨")
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw RuntimeException("未知音频格式")
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            val dec = MediaCodec.createDecoderByType(mime)
            AppLog.d("Audio", "压缩音频解码 mime=$mime rate=$sampleRate ch=$channels encoding=$encoding")
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val wallStart = System.currentTimeMillis()
            var fedFrames = 0L
            while (running && !outputDone) {
                while (paused && running) Thread.sleep(80)
                if (!running) break
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                        val size = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = dec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = dec.outputFormat
                        sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val pcm = decodeToFloat(outBuf, info.size, encoding, channels)
                            val resampled = if (sampleRate == 16000) pcm else resample(pcm, sampleRate, 16000)
                            if (resampled.isNotEmpty() && running) {
                                onFrames(resampled)
                                val targetMs = if (info.presentationTimeUs > 0) {
                                    info.presentationTimeUs / 1000.0 / speed
                                } else {
                                    fedFrames * 1000.0 / sampleRate.coerceAtLeast(1) / speed
                                }
                                fedFrames += pcm.size.toLong()
                                val elapsed = System.currentTimeMillis() - wallStart - pausedTotalMs
                                if (targetMs > elapsed) Thread.sleep(min(200L, (targetMs - elapsed).toLong()))
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeToFloat(buf: java.nio.ByteBuffer, size: Int, encoding: Int, channels: Int): FloatArray {
        val b = buf.slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val frameBytes: Int
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = b.asFloatBuffer()
                val frames = size / (4 * channels)
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += fb.get(i * channels + c)
                    out[i] = sum / channels
                }
                return out
            }
            AudioFormat.ENCODING_PCM_8BIT -> frameBytes = channels
            else -> frameBytes = 2 * channels
        }
        val frames = size / frameBytes
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val off = i * frameBytes + c * (frameBytes / channels)
                sum += if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                    (b.get(off).toInt() and 0xff) / 128f - 1f
                } else {
                    val lo = b.get(off).toInt() and 0xff
                    val hi = b.get(off + 1).toInt()
                    ((hi shl 8) or lo).toShort() / 32768f
                }
            }
            out[i] = sum / channels
        }
        return out
    }

    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        if (src.isEmpty()) return src
        if (from == to) return src
        if (from <= 0 || to <= 0) return FloatArray(0)
        val outLen = (src.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * from / to
            val idx = pos.toInt().coerceIn(0, src.size - 1)
            val next = (idx + 1).coerceAtMost(src.size - 1)
            val frac = pos - idx
            out[i] = (src[idx] * (1 - frac) + src[next] * frac).toFloat()
        }
        return out
    }

    private fun readLe16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun readLe32(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return readLe32(b, 0)
    }

    override fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }
}
