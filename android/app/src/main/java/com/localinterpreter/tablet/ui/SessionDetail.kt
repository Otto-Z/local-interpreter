package com.localinterpreter.tablet.ui

import android.media.MediaPlayer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    var courseOptions by remember { mutableStateOf<List<Course>>(emptyList()) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    val assistant = remember { CourseAssistant(app) }

    fun writeExport(uri: android.net.Uri, data: Pair<String, String>?) {
        if (data == null) return
        // 大课次的导出文件可能几 MB，写 SAF 放到应用级 IO 线程，离开页面也不会中断。
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
            // 数据库读取在 IO 线程完成，Compose 状态统一回到主线程再写。
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
            Toast.makeText(context, "这段没有录音", Toast.LENGTH_SHORT).show()
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
        } catch (e: Exception) {
            Toast.makeText(context, "播放失败", Toast.LENGTH_SHORT).show()
            runCatching { mp.release() }
        }
    }

    val speakersById = speakers.associateBy { it.id }

    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session?.title?.ifBlank { "未命名课次" } ?: "课次",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    MiniButton("改标题", { titleEdit = session?.title ?: "" })
                    Spacer(Modifier.width(6.dp))
                    MiniButton("归入课程", {
                        scope.launch {
                            courseOptions = runCatching {
                                withContext(Dispatchers.IO) { app.db.listCourses() }
                            }.getOrDefault(emptyList())
                            assignCourse = true
                        }
                    })
                    if (titleBusy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Accent)
                }
                Text(
                    (session?.let { "${formatDate(it.startedAt)} · ${formatDuration(it.durationS)}" } ?: "") +
                        " · ${course.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniButton("导出 SRT", {
                val s = session ?: run { Toast.makeText(context, "课次还在读取中，请稍候", Toast.LENGTH_SHORT).show(); return@MiniButton }
                if (segments.isEmpty()) { Toast.makeText(context, "这次课还没有字幕可导出", Toast.LENGTH_SHORT).show(); return@MiniButton }
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { app.repo.sessionSrt(sessionId) } }
                        .onSuccess { content ->
                            val name = s.title.ifBlank { "课次" }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".srt"
                            pendingSrt = name to content
                            exportLauncher.launch(name)
                        }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message?.take(60)}", Toast.LENGTH_LONG).show() }
                }
            })
            Spacer(Modifier.width(8.dp))
            MiniButton("导出 TXT", {
                val s = session ?: run { Toast.makeText(context, "课次还在读取中，请稍候", Toast.LENGTH_SHORT).show(); return@MiniButton }
                if (segments.isEmpty()) { Toast.makeText(context, "这次课还没有字幕可导出", Toast.LENGTH_SHORT).show(); return@MiniButton }
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { app.repo.sessionTxt(sessionId) } }
                        .onSuccess { content ->
                            val name = s.title.ifBlank { "课次" }.replace(Regex("[\\/:*?\"<>|]"), "_") + ".txt"
                            pendingTxt = name to content
                            txtLauncher.launch(name)
                        }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message?.take(60)}", Toast.LENGTH_LONG).show() }
                }
            })
            Spacer(Modifier.width(8.dp))
            MiniButton("导出 Markdown", {
                val s = session ?: run { Toast.makeText(context, "课次还在读取中，请稍候", Toast.LENGTH_SHORT).show(); return@MiniButton }
                if (segments.isEmpty()) { Toast.makeText(context, "这次课还没有字幕可导出", Toast.LENGTH_SHORT).show(); return@MiniButton }
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { app.repo.sessionMarkdown(sessionId) } }
                        .onSuccess { content ->
                            val name = s.title.ifBlank { "课次" }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".md"
                            pendingMd = name to content
                            mdLauncher.launch(name)
                        }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message?.take(60)}", Toast.LENGTH_LONG).show() }
                }
            })
            Spacer(Modifier.width(8.dp))
            MiniButton("导出 JSON", {
                val s = session ?: run { Toast.makeText(context, "课次还在读取中，请稍候", Toast.LENGTH_SHORT).show(); return@MiniButton }
                if (segments.isEmpty()) { Toast.makeText(context, "这次课还没有字幕可导出", Toast.LENGTH_SHORT).show(); return@MiniButton }
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { app.repo.sessionJson(sessionId) } }
                        .onSuccess { content ->
                            val name = s.title.ifBlank { "课次" }.replace(Regex("[\\/:*?\"<>|]"), "_") + ".json"
                            pendingJson = name to content
                            jsonLauncher.launch(name)
                        }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message?.take(60)}", Toast.LENGTH_LONG).show() }
                }
            })
            Spacer(Modifier.width(8.dp))
            MiniButton("删除", {
                if (session?.status == "running") {
                    Toast.makeText(context, "这次课正在进行中，请先在实时页结束同传", Toast.LENGTH_LONG).show()
                } else {
                    showDelete = true
                }
            }, danger = true)
        }
        Spacer(Modifier.height(10.dp))
        if (session?.summary?.isNotBlank() == true) {
            SelectionContainer {
                Text(
                    session?.summary ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        val missingTranslations = segments.count {
            it.tgtText.isBlank() || it.tgtText.startsWith("（翻译失败") || it.tgtText.startsWith("（未翻译")
        }
        if (missingTranslations > 0) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                it.tgtText.isBlank() || it.tgtText.startsWith("（翻译失败") ||
                                    it.tgtText.startsWith("（未翻译")
                            }
                            val target = session?.targetLang ?: "auto"
                            var done = 0
                            retranslateStatus = "0 / ${todo.size}"
                            try {
                                for (seg in todo) {
                                    val liveState = app.live.state.value
                                    if (liveState.running || liveState.starting) {
                                        retranslateStatus = "实时同传已开始，补译已停止（${done}/${todo.size}）"
                                        return@launch
                                    }
                                    val out = try {
                                        app.llm.translate(
                                            text = seg.srcText,
                                            srcLang = seg.srcLang.ifBlank { "auto" },
                                            target = target,
                                            maxTokens = 320,
                                            temperature = 0.2f,
                                        ).trim()
                                    } catch (e: Exception) {
                                        retranslateStatus = "补译失败：${e.message?.take(80) ?: "未知错误"}（${done}/${todo.size}）"
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
                            } finally {
                                retranslating = false
                            }
                        }
                    },
                    accent = true,
                    enabled = !retranslating,
                )
                Text(
                    if (retranslateStatus.isNotBlank()) retranslateStatus else "用当前翻译模型补齐这一课的空白译文；中途开始实时同传会自动停止。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniButton(if (quizBusy) "出题中…" else "生成复习题", {
                if (!quizBusy) {
                    scope.launch {
                        quizBusy = true
                        try {
                            val text = assistant.quiz(sessionId)
                            if (text.isNotBlank()) {
                                quiz = text
                                runCatching {
                                    withContext(Dispatchers.IO) { app.db.saveQuiz(sessionId, text) }
                                }.onFailure { Toast.makeText(context, "复习题保存失败：${it.message?.take(60)}", Toast.LENGTH_LONG).show() }
                            } else {
                                Toast.makeText(
                                    context,
                                    "助教模型未就绪或没有生成内容，请在设置里下载助教模型，或改用 API",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "生成失败：${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                        }
                        quizBusy = false
                    }
                }
            }, accent = true, enabled = !quizBusy)
            if (quiz.isNotBlank()) {
                MiniButton("复制复习题", { copyText(context, "复习题", quiz) })
                MiniButton("清空", {
                    quiz = ""
                    scope.launch { runCatching { withContext(Dispatchers.IO) { app.db.deleteQuiz(sessionId) } } }
                })
            }
            Spacer(Modifier.weight(1f))
            if (speakers.isNotEmpty()) {
                Text("说话人：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                speakers.forEach { s ->
                    Row(
                        Modifier
                            .clickable { renameSpeaker = s; speakerName = s.label }
                            .background(Color(android.graphics.Color.parseColor(s.color)).copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(Color(android.graphics.Color.parseColor(s.color)), RoundedCornerShape(50)))
                        Spacer(Modifier.width(5.dp))
                        Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        if (quiz.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    quiz,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .heightInMax(220.dp)
                        .verticalScrollCompat()
                )
            }
        }
        if (bookmarks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("标记（点击回听）", style = MaterialTheme.typography.labelMedium, color = Accent)
            Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyRowChips(bookmarks, segments) { seg -> play(seg.audioPath, seg.id) }
            }
        }
        Divider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(segments, key = { it.id }) { seg ->
                val speaker = seg.speakerId?.let { speakersById[it] }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        formatSeconds(seg.t0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp).padding(top = 3.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        if (speaker != null) {
                            Text(
                                speaker.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(android.graphics.Color.parseColor(speaker.color)),
                            )
                        }
                        Text(
                            seg.srcText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (seg.tgtText.isNotBlank()) {
                            Text(
                                seg.tgtText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { play(seg.audioPath, seg.id) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            if (playingId == seg.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                            "回听",
                            tint = if (seg.audioPath != null) Accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (questions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("课堂问答", style = MaterialTheme.typography.titleMedium)
                }
                items(questions) { q ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
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
            item { Spacer(Modifier.height(30.dp)) }
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
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { app.db.setSessionCourse(sessionId, null) } }
                                assignCourse = false
                                onBack()
                            }
                        }.padding(vertical = 12.dp, horizontal = 8.dp)
                    ) { Text("未分类") }
                    courseOptions.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { app.db.setSessionCourse(sessionId, c.id) } }
                                    assignCourse = false
                                    onBack()
                                }
                            }.padding(vertical = 12.dp, horizontal = 8.dp)
                        ) { Text(c.name) }
                    }
                    if (courseOptions.isEmpty()) {
                        Text("还没有课程，请先在「课程」页新建。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assignCourse = false }) { Text("取消", color = Accent) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    titleEdit?.let { initial ->
        var text by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { titleEdit = null },
            title = { Text("修改课次标题") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
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
                }) { Text("保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { titleEdit = null }) { Text("取消") }
                if (analysisAvailable(app)) {
                    TextButton(onClick = {
                        scope.launch {
                            titleBusy = true
                            try {
                                val t = assistant.titleFor(sessionId)
                                if (t.isNotBlank()) {
                                    withContext(Dispatchers.IO) { app.db.updateSessionTitle(sessionId, t, "auto") }
                                    session = session?.copy(title = t)
                                    tick++
                                }
                            } catch (_: Exception) {
                            }
                            titleBusy = false
                            titleEdit = null
                        }
                    }) { Text("AI 起标题") }
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
                    Text("改成「老师」「同学 A」等，这段记录里同一音色会一起更新。", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = speakerName, onValueChange = { speakerName = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { app.db.renameSpeaker(sp.id, speakerName.trim()) }
                            renameSpeaker = null
                            tick++
                        } catch (t: Throwable) {
                            Toast.makeText(context, "重命名失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("保存", color = Accent) }
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

@Composable
private fun LazyRowChips(
    bookmarks: List<Bookmark>,
    segments: List<Segment>,
    onPlay: (Segment) -> Unit,
) {
    val segIds = bookmarks.map { it.segmentId }.toSet()
    val marked = segments.filter { it.id in segIds }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(marked, key = { it.id }) { seg ->
            Box(
                Modifier
                    .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                    .clickable { onPlay(seg) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(formatSeconds(seg.t0), style = MaterialTheme.typography.labelSmall, color = Accent)
            }
        }
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

private fun Modifier.heightInMax(max: androidx.compose.ui.unit.Dp): Modifier =
    this.heightIn(max = max)

@Composable
private fun Modifier.verticalScrollCompat(): Modifier =
    this.verticalScroll(rememberScrollState())
