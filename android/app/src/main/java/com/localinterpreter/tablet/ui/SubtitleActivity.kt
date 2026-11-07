package com.localinterpreter.tablet.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localinterpreter.tablet.App
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localinterpreter.tablet.data.SettingsData
import com.localinterpreter.tablet.live.LiveState

class SubtitleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val app = application as App
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val live by app.live.state.collectAsStateWithLifecycle()
            AppTheme(settings.theme) {
                SubtitleScreen(live, settings) { finish() }
            }
        }
    }
}

@Composable
private fun SubtitleScreen(live: LiveState, settings: SettingsData, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF030405))
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(
                        if (live.running && !live.paused) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    live.starting -> "启动中…"
                    live.running && live.paused -> "已暂停"
                    live.running -> "同传中"
                    live.sessionId != null -> "已结束"
                    else -> "等待开始"
                },
                color = Color(0xFF8A939F),
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "退出字幕模式", tint = Color(0xFF8A939F))
            }
        }

        val lines = buildList {
            addAll(live.segments.takeLast(settings.subtitleLines.coerceIn(1, 4)))
            if (live.partialSrc.isNotBlank() || live.partialTgt.isNotBlank()) {
                add(
                    com.localinterpreter.tablet.live.LiveSegment(
                        id = -1,
                        t0 = 0.0,
                        t1 = 0.0,
                        srcLang = "",
                        src = live.partialSrc,
                        tgt = live.partialTgt,
                    )
                )
            }
        }

        val scroll = rememberScrollState()
        androidx.compose.runtime.LaunchedEffect(lines.size, lines.lastOrNull()?.tgt, lines.lastOrNull()?.src) {
            // 等这一帧布局完成后再滚到底，否则 maxValue 还是上一行的值，最新一句会露不出来。
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (lines.isEmpty()) {
                Text(
                    "点主界面的「开始」，字幕会出现在这里。",
                    color = Color(0xFF8A939F),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                )
            } else {
                lines.forEach { seg ->
                    Column {
                        if (settings.showSource && seg.src.isNotBlank()) {
                            Text(
                                seg.src,
                                color = Color(0xFF8A939F),
                                fontSize = (settings.subtitleSize * 0.55).sp,
                                lineHeight = (settings.subtitleSize * 0.8).sp,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            seg.tgt.ifBlank { if (seg.src.isNotBlank()) "…" else "" },
                            color = if (seg.tgt.isBlank()) Color(0xFF6B7480) else Color(0xFFF2F4F7),
                            fontSize = settings.subtitleSize.sp,
                            lineHeight = (settings.subtitleSize * 1.32).sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
