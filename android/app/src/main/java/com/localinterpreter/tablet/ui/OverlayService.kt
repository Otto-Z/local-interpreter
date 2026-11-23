package com.localinterpreter.tablet.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.MainActivity
import com.localinterpreter.tablet.R
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.live.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var mainText: TextView? = null
    private var subText: TextView? = null
    private var currentSettings: SettingsData? = null
    private var background: GradientDrawable? = null

    private class OverlayContainer(context: Context) : LinearLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope.launch {
            App.instance.live.state.collectLatest { st -> render(st) }
        }
        scope.launch {
            App.instance.settings.state.collect { s ->
                currentSettings = s
                applySettings(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
        } catch (t: Throwable) {
            errors.value = "浮字幕启动失败：${t.message?.take(80) ?: "系统限制"}"
            setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) addOverlay() else view?.visibility = View.VISIBLE
        if (view != null) setRunning(true)
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        val lp = params ?: return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val newWidth = (metrics.widthPixels - (48 * density).toInt()).coerceAtLeast((240 * density).toInt())
        lp.width = newWidth
        val maxX = (metrics.widthPixels - newWidth).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - v.height.coerceAtLeast((80 * density).toInt())).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
        runCatching { wm?.updateViewLayout(v, lp) }
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this, 11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 12,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle("浮字幕已开启")
            .setContentText("字幕浮在所有应用上方，可拖动或点击右上角关闭。")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "关闭浮字幕", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notif)
        }
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            errors.value = "没有「显示在其他应用上层」权限，浮字幕无法显示"
            setRunning(false)
            stopSelf()
            return
        }
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val width = (screenWidth - (48 * density).toInt()).coerceAtLeast((240 * density).toInt())
        val settings = App.instance.settings.value

        val container = OverlayContainer(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (14 * density).toInt())
            val bg = GradientDrawable().apply {
                cornerRadius = 18 * density
                val alpha = (settings.overlayOpacity.coerceIn(0.2f, 1f) * 255).toInt()
                setColor(Color.argb(alpha, 0x0B, 0x0E, 0x13))
                setStroke((1 * density).toInt(), Color.parseColor("#443FE0B0"))
            }
            this@OverlayService.background = bg
            background = bg
            setOnClickListener {
                startActivity(
                    Intent(this@OverlayService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }

        // 顶部状态与快捷关闭栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        val dot = View(this).apply {
            val size = (7 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (7 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3FE0B0"))
            }
        }
        val status = TextView(this).apply {
            text = "本地同传"
            setTextColor(Color.parseColor("#8E99AA"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#9AA5B6"))
            textSize = 13f
            setPadding((10 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            setOnClickListener {
                setRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        header.addView(dot)
        header.addView(status)
        header.addView(closeBtn)
        statusDot = dot
        statusText = status

        // 原文在上方、译文在下方，与主界面及全屏字幕阅读顺序保持一致
        val sub = TextView(this).apply {
            setTextColor(Color.parseColor("#98A4B5"))
            textSize = (settings.subtitleSize * 0.42f).coerceIn(10f, 18f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        val main = TextView(this).apply {
            setTextColor(Color.parseColor("#F5F7FA"))
            textSize = (settings.subtitleSize * 0.72f).coerceIn(15f, 34f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.18f)
        }
        container.addView(header)
        container.addView(sub)
        container.addView(main)
        mainText = main
        subText = sub

        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (120 * density).toInt()
        }
        params = lp
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        container.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    val metrics = resources.displayMetrics
                    val maxX = (metrics.widthPixels - (v.width.coerceAtLeast(1))).coerceAtLeast(0)
                    val maxY = (metrics.heightPixels - (v.height.coerceAtLeast(1))).coerceAtLeast(0)
                    lp.x = (startX + dx).coerceIn(0, maxX)
                    lp.y = (startY + dy).coerceIn(0, maxY)
                    wm?.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                }
            }
            true
        }
        try {
            wm?.addView(container, lp)
            view = container
            applySettings(settings)
            render(App.instance.live.state.value)
        } catch (t: Throwable) {
            errors.value = "浮字幕无法显示：${t.message?.take(80) ?: "请重新开启"}"
            setRunning(false)
            runCatching { wm?.removeView(container) }
            stopSelf()
        }
    }

    private fun applySettings(s: SettingsData) {
        val density = resources.displayMetrics.density
        mainText?.textSize = (s.subtitleSize * 0.72f).coerceIn(15f, 34f)
        mainText?.maxLines = s.subtitleLines.coerceIn(1, 4) + 1
        subText?.textSize = (s.subtitleSize * 0.42f).coerceIn(10f, 18f)
        background?.let { bg ->
            val alpha = (s.overlayOpacity.coerceIn(0.2f, 1f) * 255).toInt()
            bg.setColor(Color.argb(alpha, 0x0B, 0x0E, 0x13))
            bg.setStroke((1 * density).toInt(), Color.parseColor("#443FE0B0"))
        }
        view?.background = background
        render(App.instance.live.state.value)
    }

    private fun render(st: LiveState) {
        val settings = currentSettings ?: App.instance.settings.value
        val lineCount = settings.subtitleLines.coerceIn(1, 4)
        val recent = st.segments.takeLast(lineCount)
        val targets = recent.mapNotNull { it.tgt.ifBlank { it.src }.ifBlank { null } }
        val partial = st.partialTgt.ifBlank { st.partialSrc }
        val main = buildList {
            addAll(targets)
            if (partial.isNotBlank()) add(partial)
        }.takeLast(lineCount).joinToString("\n")

        statusText?.text = when {
            st.running && st.paused -> "已暂停 · 拖动可移位"
            st.running -> "同传中 · 轻触返回应用"
            else -> "本地同传 · 等待开始"
        }
        (statusDot?.background as? GradientDrawable)?.setColor(
            when {
                st.running && !st.paused -> Color.parseColor("#3FE0B0")
                st.running && st.paused -> Color.parseColor("#F0B856")
                else -> Color.parseColor("#7D8898")
            }
        )

        mainText?.maxLines = lineCount + 1
        mainText?.text = main.ifBlank {
            if (st.running) "正在聆听…" else "点击主界面「开始同传」即可显示实时字幕"
        }
        val latestSrc = when {
            st.partialTgt.isNotBlank() && st.partialSrc.isNotBlank() -> st.partialSrc
            st.partialSrc.isNotBlank() -> ""
            else -> recent.lastOrNull()?.let { if (it.tgt.isNotBlank()) it.src else "" }.orEmpty()
        }
        subText?.text = if (settings.showSource) latestSrc else ""
        subText?.visibility = if (subText?.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "浮字幕", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        setRunning(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        view?.let { runCatching { wm?.removeView(it) } }
        view = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "overlay"
        const val ID = 43
        const val ACTION_STOP = "stop_overlay"
        val running = AtomicBoolean(false)
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        /** 启动失败原因，主界面收集后提示用户。 */
        val errors = MutableStateFlow<String?>(null)

        private fun setRunning(value: Boolean) {
            running.set(value)
            _isRunning.value = value
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
