package com.localinterpreter.tablet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.MainActivity
import com.localinterpreter.tablet.audio.LiveService
import com.localinterpreter.tablet.data.Course
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.live.LiveQuestion
import com.localinterpreter.tablet.live.LiveSegment
import com.localinterpreter.tablet.live.LiveSource
import com.localinterpreter.tablet.live.LiveState
import com.localinterpreter.tablet.llm.DownloadProgress
import com.localinterpreter.tablet.llm.Langs
import com.localinterpreter.tablet.llm.ModelKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAB_LIVE = 0
private const val TAB_COURSES = 1
private const val TAB_SETTINGS = 2

@Composable
fun MainScreen(
    activity: MainActivity,
    liveSignal: Int,
    onStartMic: (Long?) -> Unit,
    onStartSystem: (Long?) -> Unit,
    onStartFile: (Long?) -> Unit,
) {
    val c = AppColors
    val app = activity.application as App
    val context = LocalContext.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val live by app.live.state.collectAsStateWithLifecycle()
    val asrStatus by app.live.asrStatus.collectAsStateWithLifecycle()
    val llmStatus by app.llm.translatorStatus.collectAsStateWithLifecycle()
    val modelProgress by app.models.progress.collectAsStateWithLifecycle()
    val overlayActive by OverlayService.isRunning.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(TAB_LIVE) }
    var settingsSection by rememberSaveable { mutableIntStateOf(SettingsSection.AUDIO_ASR) }
    val initialAssistantOpen = LocalConfiguration.current.screenWidthDp >= 960
    var showAssistant by rememberSaveable { mutableStateOf(initialAssistantOpen) }
    var liveCourseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var coursesVersion by remember { mutableIntStateOf(0) }
    var showSetup by remember { mutableStateOf(!settings.firstRunDone) }
    val snackbar = remember { SnackbarHostState() }

    val asrReady = remember(modelProgress, tab) { app.models.asrReady() }
    val translatorReady = remember(modelProgress, settings.modelVariant, tab) {
        app.models.translatorReady(settings.modelVariant)
    }
    val apiConfigured = settings.apiBaseUrl.isNotBlank() && settings.apiModel.isNotBlank()
    val llmOk = if (settings.llmMode == SettingsData.MODE_API) apiConfigured else translatorReady
    val allReady = asrReady && llmOk

    fun openSettings(section: Int = SettingsSection.AUDIO_ASR) {
        settingsSection = section
        tab = TAB_SETTINGS
    }

    LaunchedEffect(liveSignal) {
        if (liveSignal > 0) {
            tab = TAB_LIVE
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
            openSettings(SettingsSection.MODELS)
            return
        }
        if (s.llmMode == SettingsData.MODE_BUILTIN && !app.models.translatorReady(s.modelVariant)) {
            toast("请先下载翻译模型，或在设置里改用 API")
            openSettings(SettingsSection.MODELS)
            return
        }
        if (s.llmMode == SettingsData.MODE_API && (s.apiBaseUrl.isBlank() || s.apiModel.isBlank())) {
            toast("请先在设置里填写翻译 API")
            openSettings(SettingsSection.TRANSLATE_AI)
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
        val wide = maxWidth >= 920.dp

        BackHandler(enabled = tab == TAB_SETTINGS) {
            tab = TAB_LIVE
        }
        BackHandler(enabled = tab == TAB_LIVE && !wide && showAssistant) {
            showAssistant = false
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(
                tab = tab,
                onTab = { tab = it },
                live = live,
                asrStatus = when {
                    asrReady && (asrStatus == "语音模型未加载" || asrStatus == "未加载") -> "识别就绪"
                    !asrReady -> "缺识别模型"
                    else -> asrStatus
                },
                llmStatus = when {
                    settings.llmMode == SettingsData.MODE_API -> when {
                        !apiConfigured -> "API 未配置"
                        llmStatus.startsWith("翻译失败") || llmStatus.startsWith("翻译超时") ||
                            llmStatus.startsWith("翻译模型") -> llmStatus
                        else -> "API · ${settings.apiModel}"
                    }
                    translatorReady && llmStatus == "未加载" -> "翻译就绪"
                    !translatorReady -> "缺翻译模型"
                    else -> llmStatus
                },
                asrOk = asrReady,
                llmOk = llmOk,
                showAssistant = showAssistant,
                overlayActive = overlayActive,
                onStatusClick = {
                    if (!asrReady || (settings.llmMode == SettingsData.MODE_BUILTIN && !translatorReady)) {
                        openSettings(SettingsSection.MODELS)
                    } else {
                        openSettings(SettingsSection.TRANSLATE_AI)
                    }
                },
                onAssistant = { showAssistant = !showAssistant },
                onSubtitle = { context.startActivity(Intent(context, SubtitleActivity::class.java)) },
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
                            toast("浮字幕已开启")
                        }
                    }
                },
                wide = wide,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (live.error != null && tab != TAB_SETTINGS) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.dangerSoft)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        live.error ?: "",
                        color = c.danger,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MiniButton("查看设置", { openSettings(SettingsSection.MODELS) })
                    IconButton(onClick = { app.live.dismissError() }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, "关闭提示", tint = c.danger, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    TAB_SETTINGS -> SettingsScreen(
                        app = app,
                        initialSection = settingsSection,
                        onClose = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    TAB_COURSES -> CoursesScreen(
                        app = app,
                        version = coursesVersion,
                        onChanged = { coursesVersion++ },
                        onStartCourse = { id ->
                            liveCourseId = id
                            tab = TAB_LIVE
                            toast("已选择课程，点「开始同传」即可")
                        },
                        onStartNoCourse = {
                            liveCourseId = null
                            tab = TAB_LIVE
                            toast("点「开始同传」即可开始一节课")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LiveScreen(
                        app = app,
                        live = live,
                        settings = settings,
                        allReady = allReady,
                        assistantOpen = showAssistant,
                        wide = wide,
                        liveCourseId = liveCourseId,
                        onCourseChange = { liveCourseId = it; coursesVersion++ },
                        onStart = ::start,
                        onAssistantClose = { showAssistant = false },
                        onOpenSettings = ::openSettings,
                        snackbar = snackbar,
                        coursesVersion = coursesVersion,
                    )
                }

                if (showAssistant && !wide && tab == TAB_LIVE) {
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(Color.Black.copy(alpha = 0.42f))
                                .clickable { showAssistant = false }
                        )
                        AssistantPane(
                            app = app,
                            live = live,
                            settings = settings,
                            onClose = { showAssistant = false },
                            onOpenSettings = { showAssistant = false; openSettings(SettingsSection.TRANSLATE_AI) },
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .widthIn(max = 420.dp)
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
                openSettings(SettingsSection.MODELS)
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
    live: LiveState,
    asrStatus: String,
    llmStatus: String,
    asrOk: Boolean,
    llmOk: Boolean,
    showAssistant: Boolean,
    overlayActive: Boolean,
    onStatusClick: () -> Unit,
    onAssistant: () -> Unit,
    onSubtitle: () -> Unit,
    onOverlay: () -> Unit,
    wide: Boolean,
) {
    val c = AppColors
    val modelsReady = asrOk && llmOk
    Surface(color = MaterialTheme.colorScheme.surface) {
        if (wide) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Brand(wide = true)
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    NavTabChip("实时同传", Icons.Default.Mic, tab == TAB_LIVE) { onTab(TAB_LIVE) }
                    NavTabChip("课程库", Icons.AutoMirrored.Filled.MenuBook, tab == TAB_COURSES) { onTab(TAB_COURSES) }
                    NavTabChip("设置", Icons.Default.Settings, tab == TAB_SETTINGS, badgeDot = !modelsReady) { onTab(TAB_SETTINGS) }
                }
                if (live.running && tab != TAB_LIVE) {
                    Pill(
                        text = if (live.paused) "同传已暂停 · 返回" else "同传进行中 · 返回",
                        color = if (live.paused) c.warn else c.accent,
                        pulse = !live.paused,
                        onClick = { onTab(TAB_LIVE) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (tab == TAB_LIVE) {
                    Pill(
                        text = if (modelsReady) "$asrStatus · $llmStatus" else "模型未就绪 · 去下载",
                        color = if (modelsReady) c.accent else c.warn,
                        onClick = onStatusClick,
                    )
                }
                if (tab == TAB_LIVE || live.running) {
                    IconActionChip(
                        icon = Icons.Default.Fullscreen,
                        label = "全屏字幕",
                        contentDescription = "全屏字幕模式",
                        onClick = onSubtitle,
                    )
                    IconActionChip(
                        icon = Icons.Default.PictureInPicture,
                        label = "浮字幕",
                        contentDescription = if (overlayActive) "关闭浮字幕" else "开启浮字幕",
                        active = overlayActive,
                        onClick = onOverlay,
                    )
                }
                if (tab == TAB_LIVE) {
                    IconActionChip(
                        icon = Icons.Default.Assistant,
                        label = "助教",
                        contentDescription = "课堂助教",
                        active = showAssistant,
                        onClick = onAssistant,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Brand(wide = false)
                    if (live.running && tab != TAB_LIVE) {
                        Pill(
                            text = "返回同传",
                            color = c.accent,
                            pulse = !live.paused,
                            onClick = { onTab(TAB_LIVE) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (tab == TAB_LIVE || live.running) {
                        IconActionChip(
                            icon = Icons.Default.Fullscreen,
                            contentDescription = "全屏字幕",
                            onClick = onSubtitle,
                        )
                        IconActionChip(
                            icon = Icons.Default.PictureInPicture,
                            contentDescription = "浮字幕",
                            active = overlayActive,
                            onClick = onOverlay,
                        )
                    }
                    if (tab == TAB_LIVE) {
                        IconActionChip(
                            icon = Icons.Default.Assistant,
                            contentDescription = "助教",
                            active = showAssistant,
                            onClick = onAssistant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        NavTabChip("同传", Icons.Default.Mic, tab == TAB_LIVE, compact = true) { onTab(TAB_LIVE) }
                        NavTabChip("课程", Icons.AutoMirrored.Filled.MenuBook, tab == TAB_COURSES, compact = true) { onTab(TAB_COURSES) }
                        NavTabChip("设置", Icons.Default.Settings, tab == TAB_SETTINGS, compact = true, badgeDot = !modelsReady) { onTab(TAB_SETTINGS) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!modelsReady) {
                        Pill("缺模型", c.warn, onClick = onStatusClick)
                    } else if (tab == TAB_LIVE) {
                        Pill("离线就绪", c.accent, onClick = onStatusClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun Brand(wide: Boolean) {
    val c = AppColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(if (wide) 34.dp else 28.dp)
                .background(c.accentSoft, RoundedCornerShape(10.dp))
                .border(1.dp, c.accentBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = c.accent, modifier = Modifier.size(if (wide) 20.dp else 16.dp))
        }
        Column {
            Text(
                "本地同传",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = if (wide) 17.sp else 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (wide) {
                Text(
                    "OFFLINE INTERPRETER",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun NavTabChip(
    text: String,
    icon: ImageVector,
    active: Boolean,
    compact: Boolean = false,
    badgeDot: Boolean = false,
    onClick: () -> Unit,
) {
    val c = AppColors
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent, shape)
            .then(
                if (active) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 11.dp else 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        if (badgeDot) {
            Dot(c.warn, 6)
        }
    }
}

@Composable
fun LiveScreen(
    app: App,
    live: LiveState,
    settings: SettingsData,
    allReady: Boolean,
    assistantOpen: Boolean,
    wide: Boolean,
    liveCourseId: Long?,
    onCourseChange: (Long?) -> Unit,
    onStart: (LiveSource) -> Unit,
    onAssistantClose: () -> Unit,
    onOpenSettings: (Int) -> Unit,
    snackbar: SnackbarHostState,
    coursesVersion: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(LiveSource.MIC) }
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun handleExportUri(uri: Uri?) {
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
    val exportSrtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { handleExportUri(it) }
    val exportTxtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { handleExportUri(it) }
    val exportMdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { handleExportUri(it) }

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

    val activeCourseName = remember(courses, live.courseId, liveCourseId, live.running, live.starting) {
        val id = if (live.running || live.starting) live.courseId else liveCourseId
        courses.firstOrNull { it.id == id }?.name
    }

    fun exportSession(fmt: String) {
        val sid = live.sessionId
        if (sid == null) {
            Toast.makeText(context, "还没有可导出的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val content = try {
                withContext(Dispatchers.IO) {
                    when (fmt) {
                        "txt" -> app.repo.sessionTxt(sid)
                        "md" -> app.repo.sessionMarkdown(sid)
                        else -> app.repo.sessionSrt(sid)
                    }
                }
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
            val filename = "$title.$fmt"
            pendingExport = filename to content
            when (fmt) {
                "txt" -> exportTxtLauncher.launch(filename)
                "md" -> exportMdLauncher.launch(filename)
                else -> exportSrtLauncher.launch(filename)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            TranscriptPane(
                app = app,
                live = live,
                settings = settings,
                allReady = allReady,
                selectedSource = source,
                activeCourseName = activeCourseName,
                onSelectSource = { source = it },
                onStartSelected = { onStart(source) },
                onExport = ::exportSession,
                onClear = { app.live.clear() },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            if (assistantOpen && wide) {
                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                AssistantPane(
                    app = app,
                    live = live,
                    settings = settings,
                    onClose = onAssistantClose,
                    onOpenSettings = { onOpenSettings(SettingsSection.TRANSLATE_AI) },
                    modifier = Modifier
                        .width(380.dp)
                        .fillMaxHeight(),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                val saved = app.live.bookmark()
                Toast.makeText(
                    context,
                    if (saved) "已标记最新一句" else "还没有可标记的字幕",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            snackbar = snackbar,
        )
    }
}

@Composable
private fun TranscriptPane(
    app: App,
    live: LiveState,
    settings: SettingsData,
    allReady: Boolean,
    selectedSource: LiveSource,
    activeCourseName: String?,
    onSelectSource: (LiveSource) -> Unit,
    onStartSelected: () -> Unit,
    onExport: (String) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: (Int) -> Unit,
    modifier: Modifier,
) {
    val c = AppColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var renamingSpeaker by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var editingSegment by remember { mutableStateOf<LiveSegment?>(null) }
    var addingTermSeg by remember { mutableStateOf<LiveSegment?>(null) }
    var exportMenuOpen by remember { mutableStateOf(false) }

    val hasPartial = live.partialSrc.isNotBlank() || live.partialTgt.isNotBlank()
    val contentCount = live.segments.size + if (hasPartial) 1 else 0
    val totalItems = contentCount + 1
    val lastTgtLen = live.segments.lastOrNull()?.tgt?.length ?: 0

    LaunchedEffect(contentCount, lastTgtLen, live.partialSrc, live.partialTgt) {
        if (contentCount <= 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= totalItems - 3 || lastVisible < 0) {
            listState.scrollToItem(totalItems - 1)
        }
    }

    val showJumpToBottom by remember(totalItems) {
        derivedStateOf {
            if (contentCount <= 2) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible < totalItems - 3
        }
    }

    // 空状态：未开始时展示直观的同传准备台；监听中但未出句时展示实时电平卡片。
    if (live.segments.isEmpty() && !hasPartial) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (live.running || live.starting) {
                    SectionCard {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PulseDot(if (live.paused) c.warn else c.accent, active = !live.paused, size = 10)
                                Text(
                                    when {
                                        live.starting -> "正在加载离线模型…"
                                        live.paused -> "同传已暂停"
                                        live.source == LiveSource.FILE -> "正在全速离线转写音频文件…"
                                        live.source == LiveSource.SYSTEM -> "正在监听系统音频…"
                                        else -> "正在聆听麦克风…"
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                            if (live.running && !live.paused && live.source != LiveSource.FILE) {
                                AudioLevelMeter(live.audioLevel, live.speaking, large = true)
                                val pct = live.audioLevel * 100
                                Text(
                                    if (live.audioLevel < 0.004f) {
                                        "当前输入电平 ${"%.2f".format(pct)}%（较安静，开始说话后 0.3–1.5 秒自动出句）"
                                    } else {
                                        "当前输入电平 ${"%.2f".format(pct)}% · 识别与翻译全部在本机运行"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (live.paused) {
                                Text(
                                    "点击底部「继续」即可在同一课次中接着记录。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "音频文件按设备算力全速切句、识别并翻译，请稍候片刻。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    if (!allReady) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(c.warnSoft, RoundedCornerShape(14.dp))
                                .border(1.dp, c.warn.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Dot(c.warn, 9)
                            Column(Modifier.weight(1f)) {
                                Text("离线模型尚未就绪", style = MaterialTheme.typography.titleMedium, color = c.warn)
                                Text(
                                    "首次使用需下载识别模型（~240MB）与翻译模型（~1.1GB），下载后可完全断网使用。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MiniButton("去下载", { onOpenSettings(SettingsSection.MODELS) }, accent = true, icon = Icons.Default.Download)
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("选择音频来源，开始离线同传", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "SenseVoice 识别 + Hy-MT2 翻译全部在平板本地完成，不上传音频、不依赖网络。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SourceHeroCard(
                            title = "麦克风",
                            subtitle = "教室讲课 · 线下会议",
                            icon = Icons.Default.Mic,
                            selected = selectedSource == LiveSource.MIC,
                            onClick = { onSelectSource(LiveSource.MIC) },
                            modifier = Modifier.weight(1f),
                        )
                        SourceHeroCard(
                            title = "系统音频",
                            subtitle = "网课视频 · 线上直播",
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            selected = selectedSource == LiveSource.SYSTEM,
                            onClick = { onSelectSource(LiveSource.SYSTEM) },
                            modifier = Modifier.weight(1f),
                        )
                        SourceHeroCard(
                            title = "音频文件",
                            subtitle = "录音导入 · 全速转写",
                            icon = Icons.Default.AudioFile,
                            selected = selectedSource == LiveSource.FILE,
                            onClick = { onSelectSource(LiveSource.FILE) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    val noiseLabel = when (settings.noiseSuppression) {
                        SettingsData.NOISE_SENSITIVE -> "灵敏"
                        SettingsData.NOISE_STRICT -> "强力"
                        else -> "标准"
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(
                            text = "抗噪：$noiseLabel" + if (settings.calibratedNoiseFloor > 0f) " · 已校准" else " · 可测噪校准",
                            color = if (settings.calibratedNoiseFloor > 0f) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { onOpenSettings(SettingsSection.AUDIO_ASR) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Pill(
                            text = "${Langs.name(settings.sourceLang)} → ${Langs.name(settings.targetLang)}",
                            color = c.secondary,
                            onClick = {
                                val (nextSrc, nextTgt) = swapLanguagePair(settings.sourceLang, settings.targetLang)
                                app.settings.update { it.copy(sourceLang = nextSrc, targetLang = nextTgt) }
                            },
                        )
                        if (activeCourseName != null) {
                            Spacer(Modifier.width(8.dp))
                            Pill("课程：$activeCourseName", c.accent)
                        }
                    }

                    PrimaryButton(
                        text = "开始${selectedSource.label}同传",
                        icon = Icons.Default.PlayArrow,
                        onClick = onStartSelected,
                        modifier = Modifier.widthIn(min = 210.dp),
                    )
                }
            }
        }
        return
    }

    Column(modifier = modifier) {
        // 顶部精简实时工具条：展示当前课次状态 + 抗噪快切 + 双语/仅译文 + 字号 + 导出 + 清空
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val srcIcon = when (live.source) {
                LiveSource.SYSTEM -> Icons.AutoMirrored.Filled.VolumeUp
                LiveSource.FILE -> Icons.Default.AudioFile
                LiveSource.MIC -> Icons.Default.Mic
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live.running) {
                    PulseDot(if (live.paused) c.warn else c.accent, active = !live.paused, size = 7)
                } else {
                    Icon(srcIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                }
                Text(
                    buildString {
                        append(activeCourseName ?: live.source.label)
                        append(" · ${live.segments.size} 句")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1 键循环切换环境抗噪档位
                val noiseText = when (settings.noiseSuppression) {
                    SettingsData.NOISE_SENSITIVE -> "抗噪: 灵敏"
                    SettingsData.NOISE_STRICT -> "抗噪: 强力"
                    else -> "抗噪: 标准"
                }
                MiniButton(
                    text = noiseText,
                    onClick = {
                        val next = when (settings.noiseSuppression) {
                            SettingsData.NOISE_SENSITIVE -> SettingsData.NOISE_STANDARD
                            SettingsData.NOISE_STANDARD -> SettingsData.NOISE_STRICT
                            else -> SettingsData.NOISE_SENSITIVE
                        }
                        app.settings.update { it.copy(noiseSuppression = next) }
                    },
                    accent = settings.noiseSuppression == SettingsData.NOISE_STRICT,
                )
                // 双语 / 仅译文切换
                MiniButton(
                    text = if (settings.liveShowSource) "双语" else "仅译文",
                    onClick = { app.settings.update { it.copy(liveShowSource = !it.liveShowSource) } },
                    accent = !settings.liveShowSource,
                )
                // 紧凑字号缩放器
                Row(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "A-",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (settings.liveFontSize > 16) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = settings.liveFontSize > 16) {
                                app.settings.update { it.copy(liveFontSize = (it.liveFontSize - 2).coerceAtLeast(16)) }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                    Text(
                        "A+",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (settings.liveFontSize < 30) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = settings.liveFontSize < 30) {
                                app.settings.update { it.copy(liveFontSize = (it.liveFontSize + 2).coerceAtMost(30)) }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                Box {
                    MiniButton("导出 ▾", { exportMenuOpen = true }, enabled = live.sessionId != null)
                    DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("SRT 字幕 (.srt)") }, onClick = { exportMenuOpen = false; onExport("srt") })
                        DropdownMenuItem(text = { Text("纯文本 (.txt)") }, onClick = { exportMenuOpen = false; onExport("txt") })
                        DropdownMenuItem(text = { Text("Markdown 笔记 (.md)") }, onClick = { exportMenuOpen = false; onExport("md") })
                    }
                }
                if (!live.running && !live.stopping && live.segments.isNotEmpty()) {
                    IconActionChip(
                        icon = Icons.Default.DeleteSweep,
                        contentDescription = "清空当前屏幕",
                        onClick = onClear,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(live.segments, key = { it.id }) { seg ->
                    TranscriptRow(
                        seg = seg,
                        fontSizeSp = settings.liveFontSize,
                        showSource = settings.liveShowSource,
                        canAddGlossary = live.courseId != null,
                        onBookmark = if (seg.id > 0) {
                            {
                                if (app.live.bookmarkSegment(seg.id)) {
                                    Toast.makeText(context, "已标记该句", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else null,
                        onCopy = {
                            val text = listOf(seg.src, seg.tgt).filter { it.isNotBlank() }.joinToString("\n")
                            if (text.isNotBlank()) {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("subtitle", text))
                                Toast.makeText(context, "已复制该句字幕", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRetranslate = if (seg.id > 0) {
                            {
                                if (app.live.retranslateSegment(seg.id)) {
                                    Toast.makeText(context, "已加入重译队列", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else null,
                        onEdit = if (seg.id > 0) { { editingSegment = seg } } else null,
                        onAddTerm = if (live.courseId != null) { { addingTermSeg = seg } } else null,
                        onRenameSpeaker = if (seg.speakerId != null && seg.speakerId > 0 && seg.speaker != null) {
                            { renamingSpeaker = seg.speakerId to seg.speaker }
                        } else null,
                    )
                }
                if (hasPartial) {
                    item(key = "partial") {
                        PartialRow(live, settings.draftTranslate, settings.liveFontSize, settings.liveShowSource)
                    }
                }
                item(key = "bottom_anchor") {
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (showJumpToBottom) {
                Surface(
                    onClick = { scope.launch { listState.animateScrollToItem(totalItems - 1) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        Modifier
                            .border(1.dp, c.accentBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到最新", tint = c.accent, modifier = Modifier.size(16.dp))
                        Text("回到最新", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    }
                }
            }
        }
    }

    val renaming = renamingSpeaker
    if (renaming != null) {
        var newName by remember(renaming.first) { mutableStateOf(renaming.second) }
        AlertDialog(
            onDismissRequest = { renamingSpeaker = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("重命名说话人") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("例如：教授 / 同学A") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            app.live.renameSpeaker(renaming.first, trimmed)
                            renamingSpeaker = null
                        }
                    }),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.trim().isNotEmpty(),
                    onClick = {
                        app.live.renameSpeaker(renaming.first, newName.trim())
                        renamingSpeaker = null
                    },
                ) { Text("确定", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { renamingSpeaker = null }) { Text("取消") } },
        )
    }

    val editing = editingSegment
    if (editing != null) {
        var editedSrc by remember(editing.id) { mutableStateOf(editing.src) }
        AlertDialog(
            onDismissRequest = { editingSegment = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("修正原文并重译") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "修改识别错字后会自动保存并重新翻译本句。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = editedSrc,
                        onValueChange = { editedSrc = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editedSrc.trim().isNotEmpty(),
                    onClick = {
                        app.live.editAndRetranslateSegment(editing.id, editedSrc.trim())
                        editingSegment = null
                        Toast.makeText(context, "已保存并开始重译", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("保存并重译", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { editingSegment = null }) { Text("取消") } },
        )
    }

    val termSeg = addingTermSeg
    if (termSeg != null) {
        var term by remember(termSeg.id) { mutableStateOf("") }
        var translation by remember(termSeg.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingTermSeg = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("添加到当前课程术语表") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "添加后立即在接下来的实时翻译中生效。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = term,
                        onValueChange = { term = it },
                        singleLine = true,
                        label = { Text("原文术语") },
                        placeholder = { Text("例如：Gradient Descent") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = translation,
                        onValueChange = { translation = it },
                        singleLine = true,
                        label = { Text("指定译名 / 说明") },
                        placeholder = { Text("例如：梯度下降") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = term.trim().isNotEmpty() && translation.trim().isNotEmpty(),
                    onClick = {
                        val t = term.trim()
                        val tr = translation.trim()
                        addingTermSeg = null
                        app.live.addLiveGlossaryTerm(t, tr) { ok ->
                            Toast.makeText(
                                context,
                                if (ok) "已加入术语表「$t => $tr」，下句生效" else "添加术语失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) { Text("添加", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { addingTermSeg = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SourceHeroCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppColors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) c.accentSoft else MaterialTheme.colorScheme.surface, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accentBorder else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(if (selected) c.accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) c.accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) c.accent else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TranscriptRow(
    seg: LiveSegment,
    fontSizeSp: Int,
    showSource: Boolean,
    canAddGlossary: Boolean,
    onBookmark: (() -> Unit)?,
    onCopy: () -> Unit,
    onRetranslate: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onAddTerm: (() -> Unit)?,
    onRenameSpeaker: (() -> Unit)?,
) {
    val c = AppColors
    var menuOpen by remember { mutableStateOf(false) }
    val srcFontSp = (fontSizeSp - 6).coerceIn(12, 20)
    val shape = RoundedCornerShape(14.dp)
    val cardBorder = when {
        seg.bookmarked -> c.accentBorder
        seg.lowConfidence -> c.warn.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, cardBorder, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 卡片顶部元数据与操作行
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                formatSeconds(seg.t0),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (seg.speaker != null) {
                val spColor = parseColorSafe(seg.speakerColor, c.accent)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(spColor.copy(alpha = 0.14f))
                        .then(if (onRenameSpeaker != null) Modifier.clickable(onClick = onRenameSpeaker) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Dot(spColor, 6)
                    Text(seg.speaker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (seg.srcLang.isNotBlank()) {
                Text(
                    Langs.tagLabel(seg.srcLang),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            if (seg.bookmarked) {
                Text(
                    "★ 已标记",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.accent,
                    modifier = Modifier
                        .background(c.accentSoft, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (seg.lowConfidence) {
                Text(
                    "≈ 存疑",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.warn,
                    modifier = Modifier
                        .background(c.warnSoft, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (onBookmark != null) {
                IconButton(onClick = onBookmark, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (seg.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "标记该句",
                        tint = if (seg.bookmarked) c.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制字幕",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp),
                )
            }
            if (onEdit != null || onRetranslate != null || (canAddGlossary && onAddTerm != null)) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onEdit != null) {
                            DropdownMenuItem(text = { Text("修正原文并重译") }, onClick = { menuOpen = false; onEdit() })
                        }
                        if (onRetranslate != null) {
                            DropdownMenuItem(text = { Text("重新翻译本句") }, onClick = { menuOpen = false; onRetranslate() })
                        }
                        if (canAddGlossary && onAddTerm != null) {
                            DropdownMenuItem(text = { Text("加入课程术语表") }, onClick = { menuOpen = false; onAddTerm() })
                        }
                    }
                }
            }
        }

        if (showSource && seg.src.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                seg.src,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = srcFontSp.sp, lineHeight = (srcFontSp + 7).sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = if (seg.corrected) TextDecoration.Underline else TextDecoration.None,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            seg.tgt.ifBlank {
                if (!showSource && seg.src.isNotBlank()) seg.src
                else if (seg.src.isNotBlank()) "译文中…"
                else ""
            },
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp + 10).sp,
                fontWeight = if (seg.draftTgt) FontWeight.Normal else FontWeight.Medium,
                fontStyle = if (seg.draftTgt) FontStyle.Italic else FontStyle.Normal,
            ),
            color = when {
                seg.tgt.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                seg.draftTgt -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun PartialRow(live: LiveState, draftEnabled: Boolean, fontSizeSp: Int, showSource: Boolean) {
    val c = AppColors
    val srcFontSp = (fontSizeSp - 6).coerceIn(12, 20)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.accentSoft.copy(alpha = 0.55f), shape)
            .border(1.dp, c.accentBorder.copy(alpha = 0.7f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseDot(c.accent, active = live.speaking, size = 7)
            Text(
                if (live.speaking) "正在说话…" else "实时识别中…",
                style = MaterialTheme.typography.labelSmall,
                color = c.accent,
            )
        }
        if (showSource && live.partialSrc.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                live.partialSrc,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = srcFontSp.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (live.partialTgt.isNotBlank()) {
            Text(
                live.partialTgt,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (fontSizeSp - 1).coerceAtLeast(15).sp,
                    fontStyle = FontStyle.Italic,
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        } else if (draftEnabled && live.partialSrc.isNotBlank() && live.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.accent)
                Text("边说边译…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (!showSource && live.partialSrc.isNotBlank()) {
            Text(
                live.partialSrc,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = (fontSizeSp - 1).coerceAtLeast(15).sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioLevelMeter(level: Float, speaking: Boolean, large: Boolean = false) {
    val c = AppColors
    val norm = (kotlin.math.sqrt((level / 0.12f).coerceIn(0f, 1f).toDouble())).toFloat()
    val thresholds = floatArrayOf(0.12f, 0.28f, 0.48f, 0.68f, 0.86f)
    val heights = if (large) intArrayOf(10, 16, 22, 28, 34) else intArrayOf(6, 10, 14, 18, 22)
    val barWidth = if (large) 5.dp else 3.5.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 4.dp else 2.5.dp),
        modifier = Modifier.height(if (large) 36.dp else 24.dp),
    ) {
        for (i in thresholds.indices) {
            val lit = norm >= thresholds[i]
            val color = when {
                !lit -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                i == 4 && level > 0.25f -> c.warn
                speaking -> c.accent
                else -> c.accent.copy(alpha = 0.65f)
            }
            Box(
                Modifier
                    .width(barWidth)
                    .height(heights[i].dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color)
            )
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
    snackbar: SnackbarHostState,
) {
    val c = AppColors
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            val compact = maxWidth < 880.dp
            val courseOptions = listOf("0" to "未分类") + courses.map { it.id.toString() to it.name }
            val courseValue = (if (live.running || live.starting) (live.courseId ?: 0L) else (liveCourseId ?: 0L)).toString()
            val missingCount = remember(live.segments, live.running) {
                if (live.running) 0 else live.segments.count { it.needsTranslate() }
            }

            @Composable
            fun ConfigStrip() {
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
                        minWidth = 84,
                    )
                    Dropdown(
                        value = settings.sourceLang,
                        options = Langs.sources,
                        onChange = { v -> app.settings.update { it.copy(sourceLang = v) } },
                        enabled = !live.starting && !live.stopping,
                        label = "识别",
                        minWidth = 72,
                    )
                    IconActionChip(
                        icon = Icons.Default.SwapHoriz,
                        contentDescription = "互换识别与翻译语言",
                        enabled = !live.starting && !live.stopping,
                        onClick = {
                            val (nextSrc, nextTgt) = swapLanguagePair(settings.sourceLang, settings.targetLang)
                            app.settings.update { it.copy(sourceLang = nextSrc, targetLang = nextTgt) }
                        },
                    )
                    Dropdown(
                        value = settings.targetLang,
                        options = Langs.targets,
                        onChange = { v -> app.settings.update { it.copy(targetLang = v) } },
                        enabled = !live.starting && !live.stopping,
                        label = "译为",
                        minWidth = 86,
                    )
                    Dropdown(
                        value = courseValue,
                        options = courseOptions,
                        onChange = { v ->
                            if (!live.running && !live.stopping) onCourseChange(v.toLongOrNull()?.takeIf { it > 0 })
                        },
                        enabled = !live.running && !live.starting && !live.stopping,
                        label = "课程",
                        minWidth = 104,
                    )
                }
            }

            @Composable
            fun TelemetryStrip() {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (live.pendingQueue > 1) {
                        Pill("队列 ${live.pendingQueue}", c.warn)
                    }
                    if ((!live.running || live.stopping) && live.status.isNotBlank() && live.status != "就绪") {
                        Text(
                            live.status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (live.lastAsrMs > 0 || live.lastLatencyMs > 0) {
                        val metrics = buildList {
                            if (live.lastAsrMs > 0) add("识别 ${live.lastAsrMs}ms")
                            if (live.lastLatencyMs > 0) add("首字 ${live.lastLatencyMs}ms")
                        }.joinToString(" · ")
                        Text(
                            metrics,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (missingCount > 0 && !live.stopping) {
                        MiniButton("补译 $missingCount 句", { app.live.retranslateMissing() }, accent = true)
                    }
                }
            }

            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = when {
                            live.stopping -> "收尾中…"
                            live.running -> "结束同传"
                            live.starting -> "取消启动"
                            else -> "开始同传"
                        },
                        icon = if (live.running || live.starting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        onClick = { if (!live.stopping) { if (live.running || live.starting) onStop() else onStart() } },
                        enabled = !live.stopping,
                        danger = live.running,
                    )
                    if (live.running && !live.stopping) {
                        MiniButton(
                            text = if (live.paused) "继续" else "暂停",
                            icon = if (live.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            onClick = onPause,
                        )
                        MiniButton("标记", onBookmark, icon = Icons.Default.BookmarkBorder)
                    }
                    if (live.running && !live.paused && !live.stopping && live.source != LiveSource.FILE) {
                        AudioLevelMeter(live.audioLevel, live.speaking)
                    }
                    Spacer(Modifier.width(4.dp))
                    ConfigStrip()
                    Spacer(Modifier.weight(1f))
                    TelemetryStrip()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = when {
                                live.stopping -> "收尾中…"
                                live.running -> "结束"
                                live.starting -> "取消"
                                else -> "开始同传"
                            },
                            icon = if (live.running || live.starting) Icons.Default.Stop else Icons.Default.PlayArrow,
                            onClick = { if (!live.stopping) { if (live.running || live.starting) onStop() else onStart() } },
                            enabled = !live.stopping,
                            danger = live.running,
                            modifier = Modifier.weight(1f),
                        )
                        if (live.running && !live.stopping) {
                            MiniButton(
                                text = if (live.paused) "继续" else "暂停",
                                icon = if (live.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                onClick = onPause,
                            )
                            MiniButton("标记", onBookmark, icon = Icons.Default.BookmarkBorder)
                        }
                        if (live.running && !live.paused && !live.stopping && live.source != LiveSource.FILE) {
                            AudioLevelMeter(live.audioLevel, live.speaking)
                        }
                    }
                    ConfigStrip()
                    if (live.pendingQueue > 1 || live.lastAsrMs > 0 || live.lastLatencyMs > 0 || missingCount > 0 || ((!live.running || live.stopping) && live.status.isNotBlank() && live.status != "就绪")) {
                        TelemetryStrip()
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
    onOpenSettings: () -> Unit = {},
) {
    val c = AppColors
    val context = LocalContext.current
    var subTab by rememberSaveable { mutableIntStateOf(0) } // 0 = 要点总结, 1 = 课堂问答
    var input by remember { mutableStateOf("") }

    fun submitQuestion(q: String = input) {
        val text = q.trim()
        if (live.sessionId != null && text.isNotBlank()) {
            app.live.ask(text)
            if (q == input) input = ""
            subTab = 1
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(c.accentSoft, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Assistant, null, tint = c.accent, modifier = Modifier.size(16.dp))
                }
                Text("课堂助教", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                if (live.assistantBusy) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.6.dp, color = c.accent)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, "收起助教", modifier = Modifier.size(18.dp))
                }
            }

            if (!settings.assistantEnabled) {
                Spacer(Modifier.height(16.dp))
                EmptyHint(
                    title = "课堂助教未开启",
                    subtitle = "开启后每 60 秒自动提炼本场要点，并可针对讲课内容随时提问。",
                    icon = Icons.Default.Assistant,
                    action = { MiniButton("立即开启助教", { app.live.updateAssistantEnabled(true) }, accent = true) },
                )
                return@Column
            }

            val analysisApiReady = settings.analysisMode == SettingsData.MODE_API &&
                (settings.analysisBaseUrl.ifBlank { settings.apiBaseUrl }).isNotBlank() &&
                (settings.analysisModel.ifBlank { settings.apiModel }).isNotBlank()
            if (settings.analysisMode == SettingsData.MODE_BUILTIN && !app.models.assistantReady(settings.assistantVariant)) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.warnSoft, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val problem = app.models.fileProblem(app.models.assistantKind(settings.assistantVariant))
                    Text(
                        if (problem == "未下载" || problem == null) "助教模型未下载，可去下载或改用 API。"
                        else "助教模型异常：$problem",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.warn,
                        modifier = Modifier.weight(1f),
                    )
                    MiniButton("配置", onOpenSettings)
                }
            } else if (settings.analysisMode == SettingsData.MODE_API && !analysisApiReady) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.warnSoft, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("助教 API 未配置", style = MaterialTheme.typography.labelSmall, color = c.warn, modifier = Modifier.weight(1f))
                    MiniButton("去配置", onOpenSettings)
                }
            }

            Spacer(Modifier.height(10.dp))
            // 分段子页签：要点总结 / 课堂问答（让当前面板获得 100% 纵向空间）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(3.dp),
            ) {
                val tab0Shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .clip(tab0Shape)
                        .background(if (subTab == 0) MaterialTheme.colorScheme.surface else Color.Transparent, tab0Shape)
                        .clickable { subTab = 0 }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "要点总结",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (subTab == 0) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (subTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(tab0Shape)
                        .background(if (subTab == 1) MaterialTheme.colorScheme.surface else Color.Transparent, tab0Shape)
                        .clickable { subTab = 1 }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (live.questions.isEmpty()) "课堂问答" else "课堂问答 (${live.questions.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (subTab == 1) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (subTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (subTab == 0) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本场实时要点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (live.summary.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        copyText(context, "本场要点", live.summary)
                                        Toast.makeText(context, "已复制要点", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(26.dp),
                                ) {
                                    Icon(Icons.Default.ContentCopy, "复制要点", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            MiniButton(
                                "总结近 5 分钟",
                                { app.live.recap(5) },
                                enabled = live.sessionId != null && live.segments.isNotEmpty() && !live.assistantBusy,
                                accent = true,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            SelectionContainer {
                                Text(
                                    live.summary.ifBlank {
                                        if (live.running) "开始同传后每 60 秒自动提炼要点，也可点击右上角手动生成近 5 分钟总结。"
                                        else "开始一次同传后，这里会持续整理本场课堂要点。"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (live.summary.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                } else {
                    if (live.questions.isEmpty()) {
                        EmptyHint(
                            title = "暂无课堂问答",
                            subtitle = "自动识别到讲师提问，或在下方输入你的疑问时，答案会出现在这里。",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(live.questions, key = { it.id }) { q ->
                                QuestionCard(q) {
                                    copyText(context, "助教回答", "${q.question}\n\n${q.answer}")
                                    Toast.makeText(context, "已复制问答", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // 快捷提问芯片
            if (live.sessionId != null && live.segments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("梳理核心概念", "刚才讲了什么结论？", "列出 3 个复习重点").forEach { prompt ->
                        Text(
                            prompt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
                                .clickable(enabled = !live.assistantBusy) { submitQuestion(prompt) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
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
                    shape = RoundedCornerShape(12.dp),
                    enabled = live.sessionId != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitQuestion() }),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    enabled = live.sessionId != null && input.isNotBlank(),
                    onClick = { submitQuestion() },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "发送",
                        tint = if (live.sessionId != null && input.isNotBlank()) c.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(q: LiveQuestion, onCopy: () -> Unit) {
    val c = AppColors
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (q.source.isNotBlank()) {
                Text(
                    q.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.accent,
                    modifier = Modifier
                        .background(c.accentSoft, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
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
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.accent)
            } else if (q.answer.isNotBlank()) {
                IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, "复制回答", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                }
            }
        }
        if (q.questionTgt.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(q.questionTgt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                q.answer.ifBlank { if (q.pending) "思考中…" else "" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
    val c = AppColors
    val progress by app.models.progress.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("欢迎使用本地同传") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "全部在本机运行，不上传录音。首次使用需要下载识别模型（~240MB）和翻译模型（~1.1GB），也可以在设置中改用局域网 API。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                SetupModelRow(ModelKind.SENSEVOICE, app, progress[ModelKind.SENSEVOICE.id], settings)
                Spacer(Modifier.height(8.dp))
                SetupModelRow(ModelKind.TRANSLATOR, app, progress[ModelKind.TRANSLATOR.id], settings)
                Spacer(Modifier.height(8.dp))
                Text(
                    "下载时请保持应用在前台；中断后支持断点续传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("前往模型管理", color = c.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后再说") } },
    )
}

@Composable
private fun SetupModelRow(kind: ModelKind, app: App, p: DownloadProgress?, settings: SettingsData) {
    val c = AppColors
    val ready = remember(p) { app.models.isReady(kind) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp))
            Text(kind.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p?.state == "downloading") {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp), color = c.accent)
                val progressText = if (p.total > 0) "${formatBytes(p.downloaded)} / ${formatBytes(p.total)}" else p.message
                Text(progressText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (p?.state == "error" && p.message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(p.message, style = MaterialTheme.typography.labelSmall, color = c.danger)
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            ready -> Text("已就绪", color = c.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            p?.state == "downloading" -> MiniButton("取消", { app.models.cancel(kind) })
            p?.state == "error" -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniButton("重试", { app.models.download(kind, settings.hfMirror) }, accent = true)
                MiniButton("删除重下", {
                    app.appScope.launch(Dispatchers.IO) {
                        app.models.delete(kind)
                        app.models.download(kind, settings.hfMirror)
                    }
                }, danger = true)
            }
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
