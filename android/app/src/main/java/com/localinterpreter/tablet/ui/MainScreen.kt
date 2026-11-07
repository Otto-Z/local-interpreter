package com.localinterpreter.tablet.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.MainActivity
import com.localinterpreter.tablet.audio.LiveService
import com.localinterpreter.tablet.data.Course
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.llm.Langs
import com.localinterpreter.tablet.llm.DownloadProgress
import com.localinterpreter.tablet.llm.ModelKind
import com.localinterpreter.tablet.live.LiveQuestion
import com.localinterpreter.tablet.live.LiveSegment
import com.localinterpreter.tablet.live.LiveSource
import com.localinterpreter.tablet.live.LiveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    activity: MainActivity,
    liveSignal: Int,
    onStartMic: (Long?) -> Unit,
    onStartSystem: (Long?) -> Unit,
    onStartFile: (Long?) -> Unit,
) {
    val app = activity.application as App
    val context = LocalContext.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val live by app.live.state.collectAsStateWithLifecycle()
    val asrStatus by app.live.asrStatus.collectAsStateWithLifecycle()
    val llmStatus by app.llm.translatorStatus.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val initialAssistantOpen = LocalConfiguration.current.screenWidthDp >= 900
    var showAssistant by rememberSaveable { mutableStateOf(initialAssistantOpen) }
    var liveCourseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var coursesVersion by remember { mutableIntStateOf(0) }
    var showSetup by remember { mutableStateOf(!settings.firstRunDone) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(liveSignal) {
        if (liveSignal > 0) {
            tab = 0
            showSettings = false
        }
    }

    LaunchedEffect(Unit) {
        OverlayService.errors.collect { message ->
            if (message != null) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                OverlayService.errors.value = null
            }
        }
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun start(source: LiveSource) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            toast("通知权限未开启：后台同传时通知栏不会显示暂停/结束按钮")
        }
        val s = app.settings.value
        if (!app.models.asrReady()) {
            toast("请先下载识别模型")
            showSettings = true
            return
        }
        if (s.llmMode == SettingsData.MODE_BUILTIN && !app.models.translatorReady(s.modelVariant)) {
            toast("请先下载翻译模型，或在设置里改用 API")
            showSettings = true
            return
        }
        if (s.llmMode == SettingsData.MODE_API && (s.apiBaseUrl.isBlank() || s.apiModel.isBlank())) {
            toast("请先在设置里填写翻译 API")
            showSettings = true
            return
        }
        val free = app.repo.freeBytes()
        if (free < 120L * 1024 * 1024) {
            toast("存储空间只剩 ${free / 1_048_576}MB，字幕和录音可能无法保存，建议先清理")
        }
        val course = liveCourseId?.takeIf { runCatching { app.db.getCourse(it) != null }.getOrDefault(false) }
        if (course != liveCourseId) liveCourseId = course
        when (source) {
            LiveSource.MIC -> onStartMic(course)
            LiveSource.SYSTEM -> onStartSystem(course)
            LiveSource.FILE -> onStartFile(course)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val wide = maxWidth >= 900.dp

        Column(Modifier.fillMaxSize()) {
            if (!showSettings) TopBar(
                tab = tab,
                onTab = { tab = it },
                asrStatus = when {
                    app.models.asrReady() && (asrStatus == "语音模型未加载" || asrStatus == "未加载") -> "识别模型就绪"
                    !app.models.asrReady() -> "识别模型未就绪"
                    else -> asrStatus
                },
                llmStatus = when {
                    settings.llmMode == SettingsData.MODE_API -> when {
                        settings.apiBaseUrl.isBlank() || settings.apiModel.isBlank() -> "API 未配置"
                        llmStatus.startsWith("翻译失败") || llmStatus.startsWith("翻译超时") ||
                            llmStatus.startsWith("翻译模型") -> llmStatus
                        else -> "API · ${settings.apiModel}"
                    }
                    app.models.translatorReady(settings.modelVariant) && llmStatus == "未加载" -> "翻译模型就绪"
                    !app.models.translatorReady(settings.modelVariant) -> "翻译模型未就绪"
                    else -> llmStatus
                },
                asrOk = app.models.asrReady(),
                llmOk = if (settings.llmMode == SettingsData.MODE_API) {
                    settings.apiBaseUrl.isNotBlank() && settings.apiModel.isNotBlank()
                } else app.models.translatorReady(settings.modelVariant),
                showAssistant = showAssistant,
                onAssistant = { showAssistant = !showAssistant },
                onSubtitle = {
                    context.startActivity(Intent(context, SubtitleActivity::class.java))
                },
                onOverlay = {
                    if (!Settings.canDrawOverlays(context)) {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (_: Throwable) {
                            toast("请在系统设置里允许「显示在其他应用上层」")
                        }
                        toast("请允许「显示在其他应用上层」，再点一次浮字幕")
                    } else {
                        if (OverlayService.running.get()) {
                            OverlayService.stop(context)
                            toast("浮字幕已关闭")
                        } else {
                            OverlayService.start(context)
                            toast("浮字幕已开启，可在通知里关闭")
                        }
                    }
                },
                onSettings = { showSettings = true },
                wide = wide,
            )

            if (live.error != null && !showSettings) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(com.localinterpreter.tablet.ui.Danger.copy(alpha = 0.14f))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        live.error ?: "",
                        color = com.localinterpreter.tablet.ui.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MiniButton("设置", { showSettings = true })
                    IconButton(onClick = { app.live.dismissError() }) {
                        Icon(
                            Icons.Default.Close,
                            "关闭提示",
                            tint = com.localinterpreter.tablet.ui.Danger,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    showSettings -> SettingsScreen(
                        app = app,
                        onClose = { showSettings = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                    tab == 0 -> LiveScreen(
                        app = app,
                        live = live,
                        settings = settings,
                        assistantOpen = showAssistant,
                        wide = wide,
                        liveCourseId = liveCourseId,
                        onCourseChange = { liveCourseId = it; coursesVersion++ },
                        onStart = ::start,
                        onAssistantClose = { showAssistant = false },
                        onOpenSettings = { showSettings = true },
                        snackbar = snackbar,
                        coursesVersion = coursesVersion,
                    )
                    else -> CoursesScreen(
                        app = app,
                        version = coursesVersion,
                        onChanged = { coursesVersion++ },
                        onStartCourse = { id ->
                            liveCourseId = id
                            tab = 0
                            toast("已选择课程，点「开始」即可")
                        },
                        onStartNoCourse = {
                            liveCourseId = null
                            tab = 0
                            toast("点「开始」即可开始一节课")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (showAssistant && !wide && tab == 0 && !showSettings) {
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable { showAssistant = false }
                        )
                        AssistantPane(
                            app = app,
                            live = live,
                            settings = settings,
                            onClose = { showAssistant = false },
                            modifier = Modifier
                                .fillMaxWidth(0.88f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showSetup) {
        SetupDialog(
            app = app,
            settings = settings,
            onOpenSettings = {
                showSetup = false
                app.settings.update { it.copy(firstRunDone = true) }
                showSettings = true
            },
            onDismiss = {
                showSetup = false
                app.settings.update { it.copy(firstRunDone = true) }
            },
        )
    }
}

@Composable
private fun TopBar(
    tab: Int,
    onTab: (Int) -> Unit,
    asrStatus: String,
    llmStatus: String,
    asrOk: Boolean,
    llmOk: Boolean,
    showAssistant: Boolean,
    onAssistant: () -> Unit,
    onSubtitle: () -> Unit,
    onOverlay: () -> Unit,
    onSettings: () -> Unit,
    wide: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)) {
        if (wide) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Brand(wide = true)
                Row(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(3.dp)
                ) {
                    TabChip("实时", tab == 0) { onTab(0) }
                    TabChip("课程", tab == 1) { onTab(1) }
                }
                Spacer(Modifier.weight(1f))
                Pill(asrStatus, if (asrOk) Accent else Warn)
                Pill(llmStatus, if (llmOk) Accent else Warn)
                TopActions(showAssistant, onAssistant, onSubtitle, onOverlay, onSettings)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Brand(wide = false)
                    Spacer(Modifier.weight(1f))
                    Dot(if (asrOk && llmOk) Accent else Warn, 8)
                    TopActions(showAssistant, onAssistant, onSubtitle, onOverlay, onSettings)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(3.dp)
                    ) {
                        TabChip("实时", tab == 0) { onTab(0) }
                        TabChip("课程", tab == 1) { onTab(1) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (asrOk && llmOk) "模型就绪" else "模型未就绪",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (asrOk && llmOk) Accent else Warn,
                    )
                }
            }
        }
    }
}

@Composable
private fun Brand(wide: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .background(Accent, RoundedCornerShape(50))
        )
        Column {
            Text(
                "本地同传",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (wide) {
                Text(
                    "ANDROID TABLET · 完全离线",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )
            }
        }
    }
}

@Composable
private fun TopActions(
    showAssistant: Boolean,
    onAssistant: () -> Unit,
    onSubtitle: () -> Unit,
    onOverlay: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onAssistant) {
            Icon(
                Icons.Default.Assistant,
                contentDescription = "助教",
                tint = if (showAssistant) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSubtitle) {
            Icon(Icons.Default.Fullscreen, contentDescription = "字幕模式", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOverlay) {
            Icon(Icons.Default.PictureInPicture, contentDescription = "浮字幕", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TabChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun LiveScreen(
    app: App,
    live: LiveState,
    settings: SettingsData,
    assistantOpen: Boolean,
    wide: Boolean,
    liveCourseId: Long?,
    onCourseChange: (Long?) -> Unit,
    onStart: (LiveSource) -> Unit,
    onAssistantClose: () -> Unit,
    onOpenSettings: () -> Unit,
    snackbar: SnackbarHostState,
    coursesVersion: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(LiveSource.MIC) }
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val data = pendingExport
        if (uri != null && data != null) {
            app.appScope.launch {
                val ok = writeTextToUri(context, uri, data.second)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "已导出 ${data.first}" else "导出失败，请换一个存储位置", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingExport = null
    }

    LaunchedEffect(coursesVersion, live.courseId) {
        var loadedOk = false
        val loaded = try {
            withContext(Dispatchers.IO) { app.db.listCourses() }.also { loadedOk = true }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        courses = loaded
        if (loadedOk && liveCourseId != null && loaded.none { it.id == liveCourseId }) {
            onCourseChange(null)
        }
    }
    LaunchedEffect(live.running) {
        if (!live.running && live.sessionId != null) source = live.source
    }
    LaunchedEffect(live.sessionId) {
        if (live.sessionId != null && !live.running) onCourseChange(live.courseId)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            TranscriptPane(
                app = app,
                live = live,
                settings = settings,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onOpenSettings = onOpenSettings,
            )
            if (assistantOpen && wide) {
                AssistantPane(
                    app = app,
                    live = live,
                    settings = settings,
                    onClose = onAssistantClose,
                    modifier = Modifier
                        .width(380.dp)
                        .fillMaxHeight(),
                )
            }
        }

        LiveBottomBar(
            app = app,
            live = live,
            settings = settings,
            source = source,
            onSource = { source = it },
            courses = courses,
            liveCourseId = liveCourseId,
            onCourseChange = onCourseChange,
            onStart = { onStart(source) },
            onStop = { LiveService.stop(context) },
            onPause = { LiveService.togglePause(context, live.paused) },
            onBookmark = {
                app.live.bookmark()
                Toast.makeText(context, "已标记当前句", Toast.LENGTH_SHORT).show()
            },
            onExport = {
                val sid = live.sessionId
                if (sid == null) {
                    Toast.makeText(context, "还没有可导出的字幕", Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch {
                        val content = try {
                            withContext(Dispatchers.IO) { app.repo.sessionSrt(sid) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Toast.makeText(context, "导出失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        if (content.isBlank()) {
                            Toast.makeText(context, "这次课还没有字幕可导出", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val title = "同传字幕_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
                        pendingExport = "$title.srt" to content
                        exportLauncher.launch("$title.srt")
                    }
                }
            },
            onClear = { app.live.clear() },
            snackbar = snackbar,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun TranscriptPane(
    app: App,
    live: LiveState,
    settings: SettingsData,
    modifier: Modifier,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val itemCount = live.segments.size + if (live.partialSrc.isNotBlank() || live.partialTgt.isNotBlank()) 1 else 0
    LaunchedEffect(itemCount, live.partialSrc, live.partialTgt) {
        if (itemCount <= 0) return@LaunchedEffect
        // 只在用户本来就在底部时自动跟随；用即时 scrollToItem，语速快时不会被动画拖在后面。
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= itemCount - 2) {
            listState.scrollToItem(itemCount - 1)
        }
    }
    if (live.segments.isEmpty() && live.partialSrc.isBlank()) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(60.dp))
            EmptyHint(
                title = when {
                    live.paused -> "已暂停"
                    live.running && live.source == LiveSource.FILE -> "正在全速转写音频文件…"
                    live.running && live.source == LiveSource.SYSTEM -> "正在听系统声音…"
                    live.running -> "正在听麦克风…"
                    else -> "开始一次同传"
                },
                subtitle = when {
                    live.paused -> "点底部的「继续」接着同一堂课记录。"
                    live.running && live.source == LiveSource.FILE -> "音频文件按设备处理能力全速转写，说话部分会自动切句、识别并翻译。"
                    live.running -> {
                        val pct = live.audioLevel * 100
                        val hint = if (live.audioLevel < 0.005f) {
                            "（电平偏低：请检查麦克风/音量，或确认系统声音正在播放）"
                        } else ""
                        "输入电平 ${"%.2f".format(pct)}%$hint。说话后约 0.3–2 秒出现原文和译文。"
                    }
                    else -> "选好音频来源，点底部的「开始」。全部在本机完成，不联网、不上传。"
                },
            )
            if (!live.running) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniButton("检查模型", { onOpenSettings() }, accent = true)
                    MiniButton("字幕模式设置", { onOpenSettings() })
                }
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = if (settings.assistantEnabled) 28.dp else 64.dp,
            end = if (settings.assistantEnabled) 28.dp else 64.dp,
            top = 20.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(live.segments, key = { it.id }) { seg ->
            TranscriptRow(seg)
        }
        if (live.partialSrc.isNotBlank() || live.partialTgt.isNotBlank()) {
            item(key = "partial") {
                PartialRow(live, settings.draftTranslate)
            }
        }
    }
}

@Composable
private fun TranscriptRow(seg: LiveSegment) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            Modifier.width(64.dp).padding(top = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                formatSeconds(seg.t0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (seg.lowConfidence) {
                Text("≈ 存疑", style = MaterialTheme.typography.labelSmall, color = Warn)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (seg.speaker != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(Color(android.graphics.Color.parseColor(seg.speakerColor ?: "#4fd1a5")), RoundedCornerShape(50)))
                    Text(seg.speaker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (seg.srcLang.isNotBlank()) {
                        Text(
                            Langs.tagLabel(seg.srcLang),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            if (seg.src.isNotBlank()) {
                Text(
                    seg.src,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = if (seg.corrected) TextDecoration.Underline else TextDecoration.None,
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                seg.tgt.ifBlank { if (seg.src.isNotBlank()) "译文中…" else "" },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 31.sp),
                color = if (seg.tgt.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PartialRow(live: LiveState, draftEnabled: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(Modifier.width(64.dp).padding(top = 4.dp), horizontalAlignment = Alignment.End) {
            if (live.speaking) {
                Dot(Accent, 8)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (live.partialSrc.isNotBlank()) {
                Text(
                    live.partialSrc,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(3.dp))
            }
            if (live.partialTgt.isNotBlank()) {
                Text(
                    live.partialTgt,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            } else if (draftEnabled && live.partialSrc.isNotBlank() && live.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("边说边译…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LiveBottomBar(
    app: App,
    live: LiveState,
    settings: SettingsData,
    source: LiveSource,
    onSource: (LiveSource) -> Unit,
    courses: List<Course>,
    liveCourseId: Long?,
    onCourseChange: (Long?) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onBookmark: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            val compact = maxWidth < 880.dp
            val courseOptions = listOf("0" to "未分类") + courses.map { it.id.toString() to it.name }
            val courseValue = (if (live.running || live.starting) (live.courseId ?: 0L) else (liveCourseId ?: 0L)).toString()

            @Composable
            fun Controls() {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dropdown(
                        value = (if (live.running || live.starting) live.source else source).name,
                        options = LiveSource.entries.map { it.name to it.label },
                        onChange = { onSource(LiveSource.valueOf(it)) },
                        enabled = !live.running && !live.starting && !live.stopping,
                        label = "来源",
                        minWidth = 82,
                    )
                    Dropdown(
                        value = settings.sourceLang,
                        options = Langs.sources,
                        onChange = { v -> app.settings.update { it.copy(sourceLang = v) } },
                        enabled = !live.running && !live.starting && !live.stopping,
                        label = "识别",
                        minWidth = 74,
                    )
                    Dropdown(
                        value = settings.targetLang,
                        options = Langs.targets,
                        onChange = { v -> app.settings.update { it.copy(targetLang = v) } },
                        enabled = !live.running && !live.starting && !live.stopping,
                        label = "翻译成",
                        minWidth = 88,
                    )
                    Dropdown(
                        value = courseValue,
                        options = courseOptions,
                        onChange = { v ->
                            if (!live.running && !live.stopping) onCourseChange(v.toLongOrNull()?.takeIf { it > 0 })
                        },
                        enabled = !live.running && !live.starting && !live.stopping,
                        label = "课程",
                        minWidth = 110,
                    )
                }
            }

            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = when {
                            live.stopping -> "收尾中…"
                            live.running -> "结束"
                            live.starting -> "取消启动"
                            else -> "开始"
                        },
                        onClick = { if (!live.stopping) { if (live.running || live.starting) onStop() else onStart() } },
                        enabled = !live.stopping,
                        danger = live.running,
                        modifier = Modifier.width(116.dp),
                    )
                    if (live.running && !live.stopping) {
                        MiniButton(if (live.paused) "继续" else "暂停", onPause)
                    }
                    Spacer(Modifier.width(2.dp))
                    Controls()
                    Spacer(Modifier.weight(1f))
                    if ((!live.running || live.stopping) && live.status.isNotBlank() && live.status != "就绪") {
                        Text(
                            live.status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (live.lastAsrMs > 0) {
                        Text(
                            "识别 ${live.lastAsrMs}ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (live.lastLatencyMs > 0) {
                        Text(
                            "首字 ${live.lastLatencyMs}ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (live.running && !live.stopping) {
                        MiniButton("标记", onBookmark)
                    }
                    if (!live.running && !live.stopping && live.segments.any { it.needsTranslate() }) {
                        MiniButton("补译", { app.live.retranslateMissing() }, accent = true)
                    }
                    MiniButton("导出", onExport, enabled = live.sessionId != null)
                    MiniButton("清空", onClear, enabled = !live.running && !live.stopping && live.segments.isNotEmpty())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = when {
                                live.stopping -> "收尾中…"
                                live.running -> "结束"
                                live.starting -> "取消启动"
                                else -> "开始"
                            },
                            onClick = { if (!live.stopping) { if (live.running || live.starting) onStop() else onStart() } },
                            enabled = !live.stopping,
                            danger = live.running,
                            modifier = Modifier.weight(1f),
                        )
                        if (live.running && !live.stopping) {
                            MiniButton(if (live.paused) "继续" else "暂停", onPause)
                        }
                        if (!live.running && !live.stopping && live.segments.any { it.needsTranslate() }) {
                            MiniButton("补译", { app.live.retranslateMissing() }, accent = true)
                        }
                        MiniButton("导出", onExport, enabled = live.sessionId != null)
                        MiniButton("清空", onClear, enabled = !live.running && !live.stopping && live.segments.isNotEmpty())
                    }
                    Controls()
                    if (live.lastLatencyMs > 0) {
                        Text(
                            (if (live.lastAsrMs > 0) "识别 ${live.lastAsrMs}ms · " else "") +
                                "翻译首字 ${live.lastLatencyMs}ms · " + when {
                                live.stopping -> live.status.ifBlank { "正在收尾…" }
                                live.paused -> "已暂停"
                                live.running -> "同传中"
                                live.status.isNotBlank() -> live.status
                                else -> "就绪"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if ((!live.running || live.stopping) && live.status.isNotBlank()) {
                        Text(
                            live.status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantPane(
    app: App,
    live: LiveState,
    settings: SettingsData,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Assistant, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("课堂助教", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (live.assistantBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.6.dp, color = Accent)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { app.live.recap(5) }, enabled = live.running) {
                    Text("最近总结", fontSize = 12.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "收起") }
            }
            if (!settings.assistantEnabled) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "助教已关闭。开启后，每 60 秒生成要点，并自动回答课堂提问。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                MiniButton("开启助教", { app.live.updateAssistantEnabled(true) }, accent = true)
                return@Column
            }
            val analysisApiReady = settings.analysisMode == SettingsData.MODE_API &&
                (settings.analysisBaseUrl.ifBlank { settings.apiBaseUrl }).isNotBlank() &&
                (settings.analysisModel.ifBlank { settings.apiModel }).isNotBlank()
            if (settings.analysisMode == SettingsData.MODE_BUILTIN && !app.models.assistantReady(settings.assistantVariant)) {
                Spacer(Modifier.height(10.dp))
                val problem = app.models.fileProblem(app.models.assistantKind(settings.assistantVariant))
                Text(
                    if (problem == "未下载" || problem == null)
                        "助教模型未下载（4B 约 2.5GB / 轻量 1.5B 约 1.1GB）。也可以到设置 → 助教，把分析模型改成 API。"
                    else "助教模型异常：$problem。请到设置 → 模型管理重新下载，或改用 API。",
                    style = MaterialTheme.typography.labelMedium,
                    color = Warn,
                )
            } else if (settings.analysisMode == SettingsData.MODE_API && !analysisApiReady) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "助教 API 未配置。请到设置 → 课堂助教填写接口地址和模型。",
                    style = MaterialTheme.typography.labelMedium,
                    color = Warn,
                )
            }
            Divider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text("本场要点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            SelectionContainer {
                                Text(
                                    live.summary.ifBlank {
                                        if (live.running) "开始后每 60 秒自动生成，也可以点右上「最近总结」。"
                                        else "开始一次同传后，这里会持续更新课堂要点。"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (live.summary.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text("问答", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        if (live.questions.isEmpty()) {
                            Text(
                                "识别到课堂提问时会在这里给出答案。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(live.questions, key = { it.id }) { q ->
                                    QuestionCard(q)
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (live.sessionId == null) "开始一次同传后可提问…" else "针对本场内容提问…",
                            fontSize = 13.sp,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(10.dp),
                    enabled = live.sessionId != null,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = live.sessionId != null,
                    onClick = {
                        if (input.isNotBlank()) {
                            app.live.ask(input)
                            input = ""
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.Send,
                        "发送",
                        tint = if (live.sessionId != null) Accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(q: LiveQuestion) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (q.source.isNotBlank()) {
                Text(
                    q.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    modifier = Modifier
                        .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                q.question,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (q.pending) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Accent)
            }
        }
        if (q.questionTgt.isNotBlank()) {
            Text(
                q.questionTgt,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            q.answer.ifBlank { if (q.pending) "思考中…" else "" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (q.answerAlt.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                q.answerAlt,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("AI 生成，仅供参考", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun SetupDialog(
    app: App,
    settings: SettingsData,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val progress by app.models.progress.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("先准备模型") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "全部在本机运行。需要下载两个离线模型：识别约 240MB，翻译约 1.1GB。也可以改用局域网里的 OpenAI 兼容服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                SetupModelRow(ModelKind.SENSEVOICE, app, progress[ModelKind.SENSEVOICE.id], settings)
                Spacer(Modifier.height(8.dp))
                SetupModelRow(ModelKind.TRANSLATOR, app, progress[ModelKind.TRANSLATOR.id], settings)
                Spacer(Modifier.height(6.dp))
                Text(
                    "下载时请保持应用在前台；中断后可以断点续传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("完成，去设置", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}

@Composable
private fun SetupModelRow(kind: ModelKind, app: App, p: DownloadProgress?, settings: SettingsData) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp))
            Text(kind.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p?.state == "downloading") {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Accent)
                Text(p.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            app.models.isReady(kind) -> Text("已就绪", color = Accent, style = MaterialTheme.typography.labelMedium)
            p?.state == "downloading" -> MiniButton("取消", { app.models.cancel(kind) })
            else -> MiniButton("下载", { app.models.download(kind, settings.hfMirror) }, accent = true)
        }
    }
}

private fun LiveSegment.needsTranslate(): Boolean =
    tgt.isBlank() || tgt.startsWith("（翻译失败") || tgt.startsWith("（未翻译")

private fun formatSeconds(t: Double): String {
    val s = t.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%02d:%02d".format(s / 60, s % 60)
}
