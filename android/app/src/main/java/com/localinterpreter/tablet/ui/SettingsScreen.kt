package com.localinterpreter.tablet.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.llm.ApiLlm
import com.localinterpreter.tablet.llm.DownloadProgress
import com.localinterpreter.tablet.llm.Langs
import com.localinterpreter.tablet.llm.ModelKind
import com.localinterpreter.tablet.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SettingsSection {
    const val AUDIO_ASR = 0
    const val TRANSLATE_AI = 1
    const val MODELS = 2
    const val APPEARANCE = 3
    const val STORAGE_DIAG = 4
}

private data class SettingsCategory(
    val id: Int,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val SettingsCategories = listOf(
    SettingsCategory(SettingsSection.AUDIO_ASR, "识别与抗噪", "降噪校准 · 分句 · 说话人", Icons.Default.Mic),
    SettingsCategory(SettingsSection.TRANSLATE_AI, "翻译与助教", "引擎 · GPU · 课堂助教", Icons.Default.Translate),
    SettingsCategory(SettingsSection.MODELS, "模型管理", "离线下载 · 镜像 · 自检", Icons.Default.CloudDownload),
    SettingsCategory(SettingsSection.APPEARANCE, "外观与字幕", "主题 · 字号 · 预览", Icons.Default.Subtitles),
    SettingsCategory(SettingsSection.STORAGE_DIAG, "存储与诊断", "空间 · 清理 · 诊断日志", Icons.Default.Storage),
)

@Composable
fun SettingsScreen(
    app: App,
    modifier: Modifier = Modifier,
    initialSection: Int = SettingsSection.AUDIO_ASR,
    onClose: (() -> Unit)? = null,
) {
    val c = AppColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val progress by app.models.progress.collectAsStateWithLifecycle()
    val translatorStatus by app.llm.translatorStatus.collectAsStateWithLifecycle()
    val assistantStatus by app.llm.assistantStatus.collectAsStateWithLifecycle()
    val live by app.live.state.collectAsStateWithLifecycle()

    var section by rememberSaveable { mutableIntStateOf(initialSection) }
    LaunchedEffect(initialSection) {
        section = initialSection
    }

    var storage by remember { mutableStateOf<com.localinterpreter.tablet.data.Repository.StorageInfo?>(null) }
    var storageVersion by remember { mutableIntStateOf(0) }
    var apiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var apiBusy by remember { mutableStateOf(false) }
    var apiMessage by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<ModelKind?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var selfTestBusy by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf("") }
    var evalBusy by remember { mutableStateOf(false) }
    var evalResult by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var pendingLogExport by remember { mutableStateOf<String?>(null) }
    var cloudSyncBusy by remember { mutableStateOf(false) }
    var cloudSyncStatus by remember { mutableStateOf("") }
    var calibrating by remember { mutableStateOf(false) }
    var calibProgress by remember { mutableFloatStateOf(0f) }
    var calibMessage by remember { mutableStateOf("") }
    var backendInfo by remember { mutableStateOf("") }
    var gpuAvailable by remember { mutableStateOf(false) }

    val modelsMissing = remember(progress, settings.llmMode, settings.modelVariant) {
        !app.models.asrReady() ||
            (settings.llmMode == SettingsData.MODE_BUILTIN && !app.models.translatorReady(settings.modelVariant))
    }

    fun runCalibration() {
        if (calibrating || live.running || live.starting) return
        scope.launch {
            calibrating = true
            calibProgress = 0f
            calibMessage = "请保持 2 秒安静，正在测量环境底噪…"
            try {
                val res = com.localinterpreter.tablet.audio.NoiseCalibrator.measure(context, 2000L) { p ->
                    calibProgress = p
                }
                val modeLabel = when (res.recommendedMode) {
                    SettingsData.NOISE_SENSITIVE -> "灵敏"
                    SettingsData.NOISE_STRICT -> "强力"
                    else -> "标准"
                }
                app.settings.update {
                    it.copy(
                        calibratedNoiseFloor = res.ambientRms,
                        noiseSuppression = res.recommendedMode,
                    )
                }
                calibMessage = "环境底噪 ${"%.4f".format(res.ambientRms)}（${res.dbfs} dBFS），已校准基线并自动设为「$modeLabel」档"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                calibMessage = "校准失败：${t.message?.take(80) ?: "未知错误"}"
            } finally {
                calibrating = false
            }
        }
    }

    val calibPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCalibration()
        } else {
            calibMessage = "需要麦克风权限才能测量环境底噪"
        }
    }

    val logExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingLogExport
        if (uri != null && text != null) {
            app.appScope.launch {
                val ok = writeTextToUri(context, uri, text)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (ok) "诊断日志已导出" else "日志导出失败，请换一个存储位置",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        pendingLogExport = null
    }

    LaunchedEffect(storageVersion) {
        storage = runCatching { withContext(Dispatchers.IO) { app.repo.storageInfo() } }.getOrNull()
    }

    LaunchedEffect(Unit) {
        val (summary, hasGpu) = withContext(Dispatchers.IO) {
            runCatching { app.llm.backendSummary() to app.llm.hasGpuBackend() }.getOrDefault("CPU" to false)
        }
        backendInfo = summary
        gpuAvailable = hasGpu
    }

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "2.4.0" }
    }

    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    val liveBusy = live.running || live.starting || live.stopping

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (onClose != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    Text("设置", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Text("v$versionName Android", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 760.dp

                @Composable
                fun SectionContent() {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LazyColumn(
                            Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 16.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (section) {
                                SettingsSection.AUDIO_ASR -> {
                                    item {
                                        SectionCard(
                                            title = "环境抗噪与测噪校准",
                                            subtitle = "过滤空调、键盘和轻微杂音，防止无人说话时误识别",
                                            icon = Icons.Default.GraphicEq,
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                ChoiceCard(
                                                    title = "灵敏",
                                                    subtitle = "安静房间 · 远场轻声",
                                                    selected = settings.noiseSuppression == SettingsData.NOISE_SENSITIVE,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = true,
                                                ) { app.settings.update { it.copy(noiseSuppression = SettingsData.NOISE_SENSITIVE) } }
                                                ChoiceCard(
                                                    title = "标准",
                                                    subtitle = "默认 · 平衡抗噪",
                                                    selected = settings.noiseSuppression == SettingsData.NOISE_STANDARD,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = true,
                                                ) { app.settings.update { it.copy(noiseSuppression = SettingsData.NOISE_STANDARD) } }
                                                ChoiceCard(
                                                    title = "强力",
                                                    subtitle = "嘈杂教室 · 强抑杂音",
                                                    selected = settings.noiseSuppression == SettingsData.NOISE_STRICT,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = true,
                                                ) { app.settings.update { it.copy(noiseSuppression = SettingsData.NOISE_STRICT) } }
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                "同传进行中也可随时切换档位；若轻微环境杂音导致误识别，建议选「强力」或执行下方 2 秒环境测噪校准。",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            ) {
                                                MiniButton(
                                                    text = if (calibrating) "测噪中…" else "2 秒环境测噪校准",
                                                    icon = Icons.Default.GraphicEq,
                                                    onClick = {
                                                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                            context,
                                                            android.Manifest.permission.RECORD_AUDIO,
                                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                        if (granted) runCalibration()
                                                        else calibPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                    },
                                                    accent = true,
                                                    enabled = !calibrating && !liveBusy && !selfTestBusy,
                                                )
                                                if (settings.calibratedNoiseFloor > 0f) {
                                                    MiniButton(
                                                        text = "重置校准",
                                                        onClick = {
                                                            app.settings.update { it.copy(calibratedNoiseFloor = 0f) }
                                                            calibMessage = "已恢复默认自适应底噪基线"
                                                        },
                                                        enabled = !calibrating,
                                                    )
                                                }
                                                if (settings.calibratedNoiseFloor > 0f && !calibrating) {
                                                    val db = (20.0 * kotlin.math.log10(settings.calibratedNoiseFloor.toDouble().coerceAtLeast(1e-6))).toInt()
                                                    Pill("基线 ${"%.4f".format(settings.calibratedNoiseFloor)} ($db dBFS)", c.accent)
                                                }
                                            }
                                            if (calibrating) {
                                                Spacer(Modifier.height(8.dp))
                                                LinearProgressIndicator(
                                                    progress = { calibProgress },
                                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
                                                    color = c.accent,
                                                )
                                            }
                                            if (calibMessage.isNotBlank()) {
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    calibMessage,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (calibMessage.startsWith("校准失败")) c.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                                        .padding(10.dp),
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        SectionCard(
                                            title = "语音识别与分句",
                                            subtitle = "SenseVoice 本地离线识别与 Silero VAD 断句参数",
                                            icon = Icons.Default.Mic,
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("默认识别语言", style = MaterialTheme.typography.bodyMedium)
                                                    Text("同传底栏也可随时切换语言对", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Dropdown(
                                                    value = settings.sourceLang,
                                                    options = Langs.sources,
                                                    onChange = { v -> app.settings.update { it.copy(sourceLang = v) } },
                                                    minWidth = 112,
                                                    enabled = !live.starting && !live.stopping,
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            SliderRow(
                                                title = "停顿断句时间",
                                                valueText = "${settings.pauseMs}ms",
                                                value = settings.pauseMs.toFloat(),
                                                range = 250f..900f,
                                                steps = 12,
                                                enabled = !liveBusy,
                                                onChange = { v -> app.settings.update { it.copy(pauseMs = v.toInt()) } },
                                            )
                                            Text(
                                                "300–400ms 响应更跟手；调得太低会把长句提前切碎。",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Spacer(Modifier.height(6.dp))
                                            SwitchRow(
                                                title = "说话人区分（CAM++ 声纹）",
                                                subtitle = "本地实时声纹聚类，自动标出「说话人 1 / 2」并支持点击重命名",
                                                checked = settings.diarization,
                                                onChange = { v -> app.settings.update { it.copy(diarization = v) } },
                                            )
                                            SwitchRow(
                                                title = "上下文同音字校正",
                                                subtitle = "用翻译模型修正识别同音错字（会增加延迟）；API 模式自动跳过",
                                                checked = settings.autoCorrect,
                                                onChange = { v -> app.settings.update { it.copy(autoCorrect = v) } },
                                            )
                                            var asrThreadsDraft by remember(settings.asrThreads) { mutableFloatStateOf(settings.asrThreads.toFloat()) }
                                            SliderRow(
                                                title = "语音识别线程数",
                                                valueText = "${asrThreadsDraft.toInt()} 线程",
                                                value = asrThreadsDraft,
                                                range = 1f..8f,
                                                steps = 6,
                                                enabled = !liveBusy,
                                                onChange = { v -> asrThreadsDraft = v },
                                                onFinish = {
                                                    val next = asrThreadsDraft.toInt()
                                                    if (next != settings.asrThreads) {
                                                        app.settings.update { it.copy(asrThreads = next) }
                                                        app.appScope.launch { runCatching { app.live.releaseAsrCache() } }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                SettingsSection.TRANSLATE_AI -> {
                                    item {
                                        SectionCard(
                                            title = "翻译引擎",
                                            subtitle = "支持完全离线 Hy-MT2 本地模型或局域网/云端 OpenAI 兼容接口",
                                            icon = Icons.Default.Translate,
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                ChoiceCard(
                                                    title = "内置离线模型",
                                                    subtitle = "完全离线 · Hy-MT2-1.8B",
                                                    selected = settings.llmMode == SettingsData.MODE_BUILTIN,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = !liveBusy && !evalBusy && !selfTestBusy,
                                                ) { app.settings.update { it.copy(llmMode = SettingsData.MODE_BUILTIN) }; app.llm.warmUpTranslator() }
                                                ChoiceCard(
                                                    title = "本地服务 / API",
                                                    subtitle = "任意 OpenAI 兼容接口",
                                                    selected = settings.llmMode == SettingsData.MODE_API,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = !liveBusy && !evalBusy && !selfTestBusy,
                                                ) { app.settings.update { it.copy(llmMode = SettingsData.MODE_API) } }
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            if (settings.llmMode == SettingsData.MODE_BUILTIN) {
                                                val variantReady = remember(progress, settings.modelVariant) {
                                                    app.models.translatorReady(settings.modelVariant)
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    ChoiceCard(
                                                        title = "Q4_K_M · 1.1GB",
                                                        subtitle = "推荐 · 质量与速度均衡",
                                                        selected = settings.modelVariant == "q4",
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !liveBusy && !evalBusy && !selfTestBusy,
                                                    ) {
                                                        if (settings.modelVariant != "q4") {
                                                            app.settings.update { it.copy(modelVariant = "q4") }
                                                            app.llm.unloadTranslator()
                                                        }
                                                    }
                                                    ChoiceCard(
                                                        title = "三元 QAT · 440MB",
                                                        subtitle = "官方 1.25bit · 提速省内存",
                                                        selected = settings.modelVariant == "q2",
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !liveBusy && !evalBusy && !selfTestBusy,
                                                    ) {
                                                        if (settings.modelVariant != "q2") {
                                                            app.settings.update { it.copy(modelVariant = "q2") }
                                                            app.llm.unloadTranslator()
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(10.dp))
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Dot(if (variantReady) c.accent else c.warn, 7)
                                                    Spacer(Modifier.width(8.dp))
                                                    val displayStatus = if (!variantReady) "未下载（可在「模型管理」下载）" else translatorStatus
                                                    Text(
                                                        "状态：$displayStatus" + if (backendInfo.isNotBlank()) " · 后端：$backendInfo" else "",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                if (gpuAvailable) {
                                                    Spacer(Modifier.height(6.dp))
                                                    SwitchRow(
                                                        title = "Vulkan GPU 异构加速（实验性）",
                                                        subtitle = "将模型层卸载到平板 GPU，驱动不兼容时自动无缝回退 CPU",
                                                        checked = settings.useGpu,
                                                        enabled = !liveBusy,
                                                        onChange = { v ->
                                                            app.settings.update { it.copy(useGpu = v) }
                                                            app.llm.reloadLocalModelsForThreadChange(warmUp = true)
                                                        },
                                                    )
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                var llmThreadsDraft by remember(settings.llmThreads) { mutableFloatStateOf(settings.llmThreads.toFloat()) }
                                                SliderRow(
                                                    title = "本地推理线程数",
                                                    valueText = "${llmThreadsDraft.toInt()} 线程",
                                                    value = llmThreadsDraft,
                                                    range = 1f..8f,
                                                    steps = 6,
                                                    enabled = !liveBusy,
                                                    onChange = { v -> llmThreadsDraft = v },
                                                    onFinish = {
                                                        val next = llmThreadsDraft.toInt()
                                                        if (next != settings.llmThreads) {
                                                            app.settings.update { it.copy(llmThreads = next) }
                                                            app.llm.reloadLocalModelsForThreadChange(warmUp = true)
                                                        }
                                                    },
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    MiniButton(
                                                        text = if (evalBusy) "评测中…" else "翻译测速",
                                                        icon = Icons.Default.PlayArrow,
                                                        onClick = {
                                                            if (!evalBusy) {
                                                                scope.launch {
                                                                    evalBusy = true
                                                                    try {
                                                                        val start = System.currentTimeMillis()
                                                                        var first = 0L
                                                                        val text = app.llm.translate(
                                                                            "今天下午三点我们在大会议室开项目评审会，请把上周的原型带过来。",
                                                                            "zh", "en", maxTokens = 96, temperature = 0.0f,
                                                                        ) { if (first == 0L) first = System.currentTimeMillis() }
                                                                        val total = System.currentTimeMillis() - start
                                                                        evalResult = "首字 ${if (first > 0) first - start else total}ms · 整句 ${total}ms\n$text"
                                                                    } catch (e: Exception) {
                                                                        evalResult = "评测失败：${e.message?.take(120)}"
                                                                    } finally {
                                                                        evalBusy = false
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        enabled = !evalBusy && !liveBusy && !selfTestBusy,
                                                    )
                                                    Text("用固定例句测试当前首字与整句耗时", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (evalResult.isNotBlank()) {
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        evalResult,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                                            .padding(12.dp),
                                                    )
                                                }
                                            } else {
                                                ApiFields(
                                                    baseUrl = settings.apiBaseUrl,
                                                    model = settings.apiModel,
                                                    key = settings.apiKey,
                                                    models = apiModels,
                                                    busy = apiBusy,
                                                    actionsEnabled = !liveBusy,
                                                    message = apiMessage,
                                                    onBase = { v -> app.settings.update { it.copy(apiBaseUrl = v) } },
                                                    onModel = { v -> app.settings.update { it.copy(apiModel = v) } },
                                                    onKey = { v -> app.settings.update { it.copy(apiKey = v) } },
                                                    onDetect = {
                                                        scope.launch {
                                                            apiBusy = true
                                                            apiMessage = "检测中…"
                                                            try {
                                                                val list = withContext(Dispatchers.IO) {
                                                                    ApiLlm.listModels(app.settings.value.apiBaseUrl, app.settings.value.apiKey)
                                                                }
                                                                apiModels = list
                                                                if (list.isNotEmpty() && settings.apiModel.isBlank()) {
                                                                    app.settings.update { it.copy(apiModel = list.first()) }
                                                                }
                                                                apiMessage = if (list.isEmpty()) "没有发现模型，可手动填写" else "发现 ${list.size} 个模型"
                                                            } catch (e: Exception) {
                                                                apiMessage = "连接失败：${e.message?.take(80) ?: ""}"
                                                            } finally {
                                                                apiBusy = false
                                                            }
                                                        }
                                                    },
                                                    onTest = {
                                                        scope.launch {
                                                            apiBusy = true
                                                            apiMessage = "测试中…"
                                                            try {
                                                                val start = System.currentTimeMillis()
                                                                var first = 0L
                                                                val text = app.llm.translate(
                                                                    "今天下午三点我们在大会议室开项目评审会，请把上周的原型带过来。",
                                                                    "zh", "en", maxTokens = 256, temperature = 0.1f,
                                                                ) { if (first == 0L) first = System.currentTimeMillis() }
                                                                val total = System.currentTimeMillis() - start
                                                                apiMessage = "成功，首字 ${if (first > 0) first - start else total}ms，整句 ${total}ms：${text.take(60)}"
                                                            } catch (e: Exception) {
                                                                apiMessage = "失败：${e.message?.take(240) ?: ""}"
                                                            } finally {
                                                                apiBusy = false
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Spacer(Modifier.height(8.dp))
                                            SliderRow(
                                                title = "翻译上下文参考",
                                                valueText = if (settings.translationContext == 0) "关闭（最快）" else "参考前 ${settings.translationContext} 句",
                                                value = settings.translationContext.toFloat(),
                                                range = 0f..3f,
                                                steps = 2,
                                                onChange = { v -> app.settings.update { it.copy(translationContext = v.toInt().coerceIn(0, 3)) } },
                                            )
                                            Text(
                                                "把最近几句原文作为语境参考，减少代词翻错；默认 1 句兼顾连贯与速度。",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    item {
                                        SectionCard(
                                            title = "课堂助教",
                                            subtitle = "实时提炼课堂要点、自动捕捉提问并支持课后预习与出题",
                                            icon = Icons.Default.Assistant,
                                        ) {
                                            SwitchRow(
                                                title = "启用课堂助教",
                                                subtitle = "每 60 秒生成要点，并在右侧栏提供课堂问答",
                                                checked = settings.assistantEnabled,
                                                onChange = { v -> app.live.updateAssistantEnabled(v) },
                                            )
                                            SwitchRow(
                                                title = "自动识别课堂提问并作答",
                                                subtitle = "关闭后仍可在助教侧栏手动输入提问",
                                                checked = settings.autoQuestion,
                                                enabled = settings.assistantEnabled,
                                                onChange = { v -> app.settings.update { it.copy(autoQuestion = v) } },
                                            )
                                            SwitchRow(
                                                title = "下课后自动补充课程术语表",
                                                subtitle = "结束后自动从本课字幕中提炼新术语并加入所属课程",
                                                checked = settings.autoTerms,
                                                enabled = settings.assistantEnabled,
                                                onChange = { v -> app.settings.update { it.copy(autoTerms = v) } },
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                ChoiceCard(
                                                    title = "内置模型",
                                                    subtitle = "Qwen3-4B / 轻量 1.5B · 离线",
                                                    selected = settings.analysisMode == SettingsData.MODE_BUILTIN,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = !liveBusy,
                                                ) { app.settings.update { it.copy(analysisMode = SettingsData.MODE_BUILTIN) } }
                                                ChoiceCard(
                                                    title = "API 接口",
                                                    subtitle = "复用翻译 API 或单独配置",
                                                    selected = settings.analysisMode == SettingsData.MODE_API,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = !liveBusy,
                                                ) { app.settings.update { it.copy(analysisMode = SettingsData.MODE_API) } }
                                            }
                                            if (settings.analysisMode == SettingsData.MODE_BUILTIN) {
                                                Spacer(Modifier.height(8.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    ChoiceCard(
                                                        title = "4B · 2.5GB",
                                                        subtitle = "质量优先，建议 8GB+ 内存",
                                                        selected = settings.assistantVariant == "4b",
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !liveBusy,
                                                    ) {
                                                        if (settings.assistantVariant != "4b") {
                                                            app.settings.update { it.copy(assistantVariant = "4b") }
                                                            app.llm.unloadAssistant()
                                                        }
                                                    }
                                                    ChoiceCard(
                                                        title = "1.5B · 1.1GB",
                                                        subtitle = "更省内存，响应更快",
                                                        selected = settings.assistantVariant == "1.5b",
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !liveBusy,
                                                    ) {
                                                        if (settings.assistantVariant != "1.5b") {
                                                            app.settings.update { it.copy(assistantVariant = "1.5b") }
                                                            app.llm.unloadAssistant()
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                "状态：$assistantStatus · " + when {
                                                    settings.analysisMode == SettingsData.MODE_API -> "使用 API"
                                                    app.models.assistantReady(settings.assistantVariant) -> "模型已就绪"
                                                    else -> "模型未下载（可在「模型管理」下载）"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (settings.analysisMode == SettingsData.MODE_API) {
                                                var showAnalysisKey by remember { mutableStateOf(false) }
                                                Spacer(Modifier.height(10.dp))
                                                OutlinedTextField(
                                                    value = settings.analysisBaseUrl,
                                                    onValueChange = { v -> app.settings.update { it.copy(analysisBaseUrl = v) } },
                                                    label = { Text("分析接口地址（留空复用翻译 API）") },
                                                    singleLine = true,
                                                    enabled = !liveBusy,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedTextField(
                                                        value = settings.analysisModel,
                                                        onValueChange = { v -> app.settings.update { it.copy(analysisModel = v) } },
                                                        label = { Text("分析模型") },
                                                        singleLine = true,
                                                        enabled = !liveBusy,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    OutlinedTextField(
                                                        value = settings.analysisKey,
                                                        onValueChange = { v -> app.settings.update { it.copy(analysisKey = v) } },
                                                        label = { Text("Key（可选）") },
                                                        singleLine = true,
                                                        enabled = !liveBusy,
                                                        visualTransformation = if (showAnalysisKey) VisualTransformation.None else PasswordVisualTransformation(),
                                                        trailingIcon = {
                                                            IconButton(onClick = { showAnalysisKey = !showAnalysisKey }) {
                                                                Icon(
                                                                    if (showAnalysisKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                                    contentDescription = if (showAnalysisKey) "隐藏 Key" else "显示 Key",
                                                                )
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                SettingsSection.MODELS -> {
                                    item {
                                        val modelActionsEnabled = !liveBusy && !selfTestBusy && !evalBusy
                                        SectionCard(
                                            title = "核心同传模型",
                                            subtitle = "识别模型与翻译模型下载后即可完全断网离线使用",
                                            icon = Icons.Default.CloudDownload,
                                        ) {
                                            ModelRow(ModelKind.SENSEVOICE, app, progress[ModelKind.SENSEVOICE.id], settings, modelActionsEnabled, onDelete = { confirmDelete = it })
                                            Spacer(Modifier.height(8.dp))
                                            ModelRow(ModelKind.TRANSLATOR, app, progress[ModelKind.TRANSLATOR.id], settings, modelActionsEnabled, onDelete = { confirmDelete = it })
                                            Spacer(Modifier.height(8.dp))
                                            ModelRow(ModelKind.TRANSLATOR_LITE, app, progress[ModelKind.TRANSLATOR_LITE.id], settings, modelActionsEnabled, onDelete = { confirmDelete = it })
                                        }
                                    }
                                    item {
                                        val modelActionsEnabled = !liveBusy && !selfTestBusy && !evalBusy
                                        SectionCard(
                                            title = "可选助教模型",
                                            subtitle = "用于实时课堂总结、自动问答、课前预习与复习出题",
                                            icon = Icons.Default.Assistant,
                                        ) {
                                            ModelRow(ModelKind.ASSISTANT, app, progress[ModelKind.ASSISTANT.id], settings, modelActionsEnabled, onDelete = { confirmDelete = it })
                                            Spacer(Modifier.height(8.dp))
                                            ModelRow(ModelKind.ASSISTANT_LITE, app, progress[ModelKind.ASSISTANT_LITE.id], settings, modelActionsEnabled, onDelete = { confirmDelete = it })
                                        }
                                    }
                                    item {
                                        SectionCard(
                                            title = "下载源与链路自检",
                                            icon = Icons.Default.Tune,
                                        ) {
                                            SwitchRow(
                                                title = "优先使用国内镜像下载",
                                                subtitle = "hf-mirror.com，失败时自动回退 huggingface.co",
                                                checked = settings.hfMirror,
                                                onChange = { v -> app.settings.update { it.copy(hfMirror = v) } },
                                            )
                                            Text(
                                                "存储目录：${app.models.root.absolutePath}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                MiniButton(
                                                    text = if (selfTestBusy) "自检中…" else "识别 + 翻译全链路自检",
                                                    icon = Icons.Default.PlayArrow,
                                                    onClick = {
                                                        if (!selfTestBusy) {
                                                            scope.launch {
                                                                selfTestBusy = true
                                                                selfTestResult = ""
                                                                selfTestResult = runSelfTest(context, app)
                                                                selfTestBusy = false
                                                            }
                                                        }
                                                    },
                                                    accent = true,
                                                    enabled = !selfTestBusy && !liveBusy && !evalBusy,
                                                )
                                                Text(
                                                    "内置 16 秒测试音频，不经过麦克风",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (selfTestResult.isNotBlank()) {
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    selfTestResult,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                                        .padding(12.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                SettingsSection.APPEARANCE -> {
                                    item {
                                        SectionCard(
                                            title = "界面主题",
                                            subtitle = "深色模式护眼省电，浅色模式适合明亮教室",
                                            icon = Icons.Default.Palette,
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                ChoiceCard(
                                                    title = "深色",
                                                    subtitle = "暗夜影院 · 护眼",
                                                    selected = settings.theme == "dark",
                                                    modifier = Modifier.weight(1f),
                                                ) { app.settings.update { it.copy(theme = "dark") } }
                                                ChoiceCard(
                                                    title = "浅色",
                                                    subtitle = "明亮教室 · 高对比",
                                                    selected = settings.theme == "light",
                                                    modifier = Modifier.weight(1f),
                                                ) { app.settings.update { it.copy(theme = "light") } }
                                                ChoiceCard(
                                                    title = "跟随系统",
                                                    subtitle = "自动切换深浅",
                                                    selected = settings.theme == "system",
                                                    modifier = Modifier.weight(1f),
                                                ) { app.settings.update { it.copy(theme = "system") } }
                                            }
                                        }
                                    }
                                    item {
                                        SectionCard(
                                            title = "字幕效果与实时预览",
                                            subtitle = "支持左右双栏文字窗分离（条条对应）与自由拖拽边角调节尺寸",
                                            icon = Icons.Default.Subtitles,
                                        ) {
                                            val previewRows = listOf(
                                                Pair("#1", Pair("Today we'll look at gradient descent.", "今天我们来看梯度下降的原理。")),
                                                Pair("#2", Pair("Each step moves opposite to the gradient.", "每一步都沿着梯度的反方向更新参数。")),
                                            ).take(settings.subtitleLines.coerceIn(1, 2))
                                            val previewSplit = settings.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT && settings.showSource
                                            val leftW = settings.overlaySplitRatio.coerceIn(0.30f, 0.70f)
                                            // 实时悬浮字幕预览卡片
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(Color(0xFF0A0D12).copy(alpha = settings.overlayOpacity.coerceIn(0.3f, 1f)))
                                                    .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Dot(Accent, 7)
                                                    Text(
                                                        if (previewSplit) "浮字幕预览 · 左右双栏条条对应" else if (settings.showSource) "浮字幕预览 · 上下逐条卡片" else "浮字幕预览 · 仅译文",
                                                        color = Color(0xFF8C97A8),
                                                        fontSize = 11.sp,
                                                    )
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                if (previewSplit) {
                                                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text("原文", color = Color(0xFF8FA0B6), fontSize = 10.sp, modifier = Modifier.weight(leftW).padding(start = 6.dp))
                                                        Text("译文", color = Color(0xFF5EEAD4), fontSize = 10.sp, modifier = Modifier.weight(1f - leftW).padding(start = 6.dp))
                                                    }
                                                }
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    previewRows.forEachIndexed { idx, (badge, pair) ->
                                                        val active = idx == previewRows.lastIndex
                                                        if (previewSplit) {
                                                            Row(
                                                                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            ) {
                                                                Column(
                                                                    Modifier
                                                                        .weight(leftW)
                                                                        .fillMaxHeight()
                                                                        .clip(RoundedCornerShape(10.dp))
                                                                        .background(if (active) Color(0x20FFFFFF) else Color(0x13FFFFFF))
                                                                        .border(1.dp, if (active) Color(0x447FA0C4) else Color(0x1EFFFFFF), RoundedCornerShape(10.dp))
                                                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                                                ) {
                                                                    Text(badge, color = Color(0xFF7E90A8), fontSize = 10.sp)
                                                                    Text(
                                                                        pair.first,
                                                                        color = if (active) Color(0xFFD8E1EE) else Color(0xFFA5B3C6),
                                                                        fontSize = (settings.subtitleSize * 0.40f).coerceIn(11f, 18f).sp,
                                                                        lineHeight = (settings.subtitleSize * 0.52f).coerceIn(15f, 24f).sp,
                                                                    )
                                                                }
                                                                Column(
                                                                    Modifier
                                                                        .weight(1f - leftW)
                                                                        .fillMaxHeight()
                                                                        .clip(RoundedCornerShape(10.dp))
                                                                        .background(if (active) Color(0x263FE0B0) else Color(0x18FFFFFF))
                                                                        .border(1.dp, if (active) Color(0x703FE0B0) else Color(0x24FFFFFF), RoundedCornerShape(10.dp))
                                                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                                                ) {
                                                                    Text("$badge 译文", color = Color(0xFF5EEAD4), fontSize = 10.sp)
                                                                    Text(
                                                                        pair.second,
                                                                        color = if (active) Color.White else Color(0xFFE3EAF4),
                                                                        fontSize = (settings.subtitleSize * 0.56f).coerceIn(14f, 26f).sp,
                                                                        lineHeight = (settings.subtitleSize * 0.74f).coerceIn(19f, 34f).sp,
                                                                        fontWeight = FontWeight.Medium,
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            Column(
                                                                Modifier
                                                                    .fillMaxWidth()
                                                                    .clip(RoundedCornerShape(10.dp))
                                                                    .background(if (active) Color(0x223FE0B0) else Color(0x15FFFFFF))
                                                                    .border(1.dp, if (active) Color(0x663FE0B0) else Color(0x1EFFFFFF), RoundedCornerShape(10.dp))
                                                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                                            ) {
                                                                if (settings.showSource) {
                                                                    Text(
                                                                        "$badge  ${pair.first}",
                                                                        color = Color(0xFFA5B3C6),
                                                                        fontSize = (settings.subtitleSize * 0.40f).coerceIn(11f, 18f).sp,
                                                                    )
                                                                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color(0x1EFFFFFF))
                                                                }
                                                                Text(
                                                                    pair.second,
                                                                    color = if (active) Color.White else Color(0xFFE3EAF4),
                                                                    fontSize = (settings.subtitleSize * 0.58f).coerceIn(14f, 28f).sp,
                                                                    lineHeight = (settings.subtitleSize * 0.78f).coerceIn(20f, 36f).sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Text("浮字幕文字窗排版", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.height(6.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                ChoiceCard(
                                                    title = "左右双栏对照",
                                                    subtitle = "左原文 · 右译文 · 条条对齐",
                                                    selected = settings.overlayLayout == SettingsData.OVERLAY_LAYOUT_SPLIT,
                                                    modifier = Modifier.weight(1f),
                                                ) { app.settings.update { it.copy(overlayLayout = SettingsData.OVERLAY_LAYOUT_SPLIT) } }
                                                ChoiceCard(
                                                    title = "上下逐条卡片",
                                                    subtitle = "每句独立成卡 · 适合窄窗",
                                                    selected = settings.overlayLayout == SettingsData.OVERLAY_LAYOUT_STACK,
                                                    modifier = Modifier.weight(1f),
                                                ) { app.settings.update { it.copy(overlayLayout = SettingsData.OVERLAY_LAYOUT_STACK) } }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                val geoInfo = if (settings.overlayWidthDp > 0 || settings.overlayHeightDp > 0 || settings.overlayXDp >= 0) {
                                                    val wText = if (settings.overlayWidthDp > 0) "${settings.overlayWidthDp}dp" else "默认宽"
                                                    val hText = if (settings.overlayHeightDp > 0) "${settings.overlayHeightDp}dp" else "自动高"
                                                    "已记住浮窗：$wText × $hText（可拖底角自由缩放，双击底角恢复自动高）"
                                                } else {
                                                    "浮窗支持拖动顶栏移位、拖拽左右底角自由调节宽高、拖动中间 ⋮ 调双栏比例"
                                                }
                                                Text(
                                                    geoInfo,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                MiniButton(
                                                    text = "重置位置与尺寸",
                                                    onClick = {
                                                        app.settings.update {
                                                            it.copy(
                                                                overlayXDp = -1,
                                                                overlayYDp = -1,
                                                                overlayWidthDp = 0,
                                                                overlayHeightDp = 0,
                                                                overlaySplitRatio = 0.45f,
                                                            )
                                                        }
                                                        toast("已重置浮字幕位置、尺寸与双栏比例")
                                                    },
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            SwitchRow(
                                                title = "主记录页显示原文（双语模式）",
                                                subtitle = "关闭后主记录页只展示译文，阅读更专注",
                                                checked = settings.liveShowSource,
                                                onChange = { v -> app.settings.update { it.copy(liveShowSource = v) } },
                                            )
                                            SwitchRow(
                                                title = "全屏与浮字幕显示原文",
                                                subtitle = "开启后与译文条条对应显示；关闭后仅展示译文",
                                                checked = settings.showSource,
                                                onChange = { v -> app.settings.update { it.copy(showSource = v) } },
                                            )
                                            SwitchRow(
                                                title = "边说边译（实时草稿）",
                                                subtitle = "说话过程中先展示灰色草稿译文；API 模式自动跳过",
                                                checked = settings.draftTranslate,
                                                onChange = { v -> app.settings.update { it.copy(draftTranslate = v) } },
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            SliderRow(
                                                title = "主记录页译文字号",
                                                valueText = "${settings.liveFontSize}sp",
                                                value = settings.liveFontSize.toFloat(),
                                                range = 16f..30f,
                                                steps = 6,
                                                onChange = { v -> app.settings.update { it.copy(liveFontSize = v.toInt()) } },
                                            )
                                            SliderRow(
                                                title = "全屏 / 浮字幕字号",
                                                valueText = "${settings.subtitleSize}sp",
                                                value = settings.subtitleSize.toFloat(),
                                                range = 24f..64f,
                                                steps = 39,
                                                onChange = { v -> app.settings.update { it.copy(subtitleSize = v.toInt()) } },
                                            )
                                            SliderRow(
                                                title = "浮字幕背景不透明度",
                                                valueText = "${(settings.overlayOpacity * 100).toInt()}%",
                                                value = settings.overlayOpacity,
                                                range = 0.3f..1f,
                                                steps = 6,
                                                onChange = { v -> app.settings.update { it.copy(overlayOpacity = v) } },
                                            )
                                            SliderRow(
                                                title = "全屏 / 浮字幕显示条数",
                                                valueText = "${settings.subtitleLines} 条",
                                                value = settings.subtitleLines.toFloat(),
                                                range = 1f..4f,
                                                steps = 2,
                                                onChange = { v -> app.settings.update { it.copy(subtitleLines = v.toInt()) } },
                                            )
                                        }
                                    }
                                }

                                SettingsSection.STORAGE_DIAG -> {
                                    item {
                                        SectionCard(
                                            title = "本地存储与隐私",
                                            subtitle = "全部数据保存在应用私有目录，不联网、不上传",
                                            icon = Icons.Default.Storage,
                                            trailing = { MiniButton("刷新", { storageVersion++ }) },
                                        ) {
                                            val info = storage
                                            if (info != null) {
                                                val usedTotal = info.databaseBytes + info.audioBytes + info.materialBytes + info.modelBytes
                                                // 分段存储占比条
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(8.dp)
                                                        .clip(RoundedCornerShape(999.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                                ) {
                                                    if (usedTotal > 0L) {
                                                        val totalF = usedTotal.toFloat()
                                                        val modelW = if (info.modelBytes > 0L) (info.modelBytes / totalF).coerceAtLeast(0.02f) else 0f
                                                        val audioW = if (info.audioBytes > 0L) (info.audioBytes / totalF).coerceAtLeast(0.01f) else 0f
                                                        val matW = if (info.materialBytes > 0L) (info.materialBytes / totalF).coerceAtLeast(0.01f) else 0f
                                                        val dbW = if (info.databaseBytes > 0L) (info.databaseBytes / totalF).coerceAtLeast(0.01f) else 0f
                                                        if (modelW > 0f) Box(Modifier.weight(modelW).fillMaxHeight().background(c.accent))
                                                        if (audioW > 0f) Box(Modifier.weight(audioW).fillMaxHeight().background(c.secondary))
                                                        if (matW > 0f) Box(Modifier.weight(matW).fillMaxHeight().background(c.warn))
                                                        if (dbW > 0f) Box(Modifier.weight(dbW).fillMaxHeight().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)))
                                                    }
                                                }
                                                Spacer(Modifier.height(10.dp))
                                                InfoRow("离线模型", gb(info.modelBytes), c.accent)
                                                InfoRow("逐句录音", mb(info.audioBytes), c.secondary)
                                                InfoRow("课程资料", mb(info.materialBytes), c.warn)
                                                InfoRow("字幕数据库", mb(info.databaseBytes), MaterialTheme.colorScheme.onSurfaceVariant)
                                                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                                InfoRow("设备剩余可用空间", gb(info.freeBytes))
                                            } else {
                                                Text("正在统计存储占用…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            SwitchRow(
                                                title = "保存逐句录音",
                                                subtitle = "课后可按句点击回听，约 1MB/分钟",
                                                checked = settings.keepAudio,
                                                onChange = { v -> app.settings.update { it.copy(keepAudio = v) } },
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("历史课次清理策略", style = MaterialTheme.typography.bodyMedium)
                                                    Text("应用启动时自动清理到期课次，也可随时手动一键清理", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Dropdown(
                                                    value = settings.autoCleanupDays.toString(),
                                                    options = listOf("0" to "永久保留", "7" to "7 天", "30" to "30 天", "90" to "90 天"),
                                                    onChange = { v -> app.settings.update { it.copy(autoCleanupDays = v.toInt()) } },
                                                    minWidth = 98,
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            MiniButton("立即按策略清理", { confirmCleanup = true }, accent = true, enabled = settings.autoCleanupDays > 0)
                                        }
                                    }
                                    item {
                                        SectionCard(
                                            title = "Google Cloud 跨端同步",
                                            subtitle = "与 Mac 版及 iPhone 知识点 PWA 双向同步课次、知识点、术语表与收藏",
                                            icon = Icons.Default.CloudDownload,
                                        ) {
                                            OutlinedTextField(
                                                value = settings.cloudSyncUrl,
                                                onValueChange = { v -> app.settings.update { it.copy(cloudSyncUrl = v.trim()) } },
                                                label = { Text("Cloud Run 服务地址") },
                                                placeholder = { Text("https://local-interpreter-cloud-xxx.run.app") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = settings.cloudSyncToken,
                                                onValueChange = { v -> app.settings.update { it.copy(cloudSyncToken = v.trim()) } },
                                                label = { Text("Sync Auth Token（与 Mac / iPhone 一致）") },
                                                singleLine = true,
                                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            SwitchRow(
                                                title = "下课总结完成后自动同步",
                                                subtitle = "仅上传纯文本知识点、双语字幕与术语，原始录音留在本机",
                                                checked = settings.cloudAutoSync,
                                                onChange = { v -> app.settings.update { it.copy(cloudAutoSync = v) } },
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                MiniButton(
                                                    text = if (cloudSyncBusy) "同步中…" else "立即同步 Google Cloud",
                                                    onClick = {
                                                        cloudSyncBusy = true
                                                        cloudSyncStatus = "正在连接 Google Cloud…"
                                                        scope.launch {
                                                            val res = app.cloudSync.syncNow()
                                                            cloudSyncBusy = false
                                                            res.onSuccess { st ->
                                                                cloudSyncStatus = "同步成功：上传 ${st.pushedItems} 项，拉取 ${st.pulledItems} 项"
                                                                toast(cloudSyncStatus)
                                                            }.onFailure { err ->
                                                                cloudSyncStatus = "同步失败：${err.message ?: err.javaClass.simpleName}"
                                                                toast(cloudSyncStatus)
                                                            }
                                                        }
                                                    },
                                                    accent = true,
                                                    enabled = !cloudSyncBusy && settings.cloudSyncUrl.isNotBlank(),
                                                )
                                                if (cloudSyncStatus.isNotBlank()) {
                                                    Text(
                                                        cloudSyncStatus,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    item {
                                        SectionCard(
                                            title = "诊断日志与关于",
                                            subtitle = "v$versionName Android · 不包含录音内容或 API Key",
                                            icon = Icons.Default.BugReport,
                                        ) {
                                            SwitchRow(
                                                title = "记录每句耗时探针（Lat 日志）",
                                                subtitle = "用于排查高延迟或卡顿，正常使用保持关闭即可",
                                                checked = settings.latencyProbe,
                                                onChange = { v -> app.settings.update { it.copy(latencyProbe = v) } },
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                MiniButton("查看日志", {
                                                    scope.launch {
                                                        val text = withContext(Dispatchers.IO) {
                                                            AppLog.report(diagnosticHeader(app), maxLines = 1200)
                                                        }
                                                        logText = text
                                                        showLog = true
                                                    }
                                                }, accent = true)
                                                MiniButton("复制日志", {
                                                    scope.launch {
                                                        val text = withContext(Dispatchers.IO) {
                                                            AppLog.report(diagnosticHeader(app), maxLines = 900)
                                                        }
                                                        copyText(context, "本地同传诊断日志", text)
                                                        toast("完整诊断日志已复制")
                                                    }
                                                })
                                                MiniButton("导出日志", {
                                                    scope.launch {
                                                        val text = withContext(Dispatchers.IO) {
                                                            AppLog.report(diagnosticHeader(app), maxLines = 8000)
                                                        }
                                                        pendingLogExport = text
                                                        logExportLauncher.launch("本地同传诊断_${System.currentTimeMillis()}.txt")
                                                    }
                                                }, accent = true)
                                                MiniButton("清空日志", { AppLog.clear(); toast("已清空") }, danger = true)
                                            }
                                            Spacer(Modifier.height(14.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Spacer(Modifier.height(12.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.Info, null, tint = c.accent, modifier = Modifier.size(16.dp))
                                                Text("本地同传 · Android 平板版 v$versionName", style = MaterialTheme.typography.titleMedium)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "麦克风 / 系统声音 → Silero VAD 分句 → SenseVoice 识别 → 本地 Hy-MT2 翻译 → 实时字幕。全部在本机完成。",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "识别：sherpa-onnx · 翻译/助教：llama.cpp · 声纹：CAM++",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }

                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .width(236.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 12.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "设置中心",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                            SettingsCategories.forEach { cat ->
                                val active = section == cat.id
                                val showWarnDot = cat.id == SettingsSection.MODELS && modelsMissing
                                val shape = RoundedCornerShape(12.dp)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .background(if (active) c.accentSoft else Color.Transparent, shape)
                                        .border(1.dp, if (active) c.accentBorder else Color.Transparent, shape)
                                        .clickable { section = cat.id }
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        cat.icon,
                                        contentDescription = null,
                                        tint = if (active) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            cat.title,
                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                            color = if (active) c.accent else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                        )
                                        Text(
                                            cat.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (showWarnDot) {
                                        Dot(c.warn, 7)
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "v$versionName · 离线优先",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 10.dp),
                            )
                        }
                        VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            SectionContent()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SettingsCategories.forEach { cat ->
                                val active = section == cat.id
                                val shape = RoundedCornerShape(999.dp)
                                Row(
                                    Modifier
                                        .clip(shape)
                                        .background(if (active) c.accentSoft else MaterialTheme.colorScheme.surfaceVariant, shape)
                                        .border(1.dp, if (active) c.accentBorder else MaterialTheme.colorScheme.outlineVariant, shape)
                                        .clickable { section = cat.id }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        cat.icon,
                                        contentDescription = null,
                                        tint = if (active) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        cat.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (active) c.accent else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (cat.id == SettingsSection.MODELS && modelsMissing) {
                                        Dot(c.warn, 6)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            SectionContent()
                        }
                    }
                }
            }
        }
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("诊断日志") },
            text = {
                SelectionContainer {
                    Text(
                        logText.ifBlank { "暂无日志" },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("关闭", color = c.accent) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    if (confirmCleanup) {
        ConfirmDialog(
            title = "清理课次",
            message = "删除 ${settings.autoCleanupDays} 天前的课次及其录音、问答和笔记，无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                scope.launch {
                    try {
                        val n = withContext(Dispatchers.IO) { app.repo.cleanupSessions(settings.autoCleanupDays) }
                        storageVersion++
                        toast("已清理 $n 个课次")
                    } catch (t: Throwable) {
                        toast("清理失败：${t.message?.take(60)}")
                    }
                }
            },
            onDismiss = { confirmCleanup = false },
        )
    }
    confirmDelete?.let { kind ->
        ConfirmDialog(
            title = "删除模型",
            message = "删除「${kind.title}」？下次使用需要重新下载。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                val liveNow = app.live.state.value
                if (liveNow.running || liveNow.starting || liveNow.stopping) {
                    toast("请先结束同传，再删除模型")
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                when (kind) {
                                    ModelKind.TRANSLATOR, ModelKind.TRANSLATOR_LITE -> app.llm.unloadTranslator()
                                    ModelKind.ASSISTANT, ModelKind.ASSISTANT_LITE -> app.llm.unloadAssistant()
                                    ModelKind.SENSEVOICE -> app.live.releaseAsrCache()
                                }
                            }
                            app.models.delete(kind)
                        }
                        storageVersion++
                        toast("已删除")
                    }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = AppColors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) c.accentSoft else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape,
            )
            .border(1.dp, if (selected) c.accentBorder else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp)
            .alpha(if (enabled) 1f else 0.45f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) c.accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp).alpha(if (enabled) 1f else 0.5f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onFinish: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    val c = AppColors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = c.accent, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinish,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, dotColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dotColor != null) {
            Dot(dotColor, 7)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModelRow(
    kind: ModelKind,
    app: App,
    p: DownloadProgress?,
    settings: SettingsData,
    actionsEnabled: Boolean,
    onDelete: (ModelKind) -> Unit,
) {
    val c = AppColors
    val ready = app.models.isReady(kind)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), shape)
            .border(1.dp, if (ready) c.accentBorder.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp))
                if (ready) {
                    Text(
                        "已就绪",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accent,
                        modifier = Modifier
                            .background(c.accentSoft, RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(kind.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p?.state == "downloading") {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { p.fraction },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
                    color = c.accent,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${p.message} · ${mb(p.downloaded)} / ${mb(p.total)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (p?.state == "error") {
                Spacer(Modifier.height(4.dp))
                Text(p.message, style = MaterialTheme.typography.labelSmall, color = c.danger)
                Text(
                    "点右侧「删除并重下」可清掉残留文件重新下载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!ready) {
                val problem = app.models.fileProblem(kind)
                if (problem != null && problem != "未下载") {
                    Spacer(Modifier.height(3.dp))
                    Text(problem, style = MaterialTheme.typography.labelSmall, color = c.danger)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            ready -> MiniButton("删除", { onDelete(kind) }, danger = true, enabled = actionsEnabled)
            p?.state == "downloading" -> MiniButton("取消", { app.models.cancel(kind) })
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniButton(
                    if (p?.state == "error") "重试" else if (app.models.fileFor(kind).exists()) "重新下载" else "下载",
                    { app.models.download(kind, settings.hfMirror) },
                    accent = true,
                    enabled = actionsEnabled,
                )
                if (p?.state == "error") {
                    MiniButton("删除并重下", {
                        app.appScope.launch(Dispatchers.IO) {
                            runCatching {
                                when (kind) {
                                    ModelKind.TRANSLATOR, ModelKind.TRANSLATOR_LITE -> app.llm.unloadTranslator()
                                    ModelKind.ASSISTANT, ModelKind.ASSISTANT_LITE -> app.llm.unloadAssistant()
                                    ModelKind.SENSEVOICE -> app.live.releaseAsrCache()
                                }
                            }
                            app.models.delete(kind)
                            app.models.download(kind, settings.hfMirror)
                        }
                    }, danger = true, enabled = actionsEnabled)
                } else if (app.models.fileFor(kind).exists()) {
                    MiniButton("删除", { onDelete(kind) }, danger = true, enabled = actionsEnabled)
                }
            }
        }
    }
}

@Composable
private fun ApiFields(
    baseUrl: String,
    model: String,
    key: String,
    models: List<String>,
    busy: Boolean,
    actionsEnabled: Boolean,
    message: String,
    onBase: (String) -> Unit,
    onModel: (String) -> Unit,
    onKey: (String) -> Unit,
    onDetect: () -> Unit,
    onTest: () -> Unit,
) {
    val c = AppColors
    var showKey by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBase,
            label = { Text("接口地址") },
            placeholder = { Text("http://192.168.1.100:8000/v1（电脑局域网地址）") },
            singleLine = true,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = model,
                onValueChange = onModel,
                label = { Text("模型") },
                singleLine = true,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f),
            )
            if (models.isNotEmpty()) {
                Dropdown(
                    value = model,
                    options = models.map { it to it },
                    onChange = onModel,
                    label = "检测到",
                    minWidth = 120,
                    enabled = actionsEnabled,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = onKey,
            label = { Text("API Key（可选）") },
            singleLine = true,
            enabled = actionsEnabled,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) "隐藏 API Key" else "显示 API Key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniButton(if (busy) "请稍候…" else "自动检测", onDetect, accent = true, enabled = !busy && actionsEnabled)
            MiniButton("测试翻译", onTest, enabled = !busy && actionsEnabled)
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.6.dp, color = c.accent)
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun diagnosticHeader(app: App): String {
    val s = app.settings.value
    val st = app.live.state.value
    return buildString {
        appendLine("翻译：mode=${s.llmMode} variant=${s.modelVariant} useGpu=${s.useGpu} apiBase=${s.apiBaseUrl} apiModel=${s.apiModel}")
        appendLine("识别：source=${s.sourceLang} target=${s.targetLang} pause=${s.pauseMs} noise=${s.noiseSuppression} floor=${"%.4f".format(s.calibratedNoiseFloor)} asrThreads=${s.asrThreads} llmThreads=${s.llmThreads} draft=${s.draftTranslate} diarization=${s.diarization} correct=${s.autoCorrect}")
        appendLine("助教：enabled=${s.assistantEnabled} mode=${s.analysisMode} variant=${s.assistantVariant} apiBase=${s.analysisBaseUrl} model=${s.analysisModel}")
        appendLine("显示：subtitleLines=${s.subtitleLines} size=${s.subtitleSize} liveSize=${s.liveFontSize} showSource=${s.showSource} liveShowSource=${s.liveShowSource} keepAudio=${s.keepAudio}")
        appendLine("直播：running=${st.running} starting=${st.starting} stopping=${st.stopping} paused=${st.paused} source=${st.source} session=${st.sessionId}")
        appendLine("      segments=${st.segments.size} partial=${st.partialSrc.length}字 asrMs=${st.lastAsrMs} translateFirst=${st.lastLatencyMs} level=${"%.4f".format(st.audioLevel)} error=${st.error}")
        appendLine("状态：ASR=${app.live.asrStatus.value} / 翻译=${app.llm.translatorStatus.value} / 助教=${app.llm.assistantStatus.value}")
        appendLine("模型目录：${app.models.root.absolutePath}")
    }.trim()
}

private suspend fun runSelfTest(context: android.content.Context, app: App): String = withContext(Dispatchers.Default) {
    app.live.releaseAsrCache()
    val asr = com.localinterpreter.tablet.asr.AsrEngine()
    var vad: com.localinterpreter.tablet.asr.VadSegmenter? = null
    try {
        val t0 = System.currentTimeMillis()
        if (!asr.ensureLoaded("zh")) return@withContext "自检失败：${asr.status.value}"
        val loadMs = System.currentTimeMillis() - t0
        val samples = withContext(Dispatchers.IO) { readWav(context, "test_zh.wav") }
        if (samples.isEmpty()) return@withContext "自检失败：测试音频读取失败"
        val segmenter = com.localinterpreter.tablet.asr.VadSegmenter(400).also { vad = it }
        val window = segmenter.windowSize
        var i = 0
        val asrStart = System.currentTimeMillis()
        val texts = mutableListOf<Pair<String, String>>()
        while (i + window <= samples.size) {
            segmenter.accept(samples.copyOfRange(i, i + window))
            i += window
            while (true) {
                val seg = segmenter.nextSegment() ?: break
                if (texts.size < 2) {
                    val (text, lang) = asr.transcribe(seg.samples, "zh")
                    if (text.isNotBlank()) texts += text to lang
                }
            }
            if (texts.size >= 2) break
        }
        segmenter.flush()
        while (true) {
            val seg = segmenter.nextSegment() ?: break
            if (texts.size < 2) {
                val (text, lang) = asr.transcribe(seg.samples, "zh")
                if (text.isNotBlank()) texts += text to lang
            }
        }
        if (texts.isEmpty()) {
            val (text, lang) = asr.transcribe(samples, "zh")
            if (text.isNotBlank()) texts += text to lang
        }
        val asrMs = System.currentTimeMillis() - asrStart
        val first = texts.firstOrNull()?.first ?: ""
        var firstTok = 0L
        val trStart = System.currentTimeMillis()
        val temp = if (app.settings.value.llmMode == SettingsData.MODE_API) 0.1f else 0.0f
        val tr = if (first.isNotBlank()) {
            app.llm.translate(first, "zh", "en", maxTokens = 200, temperature = temp) {
                if (firstTok == 0L) firstTok = System.currentTimeMillis()
            }
        } else ""
        val trMs = System.currentTimeMillis() - trStart
        buildString {
            appendLine("模型加载：${loadMs}ms")
            appendLine("识别（${samples.size / 16000}s 音频，${texts.size} 句）：${asrMs}ms")
            appendLine("原文：${first.ifBlank { "（无）" }}")
            if (tr.isNotBlank()) {
                appendLine("译文：$tr")
                appendLine("翻译首字：${if (firstTok > 0) firstTok - trStart else trMs}ms · 整句：${trMs}ms")
            } else {
                append("翻译：未完成（模型未就绪或失败）")
            }
        }
    } catch (e: Exception) {
        "自检失败：${e.message?.take(120) ?: e.javaClass.simpleName}"
    } finally {
        runCatching { vad?.release() }
        asr.release()
    }
}

private fun readWav(context: android.content.Context, asset: String): FloatArray {
    return try {
        val bytes = context.assets.open(asset).readBytes()
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xff) or
                ((bytes[pos + 5].toInt() and 0xff) shl 8) or
                ((bytes[pos + 6].toInt() and 0xff) shl 16) or
                ((bytes[pos + 7].toInt() and 0xff) shl 24)
            if (id == "data") {
                dataOffset = pos + 8
                dataLen = minOf(size, bytes.size - dataOffset)
                break
            }
            pos += 8 + size + (size % 2)
        }
        if (dataOffset < 0) return FloatArray(0)
        val count = dataLen / 2
        FloatArray(count) { i ->
            val lo = bytes[dataOffset + i * 2].toInt() and 0xff
            val hi = bytes[dataOffset + i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    } catch (_: Exception) {
        FloatArray(0)
    }
}

private fun mb(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun gb(bytes: Long): String = "%.2f GB".format(bytes / 1_073_741_824.0)
