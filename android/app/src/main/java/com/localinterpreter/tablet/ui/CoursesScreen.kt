package com.localinterpreter.tablet.ui

import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn as layoutHeightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.assistant.CourseAssistant
import com.localinterpreter.tablet.data.Course
import com.localinterpreter.tablet.data.Material
import com.localinterpreter.tablet.data.Segment
import com.localinterpreter.tablet.data.Session
import com.localinterpreter.tablet.data.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CoursesScreen(
    app: App,
    version: Int,
    onChanged: () -> Unit,
    onStartCourse: (Long) -> Unit,
    onStartNoCourse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailTick by remember { mutableIntStateOf(0) }
    var sessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Course?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Course?>(null) }
    var importCourseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var materialImporting by remember { mutableStateOf(false) }
    var unassignedCount by remember { mutableIntStateOf(0) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val courseId = importCourseId
        if (uri != null && courseId != null) {
            materialImporting = true
            // 导入放到应用级 IO 作用域：切到「实时」页或旋屏不会把导入变成“无提示失败”。
            app.appScope.launch {
                val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
                val result = app.repo.importMaterial(courseId, uri, name)
                withContext(Dispatchers.Main) {
                    materialImporting = false
                    if (result.isSuccess) {
                        Toast.makeText(context, "已导入 $name", Toast.LENGTH_SHORT).show()
                        detailTick++
                    } else {
                        Toast.makeText(context, result.exceptionOrNull()?.message ?: "导入失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        importCourseId = null
    }

    LaunchedEffect(version, detailTick) {
        val loaded = try {
            withContext(Dispatchers.IO) { app.db.listCourses() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Toast.makeText(context, "读取课程失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
            emptyList()
        }
        courses = loaded
        unassignedCount = try {
            withContext(Dispatchers.IO) { app.db.unassignedSessionCount() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            0
        }
        if (selectedId != null && selectedId != -1L && loaded.none { it.id == selectedId }) selectedId = null
    }

    val selected = courses.firstOrNull { it.id == selectedId }
    val unassigned = selectedId == -1L
    val showDetail = selected != null || unassigned

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        Row(Modifier.fillMaxSize()) {
            if (wide || !showDetail) {
                CourseListPane(
                    courses = courses,
                    unassignedCount = unassignedCount,
                    selectedId = selectedId,
                    search = search,
                    onSearch = { search = it },
                    newName = newName,
                    onNewName = { newName = it },
                    onCreate = {
                        val name = newName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                try {
                                    val c = withContext(Dispatchers.IO) { app.db.createCourse(name) }
                                    newName = ""
                                    detailTick++
                                    selectedId = c.id
                                    onChanged()
                                } catch (t: Throwable) {
                                    Toast.makeText(context, "新建课程失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onSelect = { selectedId = it; sessionId = null },
                    modifier = if (wide) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize(),
                )
                if (wide) VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (wide || showDetail) {
                val virtualCourse = Course(-1, "未分类", "", "", 0, 0)
                if (unassigned && sessionId == null) {
                    UnassignedPane(
                        app = app,
                        tick = detailTick,
                        wide = wide,
                        onBack = { selectedId = null },
                        onOpenSession = { sessionId = it },
                        onStart = onStartNoCourse,
                        onRefresh = { detailTick++; onChanged() },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                } else if (sessionId != null) {
                    androidx.compose.runtime.key(sessionId) {
                        SessionDetailPane(
                            app = app,
                            course = selected ?: virtualCourse,
                            sessionId = sessionId!!,
                            onBack = { sessionId = null; detailTick++; onChanged() },
                            onDeleted = { sessionId = null; detailTick++; onChanged() },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                } else if (selected == null) {
                    EmptyHint(
                        title = "新建或选择一门课程",
                        subtitle = "课程把课次、讲义、术语表和问答组织在一起；也可以直接开始一节课，之后再归档。",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    androidx.compose.runtime.key(selected.id) {
                        CourseDetailPane(
                            app = app,
                            course = selected,
                            tick = detailTick,
                            onRefresh = { detailTick++; onChanged() },
                            onBack = { selectedId = null },
                            wide = wide,
                            onOpenSession = { sessionId = it },
                            onStartCourse = { onStartCourse(selected.id) },
                            onRename = { renaming = selected; renameText = selected.name },
                            onDelete = {
                                if (materialImporting) {
                                    Toast.makeText(context, "正在导入资料，请稍后再删除课程", Toast.LENGTH_SHORT).show()
                                } else {
                                    deleting = selected
                                }
                            },
                            onImportMaterial = {
                                importCourseId = selected.id
                                importLauncher.launch(arrayOf(
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "text/plain",
                                    "text/markdown",
                                    "application/octet-stream",
                                ))
                            },
                            importing = materialImporting,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名课程") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = renaming
                    val name = renameText.trim()
                    if (c != null && name.isNotBlank()) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { app.db.updateCourse(c.id, name = name) }
                                renaming = null
                                detailTick++
                                onChanged()
                            } catch (t: Throwable) {
                                Toast.makeText(context, "重命名失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text("保存", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    if (deleting != null) {
        ConfirmDialog(
            title = "删除课程",
            message = "删除「${deleting!!.name}」？课次会移到「未分类」；课程资料和术语表会一并删除。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                val c = deleting!!
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { app.repo.deleteCourse(c.id) }
                        selectedId = null
                        detailTick++
                        onChanged()
                    } catch (t: Throwable) {
                        Toast.makeText(context, "删除失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun CourseListPane(
    courses: List<Course>,
    unassignedCount: Int,
    selectedId: Long?,
    search: String,
    onSearch: (String) -> Unit,
    newName: String,
    onNewName: (String) -> Unit,
    onCreate: () -> Unit,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = courses.filter {
        search.isBlank() || it.name.contains(search, ignoreCase = true) || it.notes.contains(search, ignoreCase = true)
    }
    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("课程", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Text("${courses.size} 门", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            placeholder = { Text("搜索课程 / 笔记", fontSize = 13.sp) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = onNewName,
                placeholder = { Text("新课程名称", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            MiniButton("新建", onCreate, accent = true, enabled = newName.isNotBlank())
        }
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty() && unassignedCount == 0) {
            Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (courses.isEmpty()) "还没有课程" else "没有匹配的课程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "新建课程后，可以把课次、讲义和术语归档到一起。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "unassigned") {
                    val active = selectedId == -1L
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (active) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                RoundedCornerShape(11.dp)
                            )
                            .border(1.dp, if (active) Accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))
                            .clickable { onSelect(-1) }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "未分类课次",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text("$unassignedCount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "没有归到课程里的课次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(filtered, key = { it.id }) { c ->
                    val active = c.id == selectedId
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (active) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(11.dp)
                            )
                            .border(1.dp, if (active) Accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))
                            .clickable { onSelect(c.id) }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (active) Accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (c.kind.isNotBlank()) {
                                Text(c.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "更新于 ${formatDate(c.updatedAt)}" + if (c.notes.isNotBlank()) " · ${c.notes.take(24)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseDetailPane(
    app: App,
    course: Course,
    tick: Int,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    wide: Boolean,
    onOpenSession: (Long) -> Unit,
    onStartCourse: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onImportMaterial: () -> Unit,
    importing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember(course.id) { mutableIntStateOf(0) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var materials by remember { mutableStateOf<List<Material>>(emptyList()) }
    var glossary by remember { mutableStateOf<List<com.localinterpreter.tablet.data.GlossaryItem>>(emptyList()) }
    var sessionQuery by remember(course.id) { mutableStateOf("") }
    var notes by remember(course.id) { mutableStateOf(course.notes) }
    var q by remember(course.id) { mutableStateOf("") }
    var answer by remember(course.id) { mutableStateOf("") }
    var asking by remember(course.id) { mutableStateOf(false) }
    var outline by remember(course.id) { mutableStateOf("") }
    var outlineBusy by remember(course.id) { mutableStateOf(false) }
    var glossaryBusy by remember(course.id) { mutableStateOf(false) }
    var term by remember(course.id) { mutableStateOf("") }
    var trans by remember(course.id) { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.localinterpreter.tablet.data.SegmentHit>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
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
    val assistant = remember { CourseAssistant(app) }

    LaunchedEffect(tick, course.id, tab, sessionQuery) {
        try {
            if (tab == 0) {
                sessions = withContext(Dispatchers.IO) { app.db.listSessions(course.id, sessionQuery) }
                hits = if (sessionQuery.isNotBlank()) {
                    withContext(Dispatchers.IO) { app.db.searchSegments(course.id, sessionQuery, 20) }
                } else {
                    emptyList()
                }
            } else if (tab == 1) {
                materials = withContext(Dispatchers.IO) { app.db.listMaterials(course.id) }
            } else if (tab == 2) {
                glossary = withContext(Dispatchers.IO) { app.db.listGlossary(course.id) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 数据库瞬时错误时保留上一份列表，避免把界面清空成“没有课次/资料”。
        }
    }

    Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!wide) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(course.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (course.kind.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            course.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            modifier = Modifier
                                .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    "${sessions.size} 个课次 · 更新于 ${formatDate(course.updatedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniButton("开始一节课", { onStartCourse() }, accent = true)
            Spacer(Modifier.width(8.dp))
            MiniButton("重命名", onRename)
            Spacer(Modifier.width(8.dp))
            MiniButton("删除", onDelete, danger = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                placeholder = { Text("问这门课，例如：前几节课讲了哪些模型？", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            MiniButton(if (asking) "思考中…" else "提问", {
                if (q.isNotBlank() && !asking) {
                    scope.launch {
                        asking = true
                        answer = ""
                        try {
                            val answerBuf = StringBuilder()
                            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            val finalAnswer = assistant.askCourse(course.id, q) { piece ->
                                // 模型回调在 IO 线程，Compose 状态只能在主线程改。
                                answerBuf.append(piece)
                                val snapshot = answerBuf.toString()
                                mainHandler.post { if (asking) answer = snapshot }
                            }
                            if (finalAnswer.isNotBlank()) answer = finalAnswer
                            if (answer.isBlank()) answer = "助教模型未就绪。请在设置里下载助教模型，或把分析模型改成 API。"
                        } catch (e: Exception) {
                            answer = "失败：${e.message?.take(100) ?: ""}"
                        }
                        asking = false
                    }
                }
            }, accent = true, enabled = q.isNotBlank() && !asking)
            Spacer(Modifier.width(8.dp))
            MiniButton("课前预习", {
                if (!outlineBusy) {
                    scope.launch {
                        outlineBusy = true
                        outline = ""
                        try {
                            outline = assistant.outline(course.id)
                            if (outline.isBlank()) {
                                outline = "助教模型未就绪或没有生成内容。请在设置 → 课堂助教里下载助教模型，或改用 API。"
                            }
                        } catch (e: Exception) {
                            outline = "失败：${e.message?.take(100) ?: ""}"
                        }
                        outlineBusy = false
                    }
                }
            }, enabled = !outlineBusy)
            Spacer(Modifier.width(8.dp))
            MiniButton("导出课程", {
                scope.launch {
                    try {
                        val content = withContext(Dispatchers.IO) { app.repo.courseMarkdown(course.id) }
                        val name = course.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".md"
                        pendingExport = name to content
                        exportLauncher.launch(name)
                    } catch (t: Throwable) {
                        Toast.makeText(context, "导出失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                    }
                }
            })
            Spacer(Modifier.width(8.dp))
            MiniButton("刷新", { onRefresh() })
        }
        if (answer.isNotBlank() || asking) {
            Spacer(Modifier.height(10.dp))
            SectionCard(title = "课程问答", trailing = { MiniButton("关闭", { answer = "" }) }) {
                if (asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                SelectionContainer {
                    Text(
                        answer.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("AI 生成，仅供参考", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (outline.isNotBlank() || outlineBusy) {
            Spacer(Modifier.height(10.dp))
            SectionCard(title = "课前预习", trailing = { MiniButton("关闭", { outline = "" }) }) {
                if (outlineBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                SelectionContainer {
                    Text(
                        outline.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChipSmall("课次", tab == 0) { tab = 0 }
            TabChipSmall("资料", tab == 1) { tab = 1 }
            TabChipSmall("术语", tab == 2) { tab = 2 }
            TabChipSmall("笔记", tab == 3) { tab = 3 }
        }
        Divider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

        when (tab) {
            0 -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = sessionQuery,
                        onValueChange = { sessionQuery = it },
                        placeholder = { Text("搜索课次标题 / 摘要 / 原话", fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${sessions.size} 个课次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (sessionQuery.isNotBlank() && hits.isNotEmpty()) {
                    Text("原话命中", style = MaterialTheme.typography.labelMedium, color = Accent)
                    LazyColumn(Modifier.layoutHeightIn(max = 160.dp)) {
                        items(hits) { hit ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenSession(hit.segment.sessionId) }.padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(formatDate(hit.sessionStartedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(hit.segment.srcText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(s, onClick = { onOpenSession(s.id) })
                    }
                    if (sessions.isEmpty()) {
                        item {
                            EmptyHint(
                                title = "还没有课次",
                                subtitle = "点上方的「开始一节课」，实时字幕结束后会自动归档到这里。",
                            )
                        }
                    }
                }
            }
            1 -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("课程资料", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (importing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.6.dp, color = Accent)
                    Spacer(Modifier.width(8.dp))
                    MiniButton("上传 PDF / DOCX / PPTX / TXT", onImportMaterial, accent = true, enabled = !importing)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(materials, key = { it.id }) { m ->
                        var chunks by remember(m.id) { mutableIntStateOf(-1) }
                        LaunchedEffect(m.id) {
                            chunks = runCatching {
                                withContext(Dispatchers.IO) { app.db.materialChunkCount(m.id) }
                            }.getOrDefault(0)
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    m.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${if (chunks >= 0) "$chunks 个片段" else "解析中"} · ${formatDate(m.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { app.repo.deleteMaterial(m.id) }
                                        materials = withContext(Dispatchers.IO) { app.db.listMaterials(course.id) }
                                    } catch (t: Throwable) {
                                        Toast.makeText(context, "删除资料失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Icon(Icons.Default.Delete, "删除", tint = Danger) }
                        }
                    }
                    if (materials.isEmpty()) item {
                        EmptyHint("还没有资料", "上传讲义后，助教回答会优先引用课件并标注页码。扫描版 PDF 暂不支持文字提取。")
                    }
                }
            }
            2 -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = term,
                        onValueChange = { term = it },
                        placeholder = { Text("术语", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = trans,
                        onValueChange = { trans = it },
                        placeholder = { Text("译名 / 说明", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    MiniButton("添加", {
                        if (term.isNotBlank() && trans.isNotBlank()) {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { app.db.addGlossary(course.id, term, trans) }
                                    term = ""
                                    trans = ""
                                    glossary = withContext(Dispatchers.IO) { app.db.listGlossary(course.id) }
                                } catch (t: Throwable) {
                                    Toast.makeText(context, "添加术语失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }, accent = true, enabled = term.isNotBlank() && trans.isNotBlank())
                    Spacer(Modifier.width(8.dp))
                    MiniButton(if (glossaryBusy) "提取中…" else "AI 自动提取", {
                        if (!glossaryBusy) {
                            scope.launch {
                                glossaryBusy = true
                                try {
                                    val pairs = assistant.extractGlossary(course.id)
                                    val existing = glossary.map { it.term }.toSet()
                                    pairs.filter { it.first !in existing }.forEach {
                                        withContext(Dispatchers.IO) { app.db.addGlossary(course.id, it.first, it.second) }
                                    }
                                    glossary = withContext(Dispatchers.IO) { app.db.listGlossary(course.id) }
                                    Toast.makeText(context, "提取了 ${pairs.size} 个术语", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "提取失败：${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                                }
                                glossaryBusy = false
                            }
                        }
                    }, enabled = !glossaryBusy)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "术语会自动用于该课程的翻译提示，保持人名、产品名和专业词一致。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(glossary, key = { it.id }) { g ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.term, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("  →  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(g.translation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { app.db.deleteGlossary(g.id) }
                                        glossary = withContext(Dispatchers.IO) { app.db.listGlossary(course.id) }
                                    } catch (t: Throwable) {
                                        Toast.makeText(context, "删除术语失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Icon(Icons.Default.Delete, "删除", tint = Danger, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    if (glossary.isEmpty()) item {
                        EmptyHint("术语表为空", "手动添加，或点「AI 自动提取」从讲义和课堂记录里生成。")
                    }
                }
            }
            else -> Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("课程目标、进度、待办…") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    MiniButton("保存笔记", {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { app.db.updateCourse(course.id, notes = notes) }
                                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                onRefresh()
                            } catch (t: Throwable) {
                                Toast.makeText(context, "保存失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }, accent = true)
                }
            }
        }
    }
}


@Composable
private fun UnassignedPane(
    app: App,
    tick: Int,
    wide: Boolean,
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onStart: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(tick, query) {
        try {
            sessions = withContext(Dispatchers.IO) { app.db.listUnassignedSessions(query) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 保留上一份列表，不要因为一次数据库错误把页面清空或崩溃。
        }
    }

    Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!wide) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            }
            Column(Modifier.weight(1f)) {
                Text("未分类课次", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "没有归到课程里的记录。可在实时页底栏选择课程，或在课程里整理。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniButton("开始一节课", onStart, accent = true)
            Spacer(Modifier.width(8.dp))
            MiniButton("刷新", onRefresh)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索标题 / 摘要", fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("${sessions.size} 个课次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                SessionRow(s, onClick = { onOpenSession(s.id) })
            }
            if (sessions.isEmpty()) {
                item {
                    EmptyHint(
                        title = if (query.isBlank()) "还没有未分类课次" else "没有匹配的课次",
                        subtitle = "在实时页底栏不选课程直接开始，记录会出现在这里。",
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.title.ifBlank { "未命名课次" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDate(s.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "${formatDuration(s.durationS)} · ${if (s.status == "running") "进行中" else "已归档"}" +
                if (s.summary.isNotBlank()) " · ${s.summary.take(60)}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TabChipSmall(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun Modifier.heightIn(max: androidx.compose.ui.unit.Dp): Modifier =
    this.layoutHeightIn(max = max)

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "资料" else "资料"
        } ?: "资料"
    } catch (_: Exception) {
        "资料"
    }
}
