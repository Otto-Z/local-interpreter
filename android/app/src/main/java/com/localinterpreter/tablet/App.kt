package com.localinterpreter.tablet

import android.app.Application
import com.localinterpreter.tablet.data.Database
import com.localinterpreter.tablet.data.Repository
import com.localinterpreter.tablet.data.Settings
import com.localinterpreter.tablet.llm.LlmService
import com.localinterpreter.tablet.llm.ModelStore
import com.localinterpreter.tablet.live.LiveController
import com.localinterpreter.tablet.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var db: Database
        private set
    lateinit var models: ModelStore
        private set
    lateinit var repo: Repository
        private set
    lateinit var llm: LlmService
        private set
    lateinit var live: LiveController
        private set

    /** 课程导入、导出等需要跨页面完成的工作。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        settings = Settings(this)
        db = Database(this)
        val interrupted = try {
            db.finishStaleSessions()
        } catch (t: Throwable) {
            AppLog.e("App", "数据库打开失败", t)
            if (isDatabaseCorrupt(t)) {
                AppLog.e("App", "检测到数据库损坏，备份后重建")
                runCatching { db.recreate() }
            } else {
                // 磁盘满、临时 I/O 错误等不要删除历史库，保留现场供下次恢复。
                AppLog.e("App", "非损坏类数据库错误，保留原数据库，未重建")
            }
            emptyList()
        }
        models = ModelStore(this)
        repo = Repository(this, db)
        llm = LlmService(this)
        live = LiveController(this)
        // 恢复长课次可能读取大量字幕，放到 IO 线程，避免拖慢冷启动首帧。
        appScope.launch {
            interrupted.filter { runCatching { db.listSegments(it).isEmpty() }.getOrDefault(false) }
                .forEach { runCatching { repo.deleteSession(it) } }
            for (id in interrupted.asReversed()) {
                if (live.state.value.running || live.state.value.starting ||
                    live.state.value.sessionId != null
                ) return@launch
                live.restoreInterrupted(id)
                if (live.state.value.sessionId != null) break
            }
        }
        val cleanupDays = settings.value.autoCleanupDays
        if (cleanupDays > 0) {
            appScope.launch {
                runCatching { repo.cleanupSessions(cleanupDays) }
            }
        }
    }

    /**
     * 进程被回收/内存紧张时主动降级：释放声纹与助教模型，保证正在进行的录音和翻译不被打断。
     * 这是 Android 给应用的标准回调，比等系统杀进程体面。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching { live.onMemoryPressure(level) }
        }
        if (level >= TRIM_MEMORY_COMPLETE) {
            // 后台且系统要回收：把已加载的大模型放掉；下次进入会重新预热。
            appScope.launch {
                runCatching { llm.unloadTranslator() }
            }
        }
    }

    private fun isDatabaseCorrupt(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is android.database.sqlite.SQLiteDatabaseCorruptException) return true
            val message = cause.message.orEmpty()
            if (message.contains("not a database", true) || message.contains("malformed", true)) return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
