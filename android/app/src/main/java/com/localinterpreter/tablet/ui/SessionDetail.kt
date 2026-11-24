package com.localinterpreter.tablet.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.assistant.CourseAssistant
import com.localinterpreter.tablet.data.Bookmark
import com.localinterpreter.tablet.data.Course
import com.localinterpreter.tablet.data.Question
import com.localinterpreter.tablet.data.Segment
import com.localinterpreter.tablet.data.Session
import com.localinterpreter.tablet.data.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class SessionDetailData(
    val session: Session?,
    val segments: List<Segment>,
    val speakers: List<Speaker>,
    val bookmarks: List<Bookmark>,
    val questions: List<Question>,
    val quiz: String,
)

@Composable
fun SessionDetailPane(
    app: App,
    course: Course,
    sessionId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<Session?>(null) }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var speakers by remember { mutableStateOf<List<Speaker>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var quiz by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var quizBusy by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var titleEdit by remember { mutableStateOf<String?>(null) }
    var renameSpeaker by remember { mutableStateOf<Speaker?>(null) }
    var speakerName by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var pendingSrt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingMd by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingJson by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingTxt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var titleBusy by remember { mutableStateOf(false) }
    var retranslating by remember { mutableStateOf(false) }
    var retranslateStatus by remember { mutableStateOf("") }
    var assignCourse by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var summaryExpanded by remember { mutableStateOf(false) }
    var quizExpanded by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("all") } // all | bookmarks | lowconf
    var courseOptions by remember { mutableStateOf<List<Course>>(emptyList()) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    val assistant = remember { CourseAssistant(app) }

    fun writeExport(uri: android.net.Uri, data: Pair<String, String>?) {
        if (data == null) return
        app.appScope.launch {
            val ok = writeTextToUri(context, uri, data.second)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (ok) "已导出 ${data.first}" else "导出失败，请换一个存储位置", Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) writeExport(uri, pendingSrt)
        pendingSrt = null
    }
    val mdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) writeExport(uri, pendingMd)
        pendingMd = null
    }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) writeExport(uri, pendingTxt)
        pendingTxt = null
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeExport(uri, pendingJson)
        pendingJson = null
    }

    LaunchedEffect(sessionId, tick) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                SessionDetailData(
                    session = app.db.getSession(sessionId),
                    segments = app.db.listSegments(sessionId),
                    speakers = app.db.listSpeakers(sessionId),
                    bookmarks = app.db.listBookmarks(sessionId),
                    questions = app.db.listQuestions(sessionId),
                    quiz = app.db.getQuiz(sessionId)?.content ?: "",
                )
            }
            session = loaded.session
            segments = loaded.segments
            speakers = loaded.speakers
            bookmarks = loaded.bookmarks
            questions = loaded.questions
            quiz = loaded.quiz
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Toast.makeText(context, "读取课次失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.value?.release() }
            player.value = null
        }
    }

    fun play(path: String?, id: Long) {
        if (path == null || !File(path).exists()) {
            Toast.makeText(context, "这段没有保存录音", Toast.LENGTH_SHORT).show()
            return
        }
        if (playingId == id) {
            runCatching { player.value?.stop() }
            runCatching { player.value?.release() }
            player.value = null
            playingId = null
            return
        }
        runCatching { player.value?.release() }
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.prepare()
            mp.setOnCompletionListener {
                playingId = null
                runCatching { it.release() }
                player.value = null
            }
            mp.setOnErrorListener { p, _, _ ->
                playingId = null
                runCatching { p.release() }
                player.value = null
                Toast.makeText(context, "播放失败", Toast.LENGTH_SHORT).show()
                true
            }
            mp.start()
            player.value = mp
            playingId = id
        } catch (_: Exception) {
            Toast.makeText(context, "播放失败", Toast.LENGTH_SHORT).show()
            runCatching { mp.release() }
        }
    }

    val speakersById = remember(speakers) { speakers.associateBy { it.id } }
    val bookmarkSegIds = remember(bookmarks) { bookmarks.mapNotNull { it.segmentId }.toSet() }
    val lowConfCount = remember(segments) { segments.count { it.lowConfidence } }
    val missingTranslations = remember(segments) {
        segments.count {
            it.tgtText.isBlank() || it.tgtText.startsWith("（翻译失败") || it.tgtText.startsWith("（未翻译")
        }
    }
    val filteredSegments = remember(segments, bookmarkSegIds, filterMode, searchQuery) {
        val q = searchQuery.trim()
        segments.filter { seg ->
            val modeOk = when (filterMode) {
                "bookmarks" -> seg.id in bookmarkSegIds
                "lowconf" -> seg.lowConfidence
                else -> true
            }
            modeOk && (q.isEmpty() ||
                seg.srcText.contains(q, ignoreCase = true) ||
                seg.tgtText.contains(q, ignoreCase = true))
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        // 顶栏：返回 + 可点击编辑标题 + 复习题 + 导出 + 更多菜单
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { titleEdit = session?.title ?: "" }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    session?.title?.ifBlank { "未命名课次" } ?: "读取中…",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (session?.let { "${formatDate(it.startedAt)} · ${formatDuration(it.durationS)}" } ?: "") + " · ${course.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MiniButton(
                text = if (quizBusy) "出题中…" else "复习题",
                icon = Icons.Default.AutoAwesome,
                onClick = {
                    if (!quizBusy) {
                        scope.launch {
                            quizBusy = true
                            try {
                                val text = assistant.quiz(sessionId)
                                if (text.isNotBlank()) {
                                    quiz = text
                                    quizExpanded = true
                                    runCatching {
                                        withContext(Dispatchers.IO) { app.db.saveQuiz(sessionId, text) }
                                    }.onFailure { Toast.makeText(context, "复习题保存失败：${it.message?.take(60)}", Toast.LENGTH_LONG).show() }
                                } else {
                                    Toast.makeText(context, "助教模型未就绪，请在设置中下载助教模型或改用 API", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "生成失败：${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                            }
                            quizBusy = false
                        }
                    }
                },
                accent = true,
                enabled = !quizBusy,
            )
            Box {
                MiniButton("导出 ▾", {
                    if (session == null) {
                        Toast.makeText(context, "课次还在读取中，请稍候", Toast.LENGTH_SHORT).show()
                    } else if (segments.isEmpty()) {
                        Toast.makeText(context, "这次课还没有字幕可导出", Toast.LENGTH_SHORT).show()
                    } else {
                        exportMenuOpen = true
                    }
                })
                DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                    fun launchExport(ext: String, producer: () -> String, onReady: (String, String) -> Unit) {
                        exportMenuOpen = false
                        val s = session ?: return
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { producer() } }
                                .onSuccess { content -> onReady(sanitizeFilename(s.title) + ext, content) }
                                .onFailure { Toast.makeText(context, "导出失败：${it.message?.take(60)}", Toast.LENGTH_LONG).show() }
                        }
                    }
                    DropdownMenuItem(text = { Text("SRT 字幕 (.srt)") }, onClick = {
                        launchExport(".srt", { app.repo.sessionSrt(sessionId) }) { name, content ->
                            pendingSrt = name to content; exportLauncher.launch(name)
                        }
                    })
                    DropdownMenuItem(text = { Text("纯文本 (.txt)") }, onClick = {
                        launchExport(".txt", { app.repo.sessionTxt(sessionId) }) { name, content ->
                            pendingTxt = name to content; txtLauncher.launch(name)
                        }
                    })
                    DropdownMenuItem(text = { Text("Markdown 笔记 (.md)") }, onClick = {
                        launchExport(".md", { app.repo.sessionMarkdown(sessionId) }) { name, content ->
                            pendingMd = name to content; mdLauncher.launch(name)
                        }
                    })
                    DropdownMenuItem(text = { Text("完整数据 (.json)") }, onClick = {
                        launchExport(".json", { app.repo.sessionJson(sessionId) }) { name, content ->
                            pendingJson = name to content; jsonLauncher.launch(name)
                        }
                    })
                }
            }
            Box {
                IconActionChip(Icons.Default.MoreVert, contentDescription = "更多课次操作", onClick = { moreMenuOpen = true })
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("修改标题 / AI 起标题") }, onClick = { moreMenuOpen = false; titleEdit = session?.title ?: "" })
                    DropdownMenuItem(text = { Text("归入其他课程") }, onClick = {
                        moreMenuOpen = false
                        scope.launch {
                            courseOptions = runCatching { withContext(Dispatchers.IO) { app.db.listCourses() } }.getOrDefault(emptyList())
                            assignCourse = true
                        }
                    })
                    DropdownMenuItem(
                        text = { Text("删除课次", color = c.danger) },
                        onClick = {
                            moreMenuOpen = false
                            if (session?.status == "running") {
                                Toast.makeText(context, "这次课正在进行中，请先在实时页结束同传", Toast.LENGTH_LONG).show()
                            } else {
                                showDelete = true
                            }
                        },
                    )
                }
            }
        }

        // 说话人 + 标记回听条（仅在存在数据时展示）
        if (speakers.isNotEmpty() || bookmarks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (speakers.isNotEmpty()) {
                    Text("说话人", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    speakers.forEach { s ->
                        val color = parseColorSafe(s.color, c.accent)
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(color.copy(alpha = 0.14f))
                                .clickable { renameSpeaker = s; speakerName = s.label }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Dot(color, 6)
                            Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (bookmarks.isNotEmpty()) {
                    Text("★ 标记回听", style = MaterialTheme.typography.labelSmall, color = c.accent)
                    val marked = segments.filter { it.id in bookmarkSegIds }
                    marked.forEach { seg ->
                        Text(
                            formatSeconds(seg.t0),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = c.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.accentSoft)
                                .border(1.dp, c.accentBorder, RoundedCornerShape(999.dp))
                                .clickable { play(seg.audioPath, seg.id) }
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        // 可折叠摘要卡
        if (session?.summary?.isNotBlank() == true) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { summaryExpanded = !summaryExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("本场要点摘要", style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.weight(1f))
                    Icon(
                        if (summaryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (summaryExpanded) {
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            session?.summary ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        session?.summary?.replace("\n", " ") ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 未译句补译提示条
        if (missingTranslations > 0) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.warnSoft, RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MiniButton(
                    if (retranslating) "补译中…" else "补译 $missingTranslations 句",
                    {
                        if (retranslating) return@MiniButton
                        val liveNow = app.live.state.value
                        if (liveNow.running || liveNow.starting) {
                            Toast.makeText(context, "请先结束实时同传，再补译历史课次", Toast.LENGTH_LONG).show()
                            return@MiniButton
                        }
                        scope.launch {
                            retranslating = true
                            val todo = segments.filter {
                                it.tgtText.isBlank() || it.tgtText.startsWith("（翻译失败") || it.tgtText.startsWith("（未翻译")
                            }
                            val target = session?.targetLang ?: "auto"
                            val glossary = withContext(Dispatchers.IO) { app.db.glossaryPrompt(session?.courseId) }
                            var done = 0
                            retranslateStatus = "0 / ${todo.size}"
                            try {
                                for (seg in todo) {
                                    val liveState = app.live.state.value
                                    if (liveState.running || liveState.starting) {
                                        retranslateStatus = "实时同传已开始，补译停止（$done/${todo.size}）"
                                        return@launch
                                    }
                                    val guessedLang = seg.srcLang.ifBlank {
                                        com.localinterpreter.tablet.llm.Langs.guessLanguage(seg.srcText).ifBlank { "auto" }
                                    }
                                    val temp = if (app.settings.value.llmMode == com.localinterpreter.tablet.data.SettingsData.MODE_API) 0.1f else 0.0f
                                    val out = try {
                                        app.llm.translate(
                                            text = seg.srcText,
                                            srcLang = guessedLang,
                                            target = target,
                                            glossary = glossary,
                                            maxTokens = 320,
                                            temperature = temp,
                                        ).trim()
                                    } catch (e: Exception) {
                                        retranslateStatus = "补译失败：${e.message?.take(60)}（$done/${todo.size}）"
                                        return@launch
                                    }
                                    if (out.isNotBlank()) {
                                        withContext(Dispatchers.IO) { app.db.updateSegmentTranslation(seg.id, out) }
                                        segments = segments.map { if (it.id == seg.id) it.copy(tgtText = out) else it }
                                        done++
                                        retranslateStatus = "$done / ${todo.size}"
                                    }
                                }
                                retranslateStatus = "补译完成：$done / ${todo.size}"
                                Toast.makeText(context, "已补译 $done 句", Toast.LENGTH_SHORT).show()
                            } finally {
                                retranslating = false
                            }
                        }
                    },
                    accent = true,
                    enabled = !retranslating,
                )
                Text(
                    if (retranslateStatus.isNotBlank()) retranslateStatus else "有 $missingTranslations 句译文缺失，可一键用当前翻译模型补齐。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 可折叠复习题卡片
        if (quiz.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, c.accentBorder.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "课后复习题",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.accent,
                        modifier = Modifier.weight(1f).clickable { quizExpanded = !quizExpanded },
                    )
                    MiniButton("复制", {
                        copyText(context, "复习题", quiz)
                        Toast.makeText(context, "复习题已复制", Toast.LENGTH_SHORT).show()
                    })
                    MiniButton("清空", {
                        quiz = ""
                        scope.launch { runCatching { withContext(Dispatchers.IO) { app.db.deleteQuiz(sessionId) } } }
                    })
                    IconButton(onClick = { quizExpanded = !quizExpanded }, modifier = Modifier.size(26.dp)) {
                        Icon(if (quizExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp))
                    }
                }
                if (quizExpanded) {
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            quiz,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

        // 课次内搜索 + 筛选条
        if (segments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("在本场字幕中搜索关键词…", fontSize = 13.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, "清空搜索", modifier = Modifier.size(15.dp))
                            }
                        }
                    } else null,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(11.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MiniButton("全部 ${segments.size}", { filterMode = "all" }, accent = filterMode == "all")
                    if (bookmarkSegIds.isNotEmpty()) {
                        MiniButton("★ 标记 ${bookmarkSegIds.size}", { filterMode = "bookmarks" }, accent = filterMode == "bookmarks")
                    }
                    if (lowConfCount > 0) {
                        MiniButton("≈ 存疑 $lowConfCount", { filterMode = "lowconf" }, accent = filterMode == "lowconf")
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filteredSegments.isEmpty() && segments.isNotEmpty()) {
                item { EmptyHint("没有匹配的字幕", "试试换个关键词或切换回「全部」。") }
            }
            items(filteredSegments, key = { it.id }) { seg ->
                val speaker = seg.speakerId?.let { speakersById[it] }
                val isBookmarked = seg.id in bookmarkSegIds
                val playing = playingId == seg.id
                val shape = RoundedCornerShape(13.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, shape)
                        .border(
                            1.dp,
                            when {
                                playing -> c.accent
                                isBookmarked -> c.accentBorder
                                seg.lowConfidence -> c.warn.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                            shape,
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            formatSeconds(seg.t0),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (speaker != null) {
                            val spColor = parseColorSafe(speaker.color, c.accent)
                            Row(
                                Modifier
                                    .background(spColor.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Dot(spColor, 5)
                                Text(speaker.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (isBookmarked) {
                            Text("★ 标记", style = MaterialTheme.typography.labelSmall, color = c.accent)
                        } else if (seg.lowConfidence) {
                            Text("≈ 存疑", style = MaterialTheme.typography.labelSmall, color = c.warn)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            if (isBookmarked) app.db.removeBookmark(sessionId, seg.id)
                                            else app.db.addBookmark(sessionId, seg.id)
                                        }
                                        bookmarks = withContext(Dispatchers.IO) { app.db.listBookmarks(sessionId) }
                                    }.onSuccess {
                                        Toast.makeText(
                                            context,
                                            if (isBookmarked) "已取消标记" else "已标记该句",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                if (isBookmarked) "取消标记" else "标记该句",
                                tint = if (isBookmarked) c.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                copyText(context, "字幕", listOf(seg.srcText, seg.tgtText).filter { it.isNotBlank() }.joinToString("\n"))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), modifier = Modifier.size(14.dp))
                        }
                        IconButton(onClick = { play(seg.audioPath, seg.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                "回听",
                                tint = if (seg.audioPath != null) c.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(seg.srcText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (seg.tgtText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            seg.tgtText,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (questions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("课堂问答（${questions.size}）", style = MaterialTheme.typography.titleMedium)
                }
                items(questions) { q ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(q.question, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp))
                        Spacer(Modifier.height(4.dp))
                        Text(q.answer, style = MaterialTheme.typography.bodyMedium)
                        if (q.answerAlt.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(q.answerAlt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (assignCourse) {
        AlertDialog(
            onDismissRequest = { assignCourse = false },
            title = { Text("归入课程") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "选择一门课程；课次会把所属课程的讲义和术语带入助教问答。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { app.db.setSessionCourse(sessionId, null) } }
                                Toast.makeText(context, "已移入「未分类」", Toast.LENGTH_SHORT).show()
                                assignCourse = false
                                onBack()
                            }
                        }.padding(vertical = 12.dp, horizontal = 8.dp)
                    ) { Text("未分类") }
                    courseOptions.forEach { opt ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { app.db.setSessionCourse(sessionId, opt.id) } }
                                    Toast.makeText(context, "已归入「${opt.name}」", Toast.LENGTH_SHORT).show()
                                    assignCourse = false
                                    onBack()
                                }
                            }.padding(vertical = 12.dp, horizontal = 8.dp)
                        ) { Text(opt.name) }
                    }
                    if (courseOptions.isEmpty()) {
                        Text("还没有课程，请先在「课程库」新建。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assignCourse = false }) { Text("取消", color = c.accent) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    titleEdit?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { if (!titleBusy) titleEdit = null },
            title = { Text("修改课次标题") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        enabled = !titleBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (titleBusy) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.accent)
                            Text("正在生成标题…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !titleBusy,
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { app.db.updateSessionTitle(sessionId, text) }
                                session = session?.copy(title = text)
                                titleEdit = null
                                tick++
                            } catch (t: Throwable) {
                                Toast.makeText(context, "保存标题失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text("保存", color = c.accent) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !titleBusy, onClick = { titleEdit = null }) { Text("取消") }
                    if (analysisAvailable(app)) {
                        TextButton(
                            enabled = !titleBusy,
                            onClick = {
                                scope.launch {
                                    titleBusy = true
                                    try {
                                        val t = assistant.titleFor(sessionId)
                                        if (t.isNotBlank()) {
                                            withContext(Dispatchers.IO) { app.db.updateSessionTitle(sessionId, t, "auto") }
                                            session = session?.copy(title = t)
                                            tick++
                                            titleEdit = null
                                        } else {
                                            Toast.makeText(context, "AI 未能生成标题，请稍后再试", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "生成标题失败：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                                    }
                                    titleBusy = false
                                }
                            },
                        ) { Text(if (titleBusy) "生成中…" else "AI 起标题") }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    renameSpeaker?.let { sp ->
        AlertDialog(
            onDismissRequest = { renameSpeaker = null },
            title = { Text("重命名说话人") },
            text = {
                Column {
                    Text("改成「老师」「同学 A」等，本场同一音色会一起更新。", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = speakerName, onValueChange = { speakerName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = speakerName.trim()
                    if (clean.isBlank()) return@TextButton
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { app.db.renameSpeaker(sp.id, clean) }
                            renameSpeaker = null
                            tick++
                        } catch (t: Throwable) {
                            Toast.makeText(context, "重命名失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("保存", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { renameSpeaker = null }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "删除课次",
            message = "删除这次课的字幕、录音、问答和复习题，无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { app.repo.deleteSession(sessionId) }
                        onDeleted()
                    } catch (t: Throwable) {
                        Toast.makeText(context, "删除失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showDelete = false },
        )
    }
}

private fun analysisAvailable(app: App): Boolean {
    val s = app.settings.value
    if (s.analysisMode == com.localinterpreter.tablet.data.SettingsData.MODE_API) {
        val base = s.analysisBaseUrl.ifBlank { s.apiBaseUrl }
        val model = s.analysisModel.ifBlank { s.apiModel }
        return base.isNotBlank() && model.isNotBlank()
    }
    return app.models.assistantReady(s.assistantVariant)
}

private fun formatSeconds(t: Double): String {
    val s = t.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%02d:%02d".format(s / 60, s % 60)
}
