package com.localinterpreter.tablet.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.MainActivity
import com.localinterpreter.tablet.R
import com.localinterpreter.tablet.live.LiveSource
import com.localinterpreter.tablet.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "同传运行中", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        scope.launch {
            var lastActive: Boolean? = null
            var lastPaused: Boolean? = null
            var lastSource: LiveSource? = null
            App.instance.live.state.collect { st ->
                val active = st.running || st.starting
                if (active) {
                    acquireWakeLock()
                    if (lastActive != true || lastPaused != st.paused || lastSource != st.source) {
                        updateNotification(st.paused, st.source)
                    }
                    lastActive = true
                    lastPaused = st.paused
                    lastSource = st.source
                } else if (lastActive != false) {
                    lastActive = false
                    releaseWakeLock()
                    delay(400)
                    val now = App.instance.live.state.value
                    if (!now.running && !now.starting) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        // 宽限期内又开始了新会话，继续复用前台服务。
                        lastActive = null
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d("Service", "onStartCommand action=${intent?.action ?: "null"}")
        when (intent?.action) {
            ACTION_START_MIC -> startSession(intent, LiveSource.MIC)
            ACTION_START_SYSTEM -> startSession(intent, LiveSource.SYSTEM)
            ACTION_START_FILE -> startSession(intent, LiveSource.FILE)
            ACTION_PAUSE -> {
                if (App.instance.live.state.value.running) {
                    App.instance.live.pause()
                    updateNotification(true, App.instance.live.state.value.source)
                }
            }
            ACTION_RESUME -> {
                if (App.instance.live.state.value.running) {
                    App.instance.live.resume()
                    updateNotification(false, App.instance.live.state.value.source)
                }
            }
            ACTION_STOP -> App.instance.live.stop()
            else -> {
                val st = App.instance.live.state.value
                if (!st.running && !st.starting) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun foregroundType(source: LiveSource): Int = when (source) {
        LiveSource.SYSTEM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        LiveSource.FILE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0 // 麦克风前台类型是 Android 11 才有的；Android 10 用普通前台服务。
        }
    }

    private fun enterForeground(source: LiveSource, paused: Boolean) {
        val type = foregroundType(source)
        AppLog.d("Service", "startForeground source=$source type=$type paused=$paused")
        if (type != 0) {
            startForeground(NOTIFICATION_ID, buildNotification(paused, source), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(paused, source))
        }
        acquireWakeLock()
    }

    /**
     * 息屏后仍要持续采集。前台服务本身不保证 CPU 不休眠，部分厂商的省电策略会在灭屏后
     * 掐掉麦克风；持有 partial wake lock 直到会话结束，保证录音线程能持续被调度。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val lock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "LocalInterpreter:live",
            )
            lock.setReferenceCounted(false)
            lock.acquire(6L * 60 * 60 * 1000)
            wakeLock = lock
            AppLog.d("Service", "wakeLock 已获取（最长 6 小时）")
        } catch (t: Throwable) {
            AppLog.e("Service", "获取 wakeLock 失败（不影响录音尝试）", t)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
        AppLog.d("Service", "wakeLock 已释放")
    }

    private fun startSession(intent: Intent, source: LiveSource) {
        val live = App.instance.live
        val current = live.state.value
        if (current.running || current.starting) {
            // 已有会话在跑：只确保前台服务仍存在且类型/通知正确，不启动第二个会话。
            try {
                enterForeground(current.source, current.paused)
            } catch (t: Throwable) {
                // 已有会话在跑时，更新通知失败不应把正在录音的服务停掉。
                AppLog.e("Service", "更新前台通知失败", t)
            }
            return
        }
        val courseId = intent.getLongExtra(EXTRA_COURSE_ID, -1L).takeIf { it > 0 }
        try {
            enterForeground(source, false)
        } catch (t: Throwable) {
            AppLog.e("LiveService", "前台服务启动失败 source=$source", t)
            App.instance.live.reportError(
                "无法启动前台服务：${t.message?.take(80) ?: "请检查通知/麦克风权限"}"
            )
            stopSelf()
            return
        }

        if (source == LiveSource.FILE) {
            val uri: android.net.Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_FILE_URI, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_FILE_URI)
            }
            App.instance.live.start(this, source, courseId, fileUri = uri)
            return
        }
        if (source == LiveSource.SYSTEM) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultCode == 0 || data == null) {
                App.instance.live.start(this, source, courseId, null)
                return
            }
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(resultCode, data)
                AppLog.d("Service", "MediaProjection 获取成功")
            } catch (e: Exception) {
                AppLog.e("Service", "MediaProjection 获取失败", e)
                App.instance.live.start(this, source, courseId, null)
                return
            }
        }
        App.instance.live.start(this, source, courseId, projection)
    }

    private fun buildNotification(paused: Boolean, source: LiveSource): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = if (paused) {
            PendingIntent.getService(this, 2, Intent(this, LiveService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 2, Intent(this, LiveService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(this, 3, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle("本地同传 · ${source.label}")
            .setContentText(
                if (paused) "已暂停" else when (source) {
                    LiveSource.MIC -> "正在听麦克风，字幕持续更新"
                    LiveSource.SYSTEM -> "正在听系统声音，字幕持续更新"
                    LiveSource.FILE -> "正在全速转写音频文件，字幕持续更新"
                }
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (paused) "继续" else "暂停", toggle)
            .addAction(0, "结束", stop)
            .build()
    }

    private fun updateNotification(paused: Boolean, source: LiveSource) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(paused, source))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        val st = App.instance.live.state.value
        AppLog.d("Service", "LiveService onDestroy running=${st.running} starting=${st.starting}")
        if (st.running || st.starting) {
            runCatching { App.instance.live.stop() }
        }
        runCatching { projection?.stop() }
        projection = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "live"
        const val NOTIFICATION_ID = 42
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_START_MIC = "start_mic"
        const val ACTION_START_SYSTEM = "start_system"
        const val ACTION_START_FILE = "start_file"
        const val EXTRA_FILE_URI = "file_uri"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"

        fun startMic(context: Context, courseId: Long?) {
            val intent = Intent(context, LiveService::class.java)
                .setAction(ACTION_START_MIC)
                .putExtra(EXTRA_COURSE_ID, courseId ?: -1L)
            context.startForegroundService(intent)
        }

        fun startSystem(context: Context, courseId: Long?, resultCode: Int, data: Intent) {
            val intent = Intent(context, LiveService::class.java)
                .setAction(ACTION_START_SYSTEM)
                .putExtra(EXTRA_COURSE_ID, courseId ?: -1L)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun startFile(context: Context, courseId: Long?, uri: android.net.Uri) {
            val intent = Intent(context, LiveService::class.java)
                .setAction(ACTION_START_FILE)
                .putExtra(EXTRA_COURSE_ID, courseId ?: -1L)
                .putExtra(EXTRA_FILE_URI, uri)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveService::class.java).setAction(ACTION_STOP))
        }

        fun togglePause(context: Context, paused: Boolean) {
            context.startService(
                Intent(context, LiveService::class.java)
                    .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE)
            )
        }
    }
}
