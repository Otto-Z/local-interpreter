package com.localinterpreter.tablet.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.live.LiveSegment
import com.localinterpreter.tablet.live.LiveState

class SubtitleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val app = application as App
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val live by app.live.state.collectAsStateWithLifecycle()
            // 全屏字幕始终使用深黑影院背景以保证投影与远距阅读对比度。
            AppTheme("dark") {
                SubtitleScreen(
                    live = live,
                    settings = settings,
                    onFontDelta = { delta ->
                        app.settings.update { it.copy(subtitleSize = (it.subtitleSize + delta).coerceIn(24, 64)) }
                    },
                    onToggleSource = {
                        app.settings.update { it.copy(showSource = !it.showSource) }
                    },
                    onCycleLines = {
                        app.settings.update { it.copy(subtitleLines = if (it.subtitleLines >= 4) 1 else it.subtitleLines + 1) }
                    },
                    onTogglePause = {
                        com.localinterpreter.tablet.audio.LiveService.togglePause(this, live.paused)
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SubtitleScreen(
    live: LiveState,
    settings: SettingsData,
    onFontDelta: (Int) -> Unit,
    onToggleSource: () -> Unit,
    onCycleLines: () -> Unit,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
) {
    val c = AppColors
    var showHud by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF040608))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { showHud = !showHud }
            .padding(horizontal = 44.dp, vertical = 24.dp),
    ) {
        val lines = buildList {
            addAll(live.segments.takeLast(settings.subtitleLines.coerceIn(1, 4)))
            if (live.partialSrc.isNotBlank() || live.partialTgt.isNotBlank()) {
                add(
                    LiveSegment(
                        id = -1,
                        t0 = 0.0,
                        t1 = 0.0,
                        srcLang = "",
                        src = live.partialSrc,
                        tgt = live.partialTgt,
                        draftTgt = live.partialDraft,
                    )
                )
            }
        }

        val scroll = rememberScrollState()
        LaunchedEffect(lines.size, lines.lastOrNull()?.tgt, lines.lastOrNull()?.src) {
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 56.dp, bottom = 20.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 1040.dp)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (lines.isEmpty()) {
                    Text(
                        if (live.running) "正在聆听，说话后字幕会自动出现在这里…"
                        else "点击主界面的「开始同传」，全屏字幕会实时显示在这里。",
                        color = Color(0xFF8C97A8),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    Text(
                        "轻触屏幕任意位置可隐藏或呼出顶部控制条。",
                        color = Color(0xFF5D6777),
                        fontSize = 14.sp,
                    )
                } else {
                    lines.forEachIndexed { idx, seg ->
                        val isLatest = idx == lines.lastIndex
                        Column {
                            if (settings.showSource && seg.src.isNotBlank()) {
                                Text(
                                    seg.src,
                                    color = Color(0xFF8C97A8).copy(alpha = if (isLatest) 0.95f else 0.7f),
                                    fontSize = (settings.subtitleSize * 0.52).sp,
                                    lineHeight = (settings.subtitleSize * 0.76).sp,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            val displayTgt = seg.tgt.ifBlank {
                                if (!settings.showSource && seg.src.isNotBlank()) seg.src
                                else if (seg.src.isNotBlank()) "…"
                                else ""
                            }
                            Text(
                                displayTgt,
                                color = when {
                                    seg.tgt.isBlank() -> Color(0xFF6B7480)
                                    seg.draftTgt -> Color(0xFFB5C0CF)
                                    isLatest -> Color(0xFFF6F8FB)
                                    else -> Color(0xFFD0D7E2).copy(alpha = 0.85f)
                                },
                                fontSize = settings.subtitleSize.sp,
                                lineHeight = (settings.subtitleSize * 1.32).sp,
                                fontWeight = if (isLatest && !seg.draftTgt) FontWeight.SemiBold else FontWeight.Medium,
                                fontStyle = if (seg.draftTgt) FontStyle.Italic else FontStyle.Normal,
                            )
                        }
                    }
                }
            }
        }

        // 顶部半透明胶囊控制栏（轻触屏幕可隐藏，获得纯净影院字幕）
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC11161E), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF232C3A), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseDot(
                    color = when {
                        live.running && !live.paused -> c.accent
                        live.running && live.paused -> c.warn
                        else -> Color(0xFF7B8798)
                    },
                    active = live.running && !live.paused,
                    size = 8,
                )
                Text(
                    when {
                        live.starting -> "启动中…"
                        live.running && live.paused -> "已暂停"
                        live.running -> "同传中 · 轻触空白处隐藏工具栏"
                        live.sessionId != null -> "已结束"
                        else -> "等待开始"
                    },
                    color = Color(0xFF9BA6B6),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                if (live.running && !live.stopping) {
                    MiniButton(
                        text = if (live.paused) "继续" else "暂停",
                        icon = if (live.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        onClick = onTogglePause,
                    )
                }
                MiniButton(if (settings.showSource) "双语" else "仅译文", onToggleSource, accent = settings.showSource)
                MiniButton("${settings.subtitleLines} 行", onCycleLines)
                MiniButton("A-", { onFontDelta(-4) }, enabled = settings.subtitleSize > 24)
                MiniButton("A+", { onFontDelta(4) }, enabled = settings.subtitleSize < 64)
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "退出字幕模式", tint = Color(0xFF9BA6B6))
                }
            }
        }
    }
}
