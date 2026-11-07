package com.localinterpreter.tablet.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import java.io.File

@Composable
fun SettingsScreen(app: App, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val progress by app.models.progress.collectAsStateWithLifecycle()
    val translatorStatus by app.llm.translatorStatus.collectAsStateWithLifecycle()
    val assistantStatus by app.llm.assistantStatus.collectAsStateWithLifecycle()
    val live by app.live.state.collectAsStateWithLifecycle()
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

    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "返回") }
                    Text("设置", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Text("v2.2.15 Android", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SectionCard(title = "翻译方式") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChoiceCard(
                                title = "内置模型",
                                subtitle = "完全离线 · Hy-MT2-1.8B",
                                selected = settings.llmMode == SettingsData.MODE_BUILTIN,
                                modifier = Modifier.weight(1f),
                                enabled = !live.running && !live.starting,
                            ) { app.settings.update { it.copy(llmMode = SettingsData.MODE_BUILTIN) }; app.llm.warmUpTranslator() }
                            ChoiceCard(
                                title = "本地服务 / API",
                                subtitle = "任意 OpenAI 兼容接口",
                                selected = settings.llmMode == SettingsData.MODE_API,
                                modifier = Modifier.weight(1f),
                                enabled = !live.running && !live.starting,
                            ) { app.settings.update { it.copy(llmMode = SettingsData.MODE_API) } }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (settings.llmMode == SettingsData.MODE_BUILTIN) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ChoiceCard(
                                    title = "Q4_K_M · 1.1GB",
                                    subtitle = "默认，质量优先",
                                    selected = settings.modelVariant == "q4",
                                    modifier = Modifier.weight(1f),
                                    enabled = !live.running && !live.starting,
                                ) { app.settings.update { it.copy(modelVariant = "q4") } }
                                ChoiceCard(
                                    title = "2bit · 600MB",
                                    subtitle = "更快、更省内存",
                                    selected = settings.modelVariant == "q2",
                                    modifier = Modifier.weight(1f),
                                    enabled = !live.running && !live.starting,
                                ) { app.settings.update { it.copy(modelVariant = "q2") } }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "状态：$translatorStatus",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MiniButton(if (evalBusy) "评测中…" else "翻译评测", {
                                    if (!evalBusy) {
                                        scope.launch {
                                            evalBusy = true
                                            try {
                                                val start = System.currentTimeMillis()
                                                var first = 0L
                                                val text = app.llm.translate(
                                                    "今天下午三点我们在大会议室开项目评审会，请把上周的原型带过来。",
                                                    "zh", "en", maxTokens = 96, temperature = 0.2f,
                                                ) { if (first == 0L) first = System.currentTimeMillis() }
                                                val total = System.currentTimeMillis() - start
                                                evalResult = "首字 ${if (first > 0) first - start else total}ms · 整句 ${total}ms\n${text}"
                                            } catch (e: Exception) {
                                                evalResult = "评测失败：${e.message?.take(120)}"
                                            }
                                            evalBusy = false
                                        }
                                    }
                                }, enabled = !evalBusy && !live.running && !live.starting)
                                Text("空闲时用固定例句测当前翻译链路。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (evalResult.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    evalResult,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                                        .padding(10.dp),
                                )
                            }
                        } else {
                            ApiFields(
                                baseUrl = settings.apiBaseUrl,
                                model = settings.apiModel,
                                key = settings.apiKey,
                                models = apiModels,
                                busy = apiBusy,
                                actionsEnabled = !live.running && !live.starting && !live.stopping,
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
                                        }
                                        apiBusy = false
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
                                                "zh", "en", maxTokens = 120, temperature = 0.2f,
                                            ) { if (first == 0L) first = System.currentTimeMillis() }
                                            val total = System.currentTimeMillis() - start
                                            apiMessage = "成功，首字 ${if (first > 0) first - start else total}ms，整句 ${total}ms：${text.take(40)}"
                                        } catch (e: Exception) {
                                            apiMessage = "失败：${e.message?.take(100) ?: ""}"
                                        }
                                        apiBusy = false
                                    }
                                },
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = "模型管理") {
                        ModelRow(ModelKind.SENSEVOICE, app, progress[ModelKind.SENSEVOICE.id], settings, actionsEnabled = !live.running && !live.starting && !live.stopping && !selfTestBusy && !evalBusy, onDelete = { confirmDelete = it })
                        Spacer(Modifier.height(8.dp))
                        ModelRow(ModelKind.TRANSLATOR, app, progress[ModelKind.TRANSLATOR.id], settings, actionsEnabled = !live.running && !live.starting && !live.stopping && !selfTestBusy && !evalBusy, onDelete = { confirmDelete = it })
                        Spacer(Modifier.height(8.dp))
                        ModelRow(ModelKind.TRANSLATOR_LITE, app, progress[ModelKind.TRANSLATOR_LITE.id], settings, actionsEnabled = !live.running && !live.starting && !live.stopping && !selfTestBusy && !evalBusy, onDelete = { confirmDelete = it })
                        Spacer(Modifier.height(8.dp))
                        ModelRow(ModelKind.ASSISTANT, app, progress[ModelKind.ASSISTANT.id], settings, actionsEnabled = !live.running && !live.starting && !live.stopping && !selfTestBusy && !evalBusy, onDelete = { confirmDelete = it })
                        Spacer(Modifier.height(8.dp))
                        ModelRow(ModelKind.ASSISTANT_LITE, app, progress[ModelKind.ASSISTANT_LITE.id], settings, actionsEnabled = !live.running && !live.starting && !live.stopping && !selfTestBusy && !evalBusy, onDelete = { confirmDelete = it })
                        Spacer(Modifier.height(12.dp))
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(
                            title = "使用国内镜像下载",
                            subtitle = "hf-mirror.com，失败时自动回退 huggingface.co",
                            checked = settings.hfMirror,
                            onChange = { v -> app.settings.update { it.copy(hfMirror = v) } },
                        )
                        Text(
                            "模型保存在 ${app.models.root.absolutePath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniButton(if (selfTestBusy) "自检中…" else "识别 + 翻译自检", {
                                if (!selfTestBusy) {
                                    scope.launch {
                                        selfTestBusy = true
                                        selfTestResult = ""
                                        selfTestResult = runSelfTest(context, app)
                                        selfTestBusy = false
                                    }
                                }
                            }, accent = true, enabled = !selfTestBusy && !live.running && !live.starting)
                            Text(
                                "用一段 16 秒中文测试音频跑完整链路，不经过麦克风。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selfTestResult.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                selfTestResult,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                                    .padding(10.dp),
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = "识别与分句") {
                        Dropdown(
                            value = settings.sourceLang,
                            options = Langs.sources,
                            onChange = { v -> app.settings.update { it.copy(sourceLang = v) } },
                            label = "识别语言",
                            minWidth = 120,
                            enabled = !live.running && !live.starting && !live.stopping,
                        )
                        Spacer(Modifier.height(10.dp))
                        SliderRow(
                            title = "停顿判定",
                            valueText = "${settings.pauseMs}ms",
                            value = settings.pauseMs.toFloat(),
                            range = 250f..900f,
                            steps = 12,
                            enabled = !live.running && !live.starting && !live.stopping,
                            onChange = { v -> app.settings.update { it.copy(pauseMs = v.toInt()) } },
                        )
                        Text(
                            "300–400ms 比较跟手；调得太低会把一句话切成几段。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        SwitchRow(
                            title = "说话人区分",
                            subtitle = "CAM++ 声纹，本地实时聚类，标出「说话人 1/2」",
                            checked = settings.diarization,
                            onChange = { v -> app.settings.update { it.copy(diarization = v) } },
                        )
                        SliderRow(
                            title = "识别线程",
                            valueText = "${settings.asrThreads} 线程",
                            value = settings.asrThreads.toFloat(),
                            range = 1f..8f,
                            steps = 6,
                            enabled = !live.running && !live.starting && !live.stopping,
                            onChange = { v ->
                                val next = v.toInt()
                                if (next != settings.asrThreads) {
                                    app.settings.update { it.copy(asrThreads = next) }
                                    // 识别器按 (语言, 线程数) 缓存；显式释放后下一次识别按新线程重建。
                                    app.appScope.launch {
                                        runCatching { app.live.releaseAsrCache() }
                                    }
                                }
                            },
                        )
                        SliderRow(
                            title = "推理线程",
                            valueText = "${settings.llmThreads} 线程",
                            value = settings.llmThreads.toFloat(),
                            range = 1f..8f,
                            steps = 6,
                            enabled = !live.running && !live.starting && !live.stopping,
                            onChange = { v ->
                                val next = v.toInt()
                                if (next != settings.llmThreads) {
                                    app.settings.update { it.copy(llmThreads = next) }
                                    // A4：已加载的模型不会自己换线程数，显式卸载后按新值重建。
                                    app.llm.reloadLocalModelsForThreadChange()
                                    app.llm.warmUpTranslator()
                                }
                            },
                        )
                        SwitchRow(
                            title = "上下文校正",
                            subtitle = "用翻译模型修正同音字（会增加延迟）；API 模式会自动跳过",
                            checked = settings.autoCorrect,
                            onChange = { v -> app.settings.update { it.copy(autoCorrect = v) } },
                        )
                    }
                }

                item {
                    SectionCard(title = "字幕显示") {
                        SwitchRow(
                            title = "字幕模式显示原文",
                            subtitle = "全屏字幕和浮字幕上方显示识别原文；主记录页始终保留原文",
                            checked = settings.showSource,
                            onChange = { v -> app.settings.update { it.copy(showSource = v) } },
                        )
                        SwitchRow(
                            title = "边说边译",
                            subtitle = "说话过程中先出灰色草稿译文；API 模式会自动跳过，保证正式翻译稳定",
                            checked = settings.draftTranslate,
                            onChange = { v -> app.settings.update { it.copy(draftTranslate = v) } },
                        )
                        SliderRow(
                            title = "字幕字号",
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
                            title = "字幕行数",
                            valueText = "${settings.subtitleLines} 行",
                            value = settings.subtitleLines.toFloat(),
                            range = 1f..4f,
                            steps = 2,
                            onChange = { v -> app.settings.update { it.copy(subtitleLines = v.toInt()) } },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("主题", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Dropdown(
                                value = settings.theme,
                                options = listOf("dark" to "深色", "light" to "浅色", "system" to "跟随系统"),
                                onChange = { v -> app.settings.update { it.copy(theme = v) } },
                                minWidth = 96,
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = "课堂助教") {
                        SwitchRow(
                            title = "启用助教",
                            subtitle = "实时总结 + 自动回答课堂提问",
                            checked = settings.assistantEnabled,
                            onChange = { v -> app.live.updateAssistantEnabled(v) },
                        )
                        SwitchRow(
                            title = "自动识别提问并作答",
                            subtitle = "关闭后仍可手动在侧栏提问",
                            checked = settings.autoQuestion,
                            onChange = { v -> app.settings.update { it.copy(autoQuestion = v) } },
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChoiceCard(
                                title = "内置模型",
                                subtitle = "Qwen3-4B / 轻量 1.5B · 离线",
                                selected = settings.analysisMode == SettingsData.MODE_BUILTIN,
                                modifier = Modifier.weight(1f),
                                enabled = !live.running && !live.starting,
                            ) { app.settings.update { it.copy(analysisMode = SettingsData.MODE_BUILTIN) } }
                            ChoiceCard(
                                title = "API",
                                subtitle = "复用或单独配置",
                                selected = settings.analysisMode == SettingsData.MODE_API,
                                modifier = Modifier.weight(1f),
                                enabled = !live.running && !live.starting,
                            ) { app.settings.update { it.copy(analysisMode = SettingsData.MODE_API) } }
                        }
                        if (settings.analysisMode == SettingsData.MODE_BUILTIN) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ChoiceCard(
                                    title = "4B · 2.5GB",
                                    subtitle = "质量优先，建议 8GB 内存",
                                    selected = settings.assistantVariant == "4b",
                                    modifier = Modifier.weight(1f),
                                    enabled = !live.running && !live.starting,
                                ) { app.settings.update { it.copy(assistantVariant = "4b") } }
                                ChoiceCard(
                                    title = "1.5B · 1.1GB",
                                    subtitle = "更省内存，问答更简单",
                                    selected = settings.assistantVariant == "1.5b",
                                    modifier = Modifier.weight(1f),
                                    enabled = !live.running && !live.starting,
                                ) { app.settings.update { it.copy(assistantVariant = "1.5b") } }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "状态：$assistantStatus · " + when {
                                settings.analysisMode == SettingsData.MODE_API -> "使用 API"
                                app.models.assistantReady(settings.assistantVariant) -> "模型已就绪"
                                else -> "模型未下载"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (settings.analysisMode == SettingsData.MODE_API) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = settings.analysisBaseUrl,
                                onValueChange = { v -> app.settings.update { it.copy(analysisBaseUrl = v) } },
                                label = { Text("分析接口地址（留空复用翻译 API）") },
                                singleLine = true,
                                enabled = !live.running && !live.starting && !live.stopping,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = settings.analysisModel,
                                    onValueChange = { v -> app.settings.update { it.copy(analysisModel = v) } },
                                    label = { Text("分析模型") },
                                    singleLine = true,
                                    enabled = !live.running && !live.starting && !live.stopping,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = settings.analysisKey,
                                    onValueChange = { v -> app.settings.update { it.copy(analysisKey = v) } },
                                    label = { Text("Key（可选）") },
                                    singleLine = true,
                                    enabled = !live.running && !live.starting && !live.stopping,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                item {
                    SectionCard(title = "存储与隐私") {
                        val info = storage
                        if (info != null) {
                            InfoRow("数据库", mb(info.databaseBytes))
                            InfoRow("录音", mb(info.audioBytes))
                            InfoRow("课程资料", mb(info.materialBytes))
                            InfoRow("离线模型", gb(info.modelBytes))
                            InfoRow("磁盘剩余", gb(info.freeBytes))
                        } else {
                            Text("统计中…（如一直不动，请点「刷新统计」）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        SwitchRow(
                            title = "保存逐句录音",
                            subtitle = "课后可按句回听，约 1MB/分钟",
                            checked = settings.keepAudio,
                            onChange = { v -> app.settings.update { it.copy(keepAudio = v) } },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("自动清理", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Dropdown(
                                value = settings.autoCleanupDays.toString(),
                                options = listOf("0" to "永久保留", "7" to "7 天", "30" to "30 天", "90" to "90 天"),
                                onChange = { v -> app.settings.update { it.copy(autoCleanupDays = v.toInt()) } },
                                minWidth = 96,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniButton("按设置清理", { confirmCleanup = true }, accent = true, enabled = settings.autoCleanupDays > 0)
                            MiniButton("刷新统计", { storageVersion++ })
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "所有数据保存在本机应用目录；不联网、不上传。录音与数据库权限为应用私有。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    SectionCard(title = "关于") {
                        Text("本地同传 · Android 平板版", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "麦克风 / 系统声音 → Silero VAD 分句 → SenseVoice 识别 → 本地 Hy-MT2 翻译 → 实时字幕。全部在本机完成。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "识别：sherpa-onnx · 翻译/助教：llama.cpp · 声纹：CAM++",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "诊断日志（识别/翻译异常时发给开发者）。包含设备、设置和运行日志，不包含录音内容或 API Key。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Switch(
                                checked = settings.latencyProbe,
                                onCheckedChange = { v ->
                                    app.settings.update { it.copy(latencyProbe = v) }
                                },
                            )
                            Text(
                                "记录每句延迟（Lat 日志，用于排查卡顿）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
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
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("关闭", color = Accent) } },
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
                        runCatching {
                            withContext(Dispatchers.IO) {
                                when (kind) {
                                    ModelKind.TRANSLATOR, ModelKind.TRANSLATOR_LITE -> app.llm.unloadTranslator()
                                    ModelKind.ASSISTANT, ModelKind.ASSISTANT_LITE -> app.llm.unloadAssistant()
                                    else -> Unit
                                }
                            }
                        }
                        app.models.delete(kind)
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
    Column(
        modifier = modifier
            .background(
                if (selected) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(11.dp)
            )
            .border(1.dp, if (selected) Accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp)
            .alpha(if (enabled) 1f else 0.45f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp), color = if (selected) Accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
        }
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = Accent)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp))
            Text(kind.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p?.state == "downloading") {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Accent)
                Text(
                    "${p.message} · ${mb(p.downloaded)} / ${mb(p.total)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (p?.state == "error") {
                Text(p.message, style = MaterialTheme.typography.labelSmall, color = Danger)
                // 续传拼出的坏文件只有删掉重下才能自愈，所以错误状态必须给「删除并重下」。
                Text(
                    "点右侧「删除并重下」可清掉残留的下载文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val problem = app.models.fileProblem(kind)
                if (problem != null && problem != "未下载") {
                    Text(problem, style = MaterialTheme.typography.labelSmall, color = Danger)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            app.models.isReady(kind) -> {
                MiniButton("删除", { onDelete(kind) }, danger = true, enabled = actionsEnabled)
            }
            p?.state == "downloading" -> MiniButton("取消", { app.models.cancel(kind) })
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniButton(
                    if (p?.state == "error") "重试" else if (kind.let { app.models.fileFor(it).exists() }) "重新下载" else "下载",
                    { app.models.download(kind, settings.hfMirror) },
                    accent = true,
                    enabled = actionsEnabled,
                )
                if (p?.state == "error") {
                    MiniButton("删除并重下", {
                        // 先删残留（含 .part.tmp/.etag），再从头下载，避免坏断点反复失败。
                        app.models.delete(kind)
                        app.models.download(kind, settings.hfMirror)
                    }, danger = true, enabled = actionsEnabled)
                } else if (kind.let { app.models.fileFor(it).exists() }) {
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
    Column {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBase,
            label = { Text("接口地址") },
            placeholder = { Text("http://192.168.1.100:8000/v1（电脑的局域网地址）") },
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
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniButton(if (busy) "请稍候…" else "自动检测", onDetect, accent = true, enabled = !busy && actionsEnabled)
            MiniButton("测试翻译", onTest, enabled = !busy && actionsEnabled)
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.6.dp, color = Accent)
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
        appendLine("翻译：mode=${s.llmMode} variant=${s.modelVariant} apiBase=${s.apiBaseUrl} apiModel=${s.apiModel}")
        appendLine("识别：source=${s.sourceLang} target=${s.targetLang} pause=${s.pauseMs} asrThreads=${s.asrThreads} llmThreads=${s.llmThreads} draft=${s.draftTranslate} diarization=${s.diarization} correct=${s.autoCorrect}")
        appendLine("助教：enabled=${s.assistantEnabled} mode=${s.analysisMode} variant=${s.assistantVariant} apiBase=${s.analysisBaseUrl} model=${s.analysisModel}")
        appendLine("显示：subtitleLines=${s.subtitleLines} size=${s.subtitleSize} showSource=${s.showSource} keepAudio=${s.keepAudio}")
        appendLine("直播：running=${st.running} starting=${st.starting} stopping=${st.stopping} paused=${st.paused} source=${st.source} session=${st.sessionId}")
        appendLine("      segments=${st.segments.size} partial=${st.partialSrc.length}字 asrMs=${st.lastAsrMs} translateFirst=${st.lastLatencyMs} level=${"%.4f".format(st.audioLevel)} error=${st.error}")
        appendLine("状态：ASR=${app.live.asrStatus.value} / 翻译=${app.llm.translatorStatus.value} / 助教=${app.llm.assistantStatus.value}")
        appendLine("模型目录：${app.models.root.absolutePath}")
    }.trim()
}

private suspend fun runSelfTest(context: android.content.Context, app: App): String {
  // 主流程可能已经缓存着一个识别器；自检会再加载一个，先释放旧实例，避免 4GB 设备双份占用。
  app.live.releaseAsrCache()
  val asr = com.localinterpreter.tablet.asr.AsrEngine()
  var vad: com.localinterpreter.tablet.asr.VadSegmenter? = null
  return try {
    val t0 = System.currentTimeMillis()
    if (!asr.ensureLoaded("zh")) return "自检失败：${asr.status.value}"
    val loadMs = System.currentTimeMillis() - t0
    val samples = withContext(Dispatchers.IO) { readWav(context, "test_zh.wav") }
    if (samples.isEmpty()) return "自检失败：测试音频读取失败"
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
    val tr = if (first.isNotBlank()) {
        app.llm.translate(first, "zh", "en", maxTokens = 200, temperature = 0.2f) {
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
