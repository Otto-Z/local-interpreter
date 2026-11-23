package com.localinterpreter.tablet.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsData(
    val llmMode: String = MODE_BUILTIN,
    val modelVariant: String = "q4",
    val apiBaseUrl: String = "",
    val apiModel: String = "",
    val apiKey: String = "",
    val analysisMode: String = MODE_BUILTIN,
    val assistantVariant: String = "4b",
    val analysisBaseUrl: String = "",
    val analysisModel: String = "",
    val analysisKey: String = "",
    val targetLang: String = "auto",
    val sourceLang: String = "auto",
    val pauseMs: Int = 400,
    val showSource: Boolean = true,
    val draftTranslate: Boolean = true,
    val autoCorrect: Boolean = false,
    val diarization: Boolean = true,
    val assistantEnabled: Boolean = true,
    val autoQuestion: Boolean = true,
    val autoTerms: Boolean = true,
    val subtitleLines: Int = 2,
    val subtitleSize: Int = 40,
    val overlayOpacity: Float = 0.82f,
    val theme: String = "dark",
    val keepAudio: Boolean = true,
    val autoCleanupDays: Int = 0,
    val hfMirror: Boolean = true,
    val firstRunDone: Boolean = false,
    val asrThreads: Int = 4,
    val llmThreads: Int = 4,
    val translationContext: Int = 1,
    /** 环境抗噪档位：sensitive（灵敏/远场）| standard（标准）| strict（强力抗噪） */
    val noiseSuppression: String = "standard",
    /** 2 秒环境测噪校准得到的初始底噪基线（RMS）；0 表示未校准使用默认自适应初始值。 */
    val calibratedNoiseFloor: Float = 0f,
    /** 实验性 Vulkan GPU 加速开关（仅当构建包含 Vulkan 后端且设备支持时生效，失败自动回退 CPU）。 */
    val useGpu: Boolean = false,
    /** 主同传页译文字号（sp），支持界面一键缩放。 */
    val liveFontSize: Int = 21,
    /** 主同传页是否显示识别原文（false 为仅看译文的专注模式）。 */
    val liveShowSource: Boolean = true,
    /** 诊断用：开启后在 logcat/日志里打印每句的 Lat 统计行。默认关闭。 */
    val latencyProbe: Boolean = false,
) {
    companion object {
        const val MODE_BUILTIN = "builtin"
        const val MODE_API = "api"
        const val NOISE_SENSITIVE = "sensitive"
        const val NOISE_STANDARD = "standard"
        const val NOISE_STRICT = "strict"
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsData> = _state.asStateFlow()

    val value: SettingsData get() = _state.value

    private fun load(): SettingsData {
        val cores = Runtime.getRuntime().availableProcessors()
        // 识别和翻译不会同时跑满（翻译排在分句之后），按核心数给足线程收益最大；
        // 4 核实测：2 线程首字 2243ms，4 线程 1240ms。留 1 核给界面/音频后取上限 6。
        val asrThreads = (cores / 2).coerceIn(2, 6)
        val recommendedLlmThreads = (cores - 1).coerceIn(2, 6)
        val d = SettingsData(asrThreads = asrThreads, llmThreads = recommendedLlmThreads)
        // 2.2.6-2.2.8 在多核设备上默认只给了 2 个推理线程，一次性迁移到推荐值；
        // 之后若用户手动改回 2 线程（例如为了省电降温），必须尊重用户选择不再覆盖。
        val storedLlmThreadsRaw = prefs.getInt("llmThreads", recommendedLlmThreads)
        val llmThreads = if (!prefs.getBoolean("migratedLlmThreads23", false)) {
            prefs.edit().putBoolean("migratedLlmThreads23", true).apply()
            if (storedLlmThreadsRaw == 2 && recommendedLlmThreads > 2) {
                prefs.edit().putInt("llmThreads", recommendedLlmThreads).apply()
                recommendedLlmThreads
            } else {
                storedLlmThreadsRaw
            }
        } else {
            storedLlmThreadsRaw
        }
        return SettingsData(
            llmMode = (prefs.getString("llmMode", d.llmMode) ?: d.llmMode)
                .takeIf { it == SettingsData.MODE_BUILTIN || it == SettingsData.MODE_API } ?: SettingsData.MODE_BUILTIN,
            modelVariant = (prefs.getString("modelVariant", d.modelVariant) ?: d.modelVariant)
                .takeIf { it == "q4" || it == "q2" } ?: "q4",
            apiBaseUrl = prefs.getString("apiBaseUrl", d.apiBaseUrl) ?: d.apiBaseUrl,
            apiModel = prefs.getString("apiModel", d.apiModel) ?: d.apiModel,
            apiKey = prefs.getString("apiKey", d.apiKey) ?: d.apiKey,
            analysisMode = (prefs.getString("analysisMode", d.analysisMode) ?: d.analysisMode)
                .takeIf { it == SettingsData.MODE_BUILTIN || it == SettingsData.MODE_API } ?: SettingsData.MODE_BUILTIN,
            assistantVariant = (prefs.getString("assistantVariant", d.assistantVariant) ?: d.assistantVariant)
                .takeIf { it == "4b" || it == "1.5b" } ?: "4b",
            analysisBaseUrl = prefs.getString("analysisBaseUrl", d.analysisBaseUrl) ?: d.analysisBaseUrl,
            analysisModel = prefs.getString("analysisModel", d.analysisModel) ?: d.analysisModel,
            analysisKey = prefs.getString("analysisKey", d.analysisKey) ?: d.analysisKey,
            targetLang = (prefs.getString("targetLang", d.targetLang) ?: d.targetLang)
                .takeIf { it in setOf("auto","zh","en","ja","ko","fr","de","es","ru","it","pt","ar","th","vi") } ?: "auto",
            sourceLang = (prefs.getString("sourceLang", d.sourceLang) ?: d.sourceLang)
                .takeIf { it in setOf("auto","zh","en","ja","ko","yue") } ?: "auto",
            pauseMs = prefs.getInt("pauseMs", d.pauseMs).coerceIn(200, 2000),
            showSource = prefs.getBoolean("showSource", d.showSource),
            draftTranslate = prefs.getBoolean("draftTranslate", d.draftTranslate),
            autoCorrect = prefs.getBoolean("autoCorrect", d.autoCorrect),
            diarization = prefs.getBoolean("diarization", d.diarization),
            assistantEnabled = prefs.getBoolean("assistantEnabled", d.assistantEnabled),
            autoQuestion = prefs.getBoolean("autoQuestion", d.autoQuestion),
            autoTerms = prefs.getBoolean("autoTerms", d.autoTerms),
            subtitleLines = prefs.getInt("subtitleLines", d.subtitleLines).coerceIn(1, 4),
            subtitleSize = prefs.getInt("subtitleSize", d.subtitleSize).coerceIn(16, 96),
            overlayOpacity = prefs.getFloat("overlayOpacity", d.overlayOpacity).coerceIn(0.2f, 1f),
            theme = (prefs.getString("theme", d.theme) ?: d.theme)
                .takeIf { it == "dark" || it == "light" || it == "system" } ?: "dark",
            keepAudio = prefs.getBoolean("keepAudio", d.keepAudio),
            autoCleanupDays = prefs.getInt("autoCleanupDays", d.autoCleanupDays)
                .takeIf { it in setOf(0, 7, 30, 90) } ?: 0,
            hfMirror = prefs.getBoolean("hfMirror", d.hfMirror),
            firstRunDone = prefs.getBoolean("firstRunDone", d.firstRunDone),
            asrThreads = prefs.getInt("asrThreads", d.asrThreads).coerceIn(1, 8),
            llmThreads = llmThreads.coerceIn(1, 8),
            translationContext = prefs.getInt("translationContext", d.translationContext).coerceIn(0, 3),
            noiseSuppression = (prefs.getString("noiseSuppression", d.noiseSuppression) ?: d.noiseSuppression)
                .takeIf { it in setOf(SettingsData.NOISE_SENSITIVE, SettingsData.NOISE_STANDARD, SettingsData.NOISE_STRICT) }
                ?: SettingsData.NOISE_STANDARD,
            calibratedNoiseFloor = prefs.getFloat("calibratedNoiseFloor", d.calibratedNoiseFloor)
                .takeIf { it == 0f || it in 0.0005f..0.03f } ?: 0f,
            useGpu = prefs.getBoolean("useGpu", d.useGpu),
            liveFontSize = prefs.getInt("liveFontSize", d.liveFontSize).coerceIn(16, 30),
            liveShowSource = prefs.getBoolean("liveShowSource", d.liveShowSource),
            latencyProbe = prefs.getBoolean("latencyProbe", d.latencyProbe),
        )
    }

    @Synchronized
    private fun edit(block: (SettingsData) -> SettingsData) {
        val next = block(_state.value)
        _state.value = next
        prefs.edit()
            .putString("llmMode", next.llmMode)
            .putString("modelVariant", next.modelVariant)
            .putString("apiBaseUrl", next.apiBaseUrl)
            .putString("apiModel", next.apiModel)
            .putString("apiKey", next.apiKey)
            .putString("analysisMode", next.analysisMode)
            .putString("assistantVariant", next.assistantVariant)
            .putString("analysisBaseUrl", next.analysisBaseUrl)
            .putString("analysisModel", next.analysisModel)
            .putString("analysisKey", next.analysisKey)
            .putString("targetLang", next.targetLang)
            .putString("sourceLang", next.sourceLang)
            .putInt("pauseMs", next.pauseMs)
            .putBoolean("showSource", next.showSource)
            .putBoolean("draftTranslate", next.draftTranslate)
            .putBoolean("autoCorrect", next.autoCorrect)
            .putBoolean("diarization", next.diarization)
            .putBoolean("assistantEnabled", next.assistantEnabled)
            .putBoolean("autoQuestion", next.autoQuestion)
            .putBoolean("autoTerms", next.autoTerms)
            .putInt("subtitleLines", next.subtitleLines)
            .putInt("subtitleSize", next.subtitleSize)
            .putFloat("overlayOpacity", next.overlayOpacity)
            .putString("theme", next.theme)
            .putBoolean("keepAudio", next.keepAudio)
            .putInt("autoCleanupDays", next.autoCleanupDays)
            .putBoolean("hfMirror", next.hfMirror)
            .putBoolean("firstRunDone", next.firstRunDone)
            .putInt("asrThreads", next.asrThreads)
            .putInt("llmThreads", next.llmThreads)
            .putInt("translationContext", next.translationContext)
            .putString("noiseSuppression", next.noiseSuppression)
            .putFloat("calibratedNoiseFloor", next.calibratedNoiseFloor)
            .putBoolean("useGpu", next.useGpu)
            .putInt("liveFontSize", next.liveFontSize)
            .putBoolean("liveShowSource", next.liveShowSource)
            .putBoolean("latencyProbe", next.latencyProbe)
            .apply()
    }

    fun update(block: (SettingsData) -> SettingsData) = edit(block)
}
