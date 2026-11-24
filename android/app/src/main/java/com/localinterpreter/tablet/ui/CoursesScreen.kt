package com.localinterpreter.tablet.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localinterpreter.tablet.App
import com.localinterpreter.tablet.assistant.CourseAssistant
import com.localinterpreter.tablet.data.Course
import com.localinterpreter.tablet.data.Material
import com.localinterpreter.tablet.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CoursesScreen(
    app: App,
    version: Int,
    onChanged: () -> Unit,
    onStartCourse: (Long) -> Unit,
    onStartNoCourse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailTick by remember { mutableIntStateOf(0) }
    var sessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
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
        BackHandler(enabled = sessionId != null || (!wide && showDetail)) {
            if (sessionId != null) {
                sessionId = null
                detailTick++
                onChanged()
            } else if (!wide && showDetail) {
                selectedId = null
            }
        }
        Row(Modifier.fillMaxSize()) {
            if (wide || !showDetail) {
                CourseListPane(
                    courses = courses,
                    unassignedCount = unassignedCount,
                    selectedId = selectedId,
                    search = search,
                    onSearch = { search = it },
                    onOpenCreate = { showCreateDialog = true },
                    onSelect = { selectedId = it; sessionId = null },
                    modifier = if (wide) Modifier.width(316.dp).fillMaxHeight() else Modifier.fillMaxSize(),
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
                        subtitle = "课程把课次字幕、课件讲义、专业术语表与课堂问答归档在一起；也可以直接去实时页开始一节课。",
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MiniButton("新建课程", { showCreateDialog = true }, accent = true, icon = Icons.Default.Add)
                                MiniButton("直接开始同传", onStartNoCourse, icon = Icons.Default.PlayArrow)
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        fun doCreate() {
            val clean = name.trim()
            if (clean.isNotBlank()) {
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) { app.db.createCourse(clean) }
                        if (notes.trim().isNotBlank()) {
                            withContext(Dispatchers.IO) { app.db.updateCourse(created.id, notes = notes.trim()) }
                        }
                        showCreateDialog = false
                        detailTick++
                        selectedId = created.id
                        onChanged()
                    } catch (t: Throwable) {
                        Toast.makeText(context, "新建课程失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("新建课程") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("课程名称") },
                        placeholder = { Text("例如：机器学习 / 宏观经济学") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { doCreate() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("课程备注（可选）") },
                        placeholder = { Text("授课教授、时间或学习目标…") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.trim().isNotEmpty(), onClick = ::doCreate) {
                    Text("创建", color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
        )
    }

    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名课程") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val course = renaming
                    val name = renameText.trim()
                    if (course != null && name.isNotBlank()) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { app.db.updateCourse(course.id, name = name) }
                                renaming = null
                                detailTick++
                                onChanged()
                            } catch (t: Throwable) {
                                Toast.makeText(context, "重命名失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text("保存", color = c.accent) }
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
                val course = deleting!!
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { app.repo.deleteCourse(course.id) }
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
    onOpenCreate: () -> Unit,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppColors
    val filtered = courses.filter {
        search.isBlank() || it.name.contains(search, ignoreCase = true) || it.notes.contains(search, ignoreCase = true)
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("课程库", style = MaterialTheme.typography.titleLarge)
                Text("${courses.size} 门课程", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MiniButton("新建", onOpenCreate, accent = true, icon = Icons.Default.Add)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            placeholder = { Text("搜索课程 / 笔记", fontSize = 13.sp) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(17.dp)) },
            trailingIcon = if (search.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearch("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "清空", modifier = Modifier.size(15.dp))
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(11.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty() && unassignedCount == 0) {
            EmptyHint(
                title = if (courses.isEmpty()) "还没有课程" else "没有匹配的课程",
                subtitle = "新建课程后，可把讲义、术语表与历次字幕统一管理。",
                icon = Icons.Default.FolderOpen,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "unassigned") {
                    val active = selectedId == -1L
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (active) c.accentSoft else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape)
                            .border(1.dp, if (active) c.accentBorder else MaterialTheme.colorScheme.outlineVariant, shape)
                            .clickable { onSelect(-1) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "未分类课次",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.5.sp),
                                color = if (active) c.accent else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            )
                            Text("暂未归入具体课程的记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "$unassignedCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) c.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                items(filtered, key = { it.id }) { course ->
                    val active = course.id == selectedId
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (active) c.accentSoft else MaterialTheme.colorScheme.background.copy(alpha = 0.55f), shape)
                            .border(1.dp, if (active) c.accentBorder else MaterialTheme.colorScheme.outlineVariant, shape)
                            .clickable { onSelect(course.id) }
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                course.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.5.sp),
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) c.accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (course.kind.isNotBlank()) {
                                Text(course.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "更新于 ${formatDate(course.updatedAt)}" + if (course.notes.isNotBlank()) " · ${course.notes.take(22)}" else "",
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
    val c = AppColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember(course.id) { mutableIntStateOf(0) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var totalSessionCount by remember(course.id) { mutableIntStateOf(0) }
    var materials by remember { mutableStateOf<List<Material>>(emptyList()) }
    var glossary by remember { mutableStateOf<List<com.localinterpreter.tablet.data.GlossaryItem>>(emptyList()) }
    var sessionQuery by remember(course.id) { mutableStateOf("") }
    var notes by remember(course.id) { mutableStateOf(course.notes) }
    var q by remember(course.id) { mutableStateOf("") }
    var answer by remember(course.id) { mutableStateOf("") }
    var asking by remember(course.id) { mutableStateOf(false) }
    var askJob by remember(course.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var outline by remember(course.id) { mutableStateOf("") }
    var outlineBusy by remember(course.id) { mutableStateOf(false) }
    var outlineJob by remember(course.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var glossaryBusy by remember(course.id) { mutableStateOf(false) }
    var term by remember(course.id) { mutableStateOf("") }
    var trans by remember(course.id) { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.localinterpreter.tablet.data.SegmentHit>>(emptyList()) }
    var moreOpen by remember { mutableStateOf(false) }
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
            totalSessionCount = withContext(Dispatchers.IO) { app.db.courseSessionCount(course.id) }
            if (tab == 0) {
                sessions = withContext(Dispatchers.IO) { app.db.listSessions(course.id, sessionQuery) }
                hits = if (sessionQuery.isNotBlank()) {
                    withContext(Dispatchers.IO) { app.db.searchSegments(course.id, sessionQuery, 20) }
                } else emptyList()
            } else if (tab == 1) {
                materials = withContext(Dispatchers.IO) { app.db.listMaterials(course.id) }
            } else if (tab == 2) {
                glossary = withContext(Dispatchers.IO) { app.db.listGlossary(course.id) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    fun submitCourseQuestion() {
        if (q.isNotBlank() && !asking) {
            askJob = scope.launch {
                asking = true
                answer = ""
                try {
                    val answerBuf = StringBuilder()
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val finalAnswer = assistant.askCourse(course.id, q) { piece ->
                        answerBuf.append(piece)
                        val snapshot = answerBuf.toString()
                        mainHandler.post { if (asking) answer = snapshot }
                    }
                    if (finalAnswer.isNotBlank()) answer = finalAnswer
                    if (answer.isBlank()) answer = "助教模型未就绪。请在设置 → 翻译与助教中下载助教模型或改用 API。"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    answer = "失败：${e.message?.take(100) ?: ""}"
                } finally {
                    asking = false
                }
            }
        }
    }

    Column(modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
        // 顶部精简标题栏：主操作 CTA + 预习 + 更多菜单
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!wide) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(course.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (course.kind.isNotBlank()) {
                        Text(
                            course.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.accent,
                            modifier = Modifier
                                .background(c.accentSoft, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    "$totalSessionCount 个课次 · 更新于 ${formatDate(course.updatedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniButton("课前预习", {
                if (!outlineBusy) {
                    outlineJob = scope.launch {
                        outlineBusy = true
                        outline = ""
                        try {
                            outline = assistant.outline(course.id)
                            if (outline.isBlank()) {
                                outline = "助教模型未就绪或没有生成内容。请在设置中下载助教模型或改用 API。"
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            outline = "失败：${e.message?.take(100) ?: ""}"
                        } finally {
                            outlineBusy = false
                        }
                    }
                }
            }, enabled = !outlineBusy, icon = Icons.Default.AutoAwesome)
            PrimaryButton("开始上课", onStartCourse, icon = Icons.Default.PlayArrow)
            Box {
                IconActionChip(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "更多课程操作",
                    onClick = { moreOpen = true },
                )
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("导出整门课程 (.md)") },
                        onClick = {
                            moreOpen = false
                            scope.launch {
                                try {
                                    val content = withContext(Dispatchers.IO) { app.repo.courseMarkdown(course.id) }
                                    val name = sanitizeFilename(course.name, "课程") + ".md"
                                    pendingExport = name to content
                                    exportLauncher.launch(name)
                                } catch (t: Throwable) {
                                    Toast.makeText(context, "导出失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                    DropdownMenuItem(text = { Text("重命名课程") }, onClick = { moreOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("刷新数据") }, onClick = { moreOpen = false; onRefresh() })
                    DropdownMenuItem(
                        text = { Text("删除课程", color = c.danger) },
                        onClick = { moreOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // 跨课次课程提问框
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                placeholder = { Text("向这门课提问，例如：前几节课讲了哪些核心模型？", fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(17.dp)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitCourseQuestion() }),
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(11.dp),
            )
            MiniButton(if (asking) "思考中…" else "提问", ::submitCourseQuestion, accent = true, enabled = q.isNotBlank() && !asking)
        }

        if (answer.isNotBlank() || asking) {
            Spacer(Modifier.height(10.dp))
            SectionCard(title = "课程问答", trailing = {
                MiniButton("关闭", {
                    askJob?.cancel()
                    askJob = null
                    if (asking) app.llm.cancelAssistant()
                    asking = false
                    answer = ""
                })
            }) {
                if (asking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.8.dp, color = c.accent)
                SelectionContainer {
                    Text(
                        answer.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("AI 生成，仅供参考", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (outline.isNotBlank() || outlineBusy) {
            Spacer(Modifier.height(10.dp))
            SectionCard(title = "课前预习大纲", trailing = {
                MiniButton("关闭", {
                    outlineJob?.cancel()
                    outlineJob = null
                    if (outlineBusy) app.llm.cancelAssistant()
                    outlineBusy = false
                    outline = ""
                })
            }) {
                if (outlineBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.8.dp, color = c.accent)
                SelectionContainer {
                    Text(
                        outline.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CourseTabChip("课次", Icons.Default.History, tab == 0) { tab = 0 }
            CourseTabChip("讲义资料", Icons.Default.Description, tab == 1) { tab = 1 }
            CourseTabChip("术语表", Icons.Default.Spellcheck, tab == 2) { tab = 2 }
            CourseTabChip("课程笔记", Icons.Default.EditNote, tab == 3) { tab = 3 }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

        when (tab) {
            0 -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = sessionQuery,
                        onValueChange = { sessionQuery = it },
                        placeholder = { Text("搜索课次标题 / 摘要 / 字幕原话", fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(17.dp)) },
                        trailingIcon = if (sessionQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { sessionQuery = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, "清空", modifier = Modifier.size(15.dp))
                                }
                            }
                        } else null,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(11.dp),
                    )
                    Text("${sessions.size} 个课次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                if (sessionQuery.isNotBlank() && hits.isNotEmpty()) {
                    Text("原话命中", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.layoutHeightIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(hits) { hit ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.accentSoft.copy(alpha = 0.45f))
                                    .clickable { onOpenSession(hit.segment.sessionId) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(formatDate(hit.sessionStartedAt), style = MaterialTheme.typography.labelSmall, color = c.accent)
                                Spacer(Modifier.width(8.dp))
                                Text(hit.segment.srcText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(s, onClick = { onOpenSession(s.id) })
                    }
                    if (sessions.isEmpty()) {
                        item {
                            EmptyHint(
                                title = "还没有课次记录",
                                subtitle = "点击右上角「开始上课」，实时同传结束后会自动归档到这里。",
                                icon = Icons.Default.History,
                            )
                        }
                    }
                }
            }
            1 -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("课件讲义（${materials.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (importing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.6.dp, color = c.accent)
                    MiniButton("导入 PDF / DOCX / PPTX / TXT", onImportMaterial, accent = true, enabled = !importing, icon = Icons.Default.UploadFile)
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(materials, key = { it.id }) { m ->
                        var chunks by remember(m.id) { mutableIntStateOf(-1) }
                        LaunchedEffect(m.id) {
                            chunks = runCatching {
                                withContext(Dispatchers.IO) { app.db.materialChunkCount(m.id) }
                            }.getOrDefault(0)
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Description, null, tint = c.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${if (chunks >= 0) "$chunks 个检索片段" else "解析中…"} · ${formatDate(m.createdAt)}",
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
                            }) { Icon(Icons.Default.Delete, "删除", tint = c.danger, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    if (materials.isEmpty()) item {
                        EmptyHint(
                            "还没有导入讲义",
                            "导入课件后，课堂助教回答问题会优先检索课件内容并标注页码。",
                            icon = Icons.Default.Description,
                        )
                    }
                }
            }
            2 -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = term,
                        onValueChange = { term = it },
                        placeholder = { Text("原文术语", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(11.dp),
                    )
                    OutlinedTextField(
                        value = trans,
                        onValueChange = { trans = it },
                        placeholder = { Text("指定译名 / 说明", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1.25f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(11.dp),
                    )
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
                    MiniButton(if (glossaryBusy) "提取中…" else "AI 提取", {
                        if (!glossaryBusy) {
                            scope.launch {
                                glossaryBusy = true
                                try {
                                    val pairs = assistant.extractGlossary(course.id)
                                    val existing = glossary.map { it.term }.toSet()
                                    val added = pairs.filter { it.first !in existing }
                                    added.forEach {
                                        withContext(Dispatchers.IO) { app.db.addGlossary(course.id, it.first, it.second) }
                                    }
                                    glossary = withContext(Dispatchers.IO) { app.db.listGlossary(course.id) }
                                    val msg = when {
                                        pairs.isEmpty() -> "未提取到术语，请确认助教模型已就绪或先导入讲义/记录课次"
                                        added.isEmpty() -> "识别到 ${pairs.size} 个术语，均已在术语表中"
                                        else -> "新增 ${added.size} 个术语（共识别 ${pairs.size} 个）"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "提取失败：${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                                } finally {
                                    glossaryBusy = false
                                }
                            }
                        }
                    }, enabled = !glossaryBusy, icon = Icons.Default.AutoAwesome)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "术语会自动注入该课程的实时翻译提示词，确保人名、模型名与专业名词译法一致。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(glossary, key = { it.id }) { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(11.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.term, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.accent)
                            Text("  →  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(g.translation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { app.db.deleteGlossary(g.id) }
                                            glossary = withContext(Dispatchers.IO) { app.db.listGlossary(course.id) }
                                        } catch (t: Throwable) {
                                            Toast.makeText(context, "删除术语失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.size(30.dp),
                            ) { Icon(Icons.Default.Delete, "删除", tint = c.danger, modifier = Modifier.size(17.dp)) }
                        }
                    }
                    if (glossary.isEmpty()) item {
                        EmptyHint("术语表为空", "手动添加，或点「AI 提取」从讲义和历史课次中自动抽取。", icon = Icons.Default.Spellcheck)
                    }
                }
            }
            else -> Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("记录课程大纲、学习进度、作业与待办…") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("保存课程笔记", {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { app.db.updateCourse(course.id, notes = notes) }
                                Toast.makeText(context, "笔记已保存", Toast.LENGTH_SHORT).show()
                                onRefresh()
                            } catch (t: Throwable) {
                                Toast.makeText(context, "保存失败：${t.message?.take(60)}", Toast.LENGTH_LONG).show()
                            }
                        }
                    })
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
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(tick, query) {
        try {
            sessions = withContext(Dispatchers.IO) { app.db.listUnassignedSessions(query) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    Column(modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!wide) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
            Column(Modifier.weight(1f)) {
                Text("未分类课次", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "进入课次详情后，可随时将其归入指定课程。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniButton("刷新", onRefresh)
            PrimaryButton("开始一节课", onStart, icon = Icons.Default.PlayArrow)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索标题 / 摘要", fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(17.dp)) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "清空", modifier = Modifier.size(15.dp))
                        }
                    }
                } else null,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(11.dp),
            )
            Text("${sessions.size} 个课次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                SessionRow(s, onClick = { onOpenSession(s.id) })
            }
            if (sessions.isEmpty()) {
                item {
                    EmptyHint(
                        title = if (query.isBlank()) "还没有未分类课次" else "没有匹配的课次",
                        subtitle = "在实时同传页未选择课程时开始的记录会保存在这里。",
                        icon = Icons.Default.History,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, onClick: () -> Unit) {
    val c = AppColors
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.title.ifBlank { "未命名课次" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (s.status == "running") {
                    Text(
                        "进行中",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accent,
                        modifier = Modifier
                            .background(c.accentSoft, RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatDate(s.startedAt)} · 时长 ${formatDuration(s.durationS)}" +
                    if (s.summary.isNotBlank()) " · ${s.summary.replace("\n", " ").take(64)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CourseTabChip(text: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val c = AppColors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent, shape)
            .then(if (active) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (active) c.accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
