package com.localinterpreter.tablet.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small append-only log kept in app-private storage, for diagnosing device-specific failures. */
object AppLog {
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "app.log")
        file = f
        if (f.length() > 2_000_000) {
            runCatching { File(dir, "app.old.log").delete() }
            runCatching { f.renameTo(File(dir, "app.old.log")) }
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        d(
            "App",
            "start version=${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()} " +
                "model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                "ram=${mi.totalMem / 1_048_576}MB avail=${mi.availMem / 1_048_576}MB lowMemory=${mi.lowMemory} " +
                "heapMax=${Runtime.getRuntime().maxMemory() / 1_048_576}MB"
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e("CRASH", "uncaught in ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    fun d(tag: String, msg: String) {
        Log.d("LocalInterpreter", "[$tag] $msg")
        write("D", tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e("LocalInterpreter", "[$tag] $msg", tr)
        val stack = tr?.let { "\n" + it.stackTraceToString().take(4000) } ?: ""
        write("E", tag, msg + stack)
    }

    fun availableBytes(): Long {
        val ctx = appContext ?: return Long.MAX_VALUE
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    fun memory(): String {
        val ctx = appContext ?: return ""
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "avail=${mi.availMem / 1_048_576}MB total=${mi.totalMem / 1_048_576}MB low=${mi.lowMemory}"
        } catch (_: Exception) {
            ""
        }
    }

    fun tail(maxLines: Int = 300, maxBytes: Long = 256L * 1024): String {
        val f = file ?: return ""
        return readTail(f, maxBytes)?.lines()?.takeLast(maxLines)?.joinToString("\n").orEmpty()
    }

    /** 设备、系统版本、内存等固定信息，用于诊断报告。 */
    fun deviceInfo(context: Context? = appContext): String {
        val ctx = context ?: return "设备信息不可用"
        val mi = ActivityManager.MemoryInfo()
        runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        }
        val pkg = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }.getOrNull()
        return buildString {
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine(
                "应用：${pkg?.versionName ?: "?"}（${pkg?.longVersionCode ?: 0}） " +
                    "cores=${Runtime.getRuntime().availableProcessors()}"
            )
            appendLine(
                "内存：total=${mi.totalMem / 1_048_576}MB avail=${mi.availMem / 1_048_576}MB " +
                    "low=${mi.lowMemory} heapMax=${Runtime.getRuntime().maxMemory() / 1_048_576}MB"
            )
        }.trim()
    }

    /** 完整诊断报告：配置/状态头 + 当前日志 + 上一份滚动日志。 */
    fun report(header: String = "", maxLines: Int = 6000): String {
        val current = file
        val old = current?.parentFile?.let { File(it, "app.old.log") }
        return buildString {
            appendLine("===== 本地同传诊断报告 =====")
            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine(deviceInfo())
            if (header.isNotBlank()) {
                appendLine()
                appendLine("----- 当前配置与状态 -----")
                appendLine(header.trim())
            }
            appendLine()
            appendLine("----- 当前日志 -----")
            appendLine(current?.let { readTail(it, 1024L * 1024) }?.lines()?.takeLast(maxLines)?.joinToString("\n").orEmpty())
            val oldText = old?.let { readTail(it, 512L * 1024) }?.lines()?.takeLast(1500)?.joinToString("\n").orEmpty()
            if (oldText.isNotBlank()) {
                appendLine()
                appendLine("----- 上一份日志 -----")
                appendLine(oldText)
            }
            appendLine("===== 报告结束 =====")
        }
    }

    private fun readTail(f: File, maxBytes: Long): String? {
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val length = raf.length()
                val start = (length - maxBytes).coerceAtLeast(0L)
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file?.writeText("") }
        }
    }

    private var writeCount = 0

    private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                writeCount++
                if (writeCount >= 256) {
                    writeCount = 0
                    if (f.length() > 4_000_000L) {
                        val old = File(f.parentFile, "app.old.log")
                        runCatching { old.delete() }
                        runCatching { f.renameTo(old) }
                    }
                }
                f.appendText("${timeFormat.format(Date())} $level/$tag $msg\n")
            } catch (_: Exception) {
            }
        }
    }
}
