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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlin.math.max
import kotlin.math.roundToInt

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var background: GradientDrawable? = null

    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var layoutToggleBtn: TextView? = null
    private var sourceToggleBtn: TextView? = null

    private var columnHeaderRow: LinearLayout? = null
    private var srcHeaderLabel: TextView? = null
    private var tgtHeaderLabel: TextView? = null

    private var scrollView: MaxHeightScrollView? = null
    private var rowsContainer: LinearLayout? = null
    private var emptyStateText: TextView? = null

    private var currentSettings: SettingsData? = null
    private var lastLiveState: LiveState = LiveState()

    // 视图池，避免流式字幕高频刷新时反复销毁/重建 View
    private val rowHolders = mutableListOf<RowHolder>()
    private var activeRenderMode: String = ""
    private var activeShowSource: Boolean = true
    private var activeSplitRatio: Float = 0.45f
    private var lastContentSignature: String = ""

    // 手势交互状态，防止拖拽/缩放期间被外部设置流覆盖
    private var isInteracting: Boolean = false
    private var userScrolledUp: Boolean = false
    private var lastAppliedGeo: GeoSnapshot? = null

    private data class GeoSnapshot(
        val xDp: Int,
        val yDp: Int,
        val wDp: Int,
        val hDp: Int,
    )

    private data class OverlayRow(
        val badge: String,
        val speaker: String?,
        val src: String,
        val tgt: String,
        val isLive: Boolean,
        val isDraft: Boolean,
    )

    private data class RowHolder(
        val root: LinearLayout,
        val srcPane: LinearLayout,
        val srcMeta: TextView,
        val srcText: TextView,
        val divider: View,
        val tgtPane: LinearLayout,
        val tgtMeta: TextView?,
        val tgtText: TextView,
        val srcBg: GradientDrawable,
        val tgtBg: GradientDrawable,
        val cardBg: GradientDrawable?,
    )

    private class TouchableLinearLayout(context: Context) : LinearLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class TouchableFrameLayout(context: Context) : FrameLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class TouchableTextView(context: Context) : TextView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class TouchableView(context: Context) : View(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val constrainedSpec = if (maxHeightPx < Int.MAX_VALUE && mode != MeasureSpec.EXACTLY) {
                val limit = if (mode == MeasureSpec.AT_MOST) minOf(size, maxHeightPx) else maxHeightPx
                MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
            } else {
                heightMeasureSpec
            }
            super.onMeasure(widthMeasureSpec, constrainedSpec)
        }
    }

    private class ResizeGripView(context: Context, private val isLeftCorner: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8C9FB4")
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            paint.strokeWidth = 1.6f * d
            val pad = 8f * d
            val w = width.toFloat()
            val h = height.toFloat()
            val offsets = floatArrayOf(4f * d, 8.5f * d, 13f * d)
            if (!isLeftCorner) {
                for (o in offsets) {
                    canvas.drawLine(w - pad - o, h - pad, w - pad, h - pad - o, paint)
                }
            } else {
                for (o in offsets) {
                    canvas.drawLine(pad + o, h - pad, pad, h - pad - o, paint)
                }
            }
        }

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
            App.instance.live.state.collectLatest { st ->
                lastLiveState = st
                render(st)
            }
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
        if (rootView == null) addOverlay() else rootView?.visibility = View.VISIBLE
        if (rootView != null) setRunning(true)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = rootView ?: return
        val lp = params ?: return
        val s = currentSettings ?: App.instance.settings.value
        applyWindowGeometry(v, lp, s, forceClamp = true)
        render(lastLiveState)
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
            .setContentText("支持左右双栏条条对应、自由拖动与拖拽边角调节尺寸。")
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

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun defaultWidthPx(): Int {
        val screenW = resources.displayMetrics.widthPixels
        val ratio = if (screenW >= dp(760f)) 0.78f else 0.92f
        return (screenW * ratio).roundToInt().coerceIn(dp(300f), (screenW - dp(16f)).coerceAtLeast(dp(260f)))
    }

    private fun minWidthPx(s: SettingsData): Int {
        val screenW = resources.displayMetrics.widthPixels
        val desired = if (s.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT && s.showSource) dp(340f) else dp(260f)
        return desired.coerceAtMost((screenW - dp(16f)).coerceAtLeast(dp(220f)))
    }

    private fun minHeightPx(): Int = dp(136f)

    private fun maxHeightPx(): Int = (resources.displayMetrics.heightPixels * 0.78f).roundToInt().coerceAtLeast(dp(220f))

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            errors.value = "没有「显示在其他应用上层」权限，浮字幕无法显示"
            setRunning(false)
            stopSelf()
            return
        }
        val settings = App.instance.settings.value
        currentSettings = settings
        activeSplitRatio = settings.overlaySplitRatio.coerceIn(0.30f, 0.70f)

        val container = TouchableLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                val alpha = (settings.overlayOpacity.coerceIn(0.2f, 1f) * 255).roundToInt()
                setColor(Color.argb(alpha, 0x0A, 0x0E, 0x15))
                setStroke(dp(1f), Color.parseColor("#4D3FE0B0"))
            }
            this@OverlayService.background = bg
            background = bg
        }

        // 1. 顶部拖动与快捷操作栏
        val header = TouchableLinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(2f), dp(2f), dp(6f))
        }
        val dot = View(this).apply {
            val size = dp(7f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(7f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3FE0B0"))
            }
        }
        val status = TextView(this).apply {
            text = "本地同传"
            setTextColor(Color.parseColor("#9AA7B8"))
            textSize = 11.5f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val layoutBtn = createPillButton(
            if (settings.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT) "双栏" else "卡片"
        ).apply {
            setOnClickListener {
                val cur = currentSettings ?: App.instance.settings.value
                val next = if (cur.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT) {
                    SettingsData.OVERLAY_LAYOUT_STACK
                } else {
                    SettingsData.OVERLAY_LAYOUT_SPLIT
                }
                App.instance.settings.update { it.copy(overlayLayout = next) }
            }
        }
        val sourceBtn = createPillButton(if (settings.showSource) "双语" else "仅译").apply {
            setOnClickListener {
                val cur = currentSettings ?: App.instance.settings.value
                App.instance.settings.update { it.copy(showSource = !cur.showSource) }
            }
        }
        val appBtn = createPillButton("主界面").apply {
            setOnClickListener {
                startActivity(
                    Intent(this@OverlayService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
        val closeBtn = createPillButton("✕", accent = false).apply {
            setTextColor(Color.parseColor("#D0D8E4"))
            setOnClickListener {
                setRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        header.addView(dot)
        header.addView(status)
        header.addView(layoutBtn)
        header.addView(sourceBtn)
        header.addView(appBtn)
        header.addView(closeBtn)
        statusDot = dot
        statusText = status
        layoutToggleBtn = layoutBtn
        sourceToggleBtn = sourceBtn

        // 2. 双栏分窗表头（左原文窗 / 中间可拖动分栏手柄 / 右译文窗）
        val colHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(dp(2f), dp(1f), dp(2f), dp(5f))
        }
        val srcLabel = TextView(this).apply {
            text = "原文"
            setTextColor(Color.parseColor("#8FA0B6"))
            textSize = 10.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8f), 0, dp(4f), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, activeSplitRatio)
        }
        val splitHandle = TouchableTextView(this).apply {
            text = "⋮"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6E829B"))
            textSize = 12f
            contentDescription = "左右拖动调节原文与译文栏宽度比例"
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(20f))
        }
        val tgtLabel = TextView(this).apply {
            text = "译文"
            setTextColor(Color.parseColor("#5EEAD4"))
            textSize = 10.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8f), 0, dp(4f), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f - activeSplitRatio)
        }
        colHeader.addView(srcLabel)
        colHeader.addView(splitHandle)
        colHeader.addView(tgtLabel)
        columnHeaderRow = colHeader
        srcHeaderLabel = srcLabel
        tgtHeaderLabel = tgtLabel
        attachSplitHandleTouch(splitHandle, colHeader)

        // 3. 逐条对照滚动内容区
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val emptyText = TextView(this).apply {
            text = "点击主界面「开始同传」即可显示实时双语字幕"
            setTextColor(Color.parseColor("#9BA7B8"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(16f), dp(12f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        rows.addView(emptyText)
        rowsContainer = rows
        emptyStateText = emptyText

        val scroller = MaxHeightScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.55f).roundToInt()
            addView(rows)
            setOnScrollChangeListener { v, _, scrollY, _, _ ->
                val sv = v as ScrollView
                val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
                val distanceToBottom = child.bottom - (scrollY + sv.height)
                userScrolledUp = distanceToBottom > dp(24f)
            }
        }
        scrollView = scroller

        // 4. 底部缩放控制条（左下角手柄 + 底部高度条 + 右下角手柄）
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24f),
            )
        }
        val leftGrip = ResizeGripView(this, isLeftCorner = true).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(24f))
            contentDescription = "左下角拖拽调节尺寸"
        }
        val centerHeightBar = TouchableFrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(24f), 1f)
            contentDescription = "上下拖动调节高度，双击恢复自动高度"
        }
        val heightPill = TouchableView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44f), dp(4f), Gravity.CENTER)
            background = GradientDrawable().apply {
                cornerRadius = dp(99f).toFloat()
                setColor(Color.parseColor("#558C9FB4"))
            }
        }
        centerHeightBar.addView(heightPill)
        val rightGrip = ResizeGripView(this, isLeftCorner = false).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(24f))
            contentDescription = "右下角拖拽调节尺寸"
        }
        footer.addView(leftGrip)
        footer.addView(centerHeightBar)
        footer.addView(rightGrip)

        container.addView(header)
        container.addView(colHeader)
        container.addView(scroller, buildScrollerLayoutParams(fixedHeight = settings.overlayHeightDp > 0))
        container.addView(footer)

        val lp = WindowManager.LayoutParams(
            defaultWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        // 在 addView 之前先计算上次保存的位置与尺寸，避免首帧闪跳
        computeGeometryIntoParams(lp, settings, measuredHeight = dp(180f))
        lastAppliedGeo = GeoSnapshot(settings.overlayXDp, settings.overlayYDp, settings.overlayWidthDp, settings.overlayHeightDp)
        params = lp

        attachMoveTouch(header, container, lp)
        attachResizeTouch(rightGrip, container, lp, mode = ResizeMode.BOTTOM_RIGHT)
        attachResizeTouch(leftGrip, container, lp, mode = ResizeMode.BOTTOM_LEFT)
        attachResizeTouch(centerHeightBar, container, lp, mode = ResizeMode.BOTTOM_ONLY)

        try {
            wm?.addView(container, lp)
            rootView = container
            applySettings(settings)
            render(App.instance.live.state.value)
        } catch (t: Throwable) {
            errors.value = "浮字幕无法显示：${t.message?.take(80) ?: "请重新开启"}"
            setRunning(false)
            runCatching { wm?.removeView(container) }
            stopSelf()
        }
    }

    private fun createPillButton(label: String, accent: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (accent) Color.parseColor("#5EEAD4") else Color.parseColor("#C2CDD9"))
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.parseColor("#24FFFFFF"))
                setStroke(dp(0.8f), Color.parseColor("#2AFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(5f)
            }
        }
    }

    private fun buildScrollerLayoutParams(fixedHeight: Boolean): LinearLayout.LayoutParams {
        return if (fixedHeight) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun attachMoveTouch(handle: View, target: View, lp: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    isInteracting = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        val metrics = resources.displayMetrics
                        val viewW = target.width.coerceAtLeast(lp.width.coerceAtLeast(dp(240f)))
                        val viewH = target.height.coerceAtLeast(dp(100f))
                        val maxX = (metrics.widthPixels - viewW).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - viewH).coerceAtLeast(0)
                        lp.x = (startX + dx).coerceIn(0, maxX)
                        lp.y = (startY + dy).coerceIn(0, maxY)
                        runCatching { wm?.updateViewLayout(target, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isInteracting = false
                    if (moved) {
                        persistCurrentGeometry(lp)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private enum class ResizeMode { BOTTOM_RIGHT, BOTTOM_LEFT, BOTTOM_ONLY }

    private fun attachResizeTouch(
        handle: View,
        target: View,
        lp: WindowManager.LayoutParams,
        mode: ResizeMode,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startW = 0
        var startH = 0
        var moved = false
        var lastTapTime = 0L

        handle.setOnTouchListener { v, event ->
            val s = currentSettings ?: App.instance.settings.value
            val density = resources.displayMetrics.density
            val metrics = resources.displayMetrics
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startW = if (lp.width > 0) lp.width else target.width.coerceAtLeast(defaultWidthPx())
                    startH = if (lp.height > 0) lp.height else target.height.coerceAtLeast(minHeightPx())
                    moved = false
                    isInteracting = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (abs(dx) > touchSlop / 2 || abs(dy) > touchSlop / 2) moved = true
                    if (moved) {
                        val minW = minWidthPx(s)
                        val maxW = (metrics.widthPixels - dp(12f)).coerceAtLeast(minW)
                        val minH = minHeightPx()
                        val maxH = maxHeightPx()

                        var newW = startW
                        var newX = startX
                        when (mode) {
                            ResizeMode.BOTTOM_RIGHT -> {
                                newW = (startW + dx).coerceIn(minW, maxW)
                                newX = startX.coerceIn(0, (metrics.widthPixels - newW).coerceAtLeast(0))
                            }
                            ResizeMode.BOTTOM_LEFT -> {
                                newW = (startW - dx).coerceIn(minW, maxW)
                                newX = (startX + (startW - newW)).coerceIn(0, (metrics.widthPixels - newW).coerceAtLeast(0))
                            }
                            ResizeMode.BOTTOM_ONLY -> {
                                newW = startW
                                newX = startX
                            }
                        }
                        val newH = (startH + dy).coerceIn(minH, maxH)
                        val maxY = (metrics.heightPixels - newH).coerceAtLeast(0)
                        lp.x = newX
                        lp.y = lp.y.coerceIn(0, maxY)
                        lp.width = newW
                        lp.height = newH
                        scrollView?.layoutParams = buildScrollerLayoutParams(fixedHeight = true)
                        scrollView?.maxHeightPx = Int.MAX_VALUE
                        runCatching { wm?.updateViewLayout(target, lp) }

                        val wDp = (newW / density).roundToInt()
                        val hDp = (newH / density).roundToInt()
                        statusText?.text = "调节尺寸 ${wDp}×${hDp} dp（双击恢复自动高度）"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isInteracting = false
                    if (moved) {
                        persistCurrentGeometry(lp)
                        render(lastLiveState)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapTime < 360L) {
                            lastTapTime = 0L
                            // 双击缩放手柄恢复高度自适应
                            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                            scrollView?.layoutParams = buildScrollerLayoutParams(fixedHeight = false)
                            scrollView?.maxHeightPx = (metrics.heightPixels * 0.55f).roundToInt()
                            runCatching { wm?.updateViewLayout(target, lp) }
                            persistCurrentGeometry(lp)
                            render(lastLiveState)
                        } else {
                            lastTapTime = now
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachSplitHandleTouch(handle: View, colHeader: LinearLayout) {
        var downX = 0f
        var startRatio = 0.45f
        var moved = false
        var lastTapTime = 0L

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    startRatio = activeSplitRatio
                    moved = false
                    isInteracting = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (abs(dx) > dp(4f)) moved = true
                    val totalW = colHeader.width.coerceAtLeast(dp(240f)).toFloat()
                    val next = (startRatio + dx / totalW).coerceIn(0.30f, 0.70f)
                    if (abs(next - activeSplitRatio) > 0.005f) {
                        activeSplitRatio = next
                        applySplitWeights(next)
                        statusText?.text = "双栏比例 ${(next * 100).roundToInt()}% : ${((1f - next) * 100).roundToInt()}%"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isInteracting = false
                    if (moved) {
                        App.instance.settings.update { it.copy(overlaySplitRatio = activeSplitRatio) }
                        render(lastLiveState)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapTime < 360L) {
                            lastTapTime = 0L
                            activeSplitRatio = 0.45f
                            applySplitWeights(activeSplitRatio)
                            App.instance.settings.update { it.copy(overlaySplitRatio = 0.45f) }
                            render(lastLiveState)
                        } else {
                            lastTapTime = now
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applySplitWeights(ratio: Float) {
        val leftWeight = ratio.coerceIn(0.30f, 0.70f)
        val rightWeight = 1f - leftWeight
        (srcHeaderLabel?.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = leftWeight
            srcHeaderLabel?.layoutParams = it
        }
        (tgtHeaderLabel?.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = rightWeight
            tgtHeaderLabel?.layoutParams = it
        }
        if (activeRenderMode == SettingsData.OVERLAY_LAYOUT_SPLIT && activeShowSource) {
            for (h in rowHolders) {
                (h.srcPane.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.weight = leftWeight
                    h.srcPane.layoutParams = it
                }
                (h.tgtPane.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.weight = rightWeight
                    h.tgtPane.layoutParams = it
                }
            }
        }
    }

    private fun persistCurrentGeometry(lp: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        val xDp = (lp.x / density).roundToInt().coerceAtLeast(0)
        val yDp = (lp.y / density).roundToInt().coerceAtLeast(0)
        val wDp = if (lp.width > 0) (lp.width / density).roundToInt() else 0
        val hDp = if (lp.height > 0) (lp.height / density).roundToInt() else 0
        lastAppliedGeo = GeoSnapshot(xDp, yDp, wDp, hDp)
        App.instance.settings.update {
            it.copy(
                overlayXDp = xDp,
                overlayYDp = yDp,
                overlayWidthDp = wDp,
                overlayHeightDp = hDp,
            )
        }
    }

    private fun computeGeometryIntoParams(
        lp: WindowManager.LayoutParams,
        s: SettingsData,
        measuredHeight: Int,
    ) {
        val metrics = resources.displayMetrics
        val minW = minWidthPx(s)
        val maxW = (metrics.widthPixels - dp(12f)).coerceAtLeast(minW)
        val targetW = if (s.overlayWidthDp > 0) dp(s.overlayWidthDp.toFloat()).coerceIn(minW, maxW) else defaultWidthPx()
        lp.width = targetW

        val fixedHeight = s.overlayHeightDp > 0
        if (fixedHeight) {
            val targetH = dp(s.overlayHeightDp.toFloat()).coerceIn(minHeightPx(), maxHeightPx())
            lp.height = targetH
            scrollView?.layoutParams = buildScrollerLayoutParams(fixedHeight = true)
            scrollView?.maxHeightPx = Int.MAX_VALUE
        } else {
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            scrollView?.layoutParams = buildScrollerLayoutParams(fixedHeight = false)
            scrollView?.maxHeightPx = (metrics.heightPixels * 0.55f).roundToInt()
        }

        val effectiveH = if (fixedHeight) lp.height else measuredHeight.coerceAtLeast(dp(140f))
        val maxX = (metrics.widthPixels - lp.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - effectiveH).coerceAtLeast(0)
        lp.x = if (s.overlayXDp >= 0) dp(s.overlayXDp.toFloat()).coerceIn(0, maxX) else ((metrics.widthPixels - lp.width) / 2).coerceAtLeast(0)
        lp.y = if (s.overlayYDp >= 0) dp(s.overlayYDp.toFloat()).coerceIn(0, maxY) else dp(96f).coerceIn(0, maxY)
    }

    private fun applyWindowGeometry(
        v: View,
        lp: WindowManager.LayoutParams,
        s: SettingsData,
        forceClamp: Boolean = false,
    ) {
        val geo = GeoSnapshot(s.overlayXDp, s.overlayYDp, s.overlayWidthDp, s.overlayHeightDp)
        if (!forceClamp && geo == lastAppliedGeo) return
        lastAppliedGeo = geo
        computeGeometryIntoParams(lp, s, measuredHeight = v.height)
        runCatching { wm?.updateViewLayout(v, lp) }
    }

    private fun applySettings(s: SettingsData) {
        if (isInteracting) return
        val v = rootView ?: return
        val lp = params ?: return

        activeSplitRatio = s.overlaySplitRatio.coerceIn(0.30f, 0.70f)
        background?.let { bg ->
            val alpha = (s.overlayOpacity.coerceIn(0.2f, 1f) * 255).roundToInt()
            bg.setColor(Color.argb(alpha, 0x0A, 0x0E, 0x15))
            bg.setStroke(dp(1f), Color.parseColor("#4D3FE0B0"))
        }
        v.background = background

        layoutToggleBtn?.text = if (s.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT) "双栏" else "卡片"
        sourceToggleBtn?.text = if (s.showSource) "双语" else "仅译"
        val showColHeader = s.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT && s.showSource
        columnHeaderRow?.visibility = if (showColHeader) View.VISIBLE else View.GONE
        applySplitWeights(activeSplitRatio)

        applyWindowGeometry(v, lp, s, forceClamp = false)
        render(lastLiveState)
    }

    private fun buildOverlayRows(st: LiveState, settings: SettingsData): List<OverlayRow> {
        val baseCount = settings.subtitleLines.coerceIn(1, 4)
        val maxRows = if (settings.overlayHeightDp > 0) max(baseCount, 12) else baseCount
        val hasPartial = st.partialSrc.isNotBlank() || st.partialTgt.isNotBlank()
        val segLimit = if (hasPartial) (maxRows - 1).coerceAtLeast(0) else maxRows
        val totalSegs = st.segments.size
        val startIndex = (totalSegs - segLimit).coerceAtLeast(0)

        return buildList {
            for (i in startIndex until totalSegs) {
                val seg = st.segments[i]
                if (seg.src.isBlank() && seg.tgt.isBlank()) continue
                add(
                    OverlayRow(
                        badge = "#${i + 1}",
                        speaker = seg.speaker?.takeIf { it.isNotBlank() },
                        src = seg.src.trim(),
                        tgt = seg.tgt.trim().ifBlank { if (seg.src.isNotBlank()) "翻译中…" else "" },
                        isLive = false,
                        isDraft = seg.draftTgt || seg.tgt.isBlank(),
                    )
                )
            }
            if (hasPartial) {
                add(
                    OverlayRow(
                        badge = "●",
                        speaker = null,
                        src = st.partialSrc.trim(),
                        tgt = st.partialTgt.trim().ifBlank { "翻译中…" },
                        isLive = true,
                        isDraft = st.partialDraft || st.partialTgt.isBlank(),
                    )
                )
            }
        }
    }

    private fun ensureRowHolders(count: Int, layoutMode: String, showSource: Boolean) {
        val container = rowsContainer ?: return
        val rebuild = layoutMode != activeRenderMode || showSource != activeShowSource
        if (rebuild) {
            for (h in rowHolders) {
                container.removeView(h.root)
            }
            rowHolders.clear()
            activeRenderMode = layoutMode
            activeShowSource = showSource
        }
        while (rowHolders.size < count) {
            val holder = createRowHolder(layoutMode, showSource)
            rowHolders.add(holder)
            container.addView(holder.root)
        }
        for (i in rowHolders.indices) {
            rowHolders[i].root.visibility = if (i < count) View.VISIBLE else View.GONE
        }
    }

    private fun createRowHolder(layoutMode: String, showSource: Boolean): RowHolder {
        val splitBilingual = layoutMode == SettingsData.OVERLAY_LAYOUT_SPLIT && showSource
        if (splitBilingual) {
            // 左右分窗行：isBaselineAligned = false + 子列 MATCH_PARENT，保证每条原文与译文严格等高、条条对应
            val rowRoot = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(6f)
                }
            }
            val srcBg = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
            }
            val tgtBg = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
            }
            val srcPane = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                background = srcBg
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activeSplitRatio,
                )
            }
            val srcMeta = TextView(this).apply {
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(2f))
            }
            val srcText = TextView(this).apply {
                setLineSpacing(0f, 1.15f)
            }
            srcPane.addView(srcMeta)
            srcPane.addView(srcText)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6f), LinearLayout.LayoutParams.MATCH_PARENT)
            }

            val tgtPane = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                background = tgtBg
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f - activeSplitRatio,
                )
            }
            val tgtMeta = TextView(this).apply {
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(2f))
            }
            val tgtText = TextView(this).apply {
                setLineSpacing(0f, 1.18f)
            }
            tgtPane.addView(tgtMeta)
            tgtPane.addView(tgtText)

            rowRoot.addView(srcPane)
            rowRoot.addView(divider)
            rowRoot.addView(tgtPane)
            return RowHolder(
                root = rowRoot,
                srcPane = srcPane,
                srcMeta = srcMeta,
                srcText = srcText,
                divider = divider,
                tgtPane = tgtPane,
                tgtMeta = tgtMeta,
                tgtText = tgtText,
                srcBg = srcBg,
                tgtBg = tgtBg,
                cardBg = null,
            )
        } else {
            // 上下逐条独立卡片（或仅译文模式）：每句独立成卡，绝不混成一坨
            val cardBg = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
            }
            val rowRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
                background = cardBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(6f)
                }
            }
            val srcPane = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val srcMeta = TextView(this).apply {
                textSize = 10.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(1f), dp(6f), 0)
            }
            val srcText = TextView(this).apply {
                setLineSpacing(0f, 1.14f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            srcPane.addView(srcMeta)
            srcPane.addView(srcText)

            val divider = View(this).apply {
                setBackgroundColor(Color.parseColor("#1EFFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(0.8f),
                ).apply {
                    topMargin = dp(5f)
                    bottomMargin = dp(5f)
                }
            }

            val tgtPane = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val tgtText = TextView(this).apply {
                setLineSpacing(0f, 1.18f)
            }
            tgtPane.addView(tgtText)

            rowRoot.addView(srcPane)
            rowRoot.addView(divider)
            rowRoot.addView(tgtPane)
            return RowHolder(
                root = rowRoot,
                srcPane = srcPane,
                srcMeta = srcMeta,
                srcText = srcText,
                divider = divider,
                tgtPane = tgtPane,
                tgtMeta = null,
                tgtText = tgtText,
                srcBg = GradientDrawable(),
                tgtBg = GradientDrawable(),
                cardBg = cardBg,
            )
        }
    }

    private fun render(st: LiveState) {
        if (rootView == null) return
        val settings = currentSettings ?: App.instance.settings.value

        if (!isInteracting) {
            val modeLabel = if (settings.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT && settings.showSource) {
                "双栏对照"
            } else if (settings.showSource) {
                "逐条卡片"
            } else {
                "仅译文"
            }
            statusText?.text = when {
                st.running && st.paused -> "已暂停 · $modeLabel · 拖顶栏移位/拖底角缩放"
                st.running -> "同传中 · $modeLabel"
                else -> "待机 · $modeLabel · 拖底角可自由调尺寸"
            }
        }
        (statusDot?.background as? GradientDrawable)?.setColor(
            when {
                st.running && !st.paused -> Color.parseColor("#3FE0B0")
                st.running && st.paused -> Color.parseColor("#F0B856")
                else -> Color.parseColor("#7D8898")
            }
        )

        val rows = buildOverlayRows(st, settings)
        val showColHeader = settings.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT && settings.showSource
        columnHeaderRow?.visibility = if (showColHeader) View.VISIBLE else View.GONE

        if (rows.isEmpty()) {
            ensureRowHolders(0, settings.overlayLayout, settings.showSource)
            emptyStateText?.visibility = View.VISIBLE
            emptyStateText?.text = if (st.running) {
                "正在聆听… 识别到语音后将在此条条对应显示"
            } else {
                "点击「主界面」开始同传，即可在此实时条条对照显示"
            }
            return
        }

        emptyStateText?.visibility = View.GONE
        ensureRowHolders(rows.size, settings.overlayLayout, settings.showSource)

        val srcSizeSp = (settings.subtitleSize * 0.44f).coerceIn(12f, 22f)
        val tgtSizeSp = (settings.subtitleSize * 0.62f).coerceIn(14f, 30f)
        val isSplitBilingual = settings.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT && settings.showSource
        val lastIdx = rows.lastIndex

        for (i in rows.indices) {
            val row = rows[i]
            val h = rowHolders[i]
            val isActive = i == lastIdx && (row.isLive || st.running)
            val metaLabel = buildString {
                append(row.badge)
                if (!row.speaker.isNullOrBlank()) {
                    append(" · ")
                    append(row.speaker)
                } else if (row.isLive) {
                    append(" 正在说")
                }
            }

            h.srcMeta.text = metaLabel
            h.srcMeta.setTextColor(if (row.isLive) Color.parseColor("#3FE0B0") else Color.parseColor("#7E90A8"))
            h.srcText.textSize = srcSizeSp
            h.srcText.text = row.src.ifBlank { "…" }
            h.srcText.setTextColor(if (isActive) Color.parseColor("#D8E1EE") else Color.parseColor("#A5B3C6"))

            h.tgtMeta?.let { tm ->
                tm.text = when {
                    row.isLive && row.isDraft -> "${row.badge} 实时草稿"
                    row.isLive -> "${row.badge} 实时译文"
                    row.isDraft -> "${row.badge} 翻译中"
                    else -> "${row.badge} 译文"
                }
                tm.setTextColor(if (row.isLive || isActive) Color.parseColor("#5EEAD4") else Color.parseColor("#6E9D93"))
            }

            h.tgtText.textSize = tgtSizeSp
            h.tgtText.text = row.tgt.ifBlank { "翻译中…" }
            h.tgtText.typeface = Typeface.create(
                if (row.isDraft) "sans-serif" else "sans-serif-medium",
                if (row.isDraft) Typeface.ITALIC else Typeface.NORMAL,
            )
            h.tgtText.setTextColor(
                when {
                    row.isDraft -> Color.parseColor("#9DD6C5")
                    isActive -> Color.parseColor("#FFFFFF")
                    else -> Color.parseColor("#E3EAF4")
                }
            )

            if (isSplitBilingual) {
                h.srcPane.visibility = View.VISIBLE
                h.divider.visibility = View.VISIBLE
                h.srcBg.setColor(if (isActive) Color.parseColor("#20FFFFFF") else Color.parseColor("#13FFFFFF"))
                h.srcBg.setStroke(
                    dp(0.8f),
                    if (isActive) Color.parseColor("#447FA0C4") else Color.parseColor("#1EFFFFFF"),
                )
                h.tgtBg.setColor(if (isActive) Color.parseColor("#263FE0B0") else Color.parseColor("#18FFFFFF"))
                h.tgtBg.setStroke(
                    dp(0.9f),
                    if (isActive) Color.parseColor("#703FE0B0") else Color.parseColor("#24FFFFFF"),
                )
            } else {
                val showSrcInCard = settings.showSource && row.src.isNotBlank()
                h.srcPane.visibility = if (showSrcInCard) View.VISIBLE else View.GONE
                h.divider.visibility = if (showSrcInCard) View.VISIBLE else View.GONE
                h.cardBg?.setColor(if (isActive) Color.parseColor("#223FE0B0") else Color.parseColor("#15FFFFFF"))
                h.cardBg?.setStroke(
                    dp(0.9f),
                    if (isActive) Color.parseColor("#663FE0B0") else Color.parseColor("#1EFFFFFF"),
                )
            }
        }

        val sig = rows.lastOrNull()?.let { "${rows.size}:${it.badge}:${it.src.length}:${it.tgt.length}" }.orEmpty()
        if (sig != lastContentSignature) {
            lastContentSignature = sig
            if (!userScrolledUp) {
                scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
            }
        }
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
        rootView?.let { runCatching { wm?.removeView(it) } }
        rootView = null
        rowHolders.clear()
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
            setRunning(false)
            runCatching { context.stopService(Intent(context, OverlayService::class.java)) }
        }
    }
}
