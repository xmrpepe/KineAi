package com.kineai.local.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineai.local.KineApp
import com.kineai.local.terminal.StyledLine
import com.kineai.local.workspace.GitStatus
import com.kineai.local.workspace.TerminalSession
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun WorkspaceScreen(app: KineApp) {
    val project by app.selectedProject.collectAsState()
    if (project == null) ProjectList(app) else ProjectView(app, project!!)
}

@Composable
private fun ProjectList(app: KineApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(app.projects.list()) }
    var newName by remember { mutableStateOf<String?>(null) }
    var cloneUrl by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { app.projects.importTree(ctx.contentResolver, ctx, uri) { message = "Copying $it…" } }
                .onSuccess { message = "Imported into ${it.name}"; projects = app.projects.list() }.onFailure { message = it.message }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Projects", style = MaterialTheme.typography.titleLarge)
            Text("Projects live in app storage, separate from models and chats. Folders you import are copied; the app only sees the folder you choose.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button({ newName = "" }) { Text("New") }
                OutlinedButton({ pick.launch(null) }) { Text("Import folder") }
                OutlinedButton({ cloneUrl = "" }) { Text("Clone (network)") }
            }
            message?.let { Text(it, color = Warn) }
        }
        items(projects, key = { it.name }) { p -> Card(Modifier.fillMaxWidth().clickable { app.selectedProject.value = p }) { Text(p.name, Modifier.padding(14.dp), style = MaterialTheme.typography.titleMedium) } }
    }
    newName?.let { n -> AlertDialog({ newName = null }, title = { Text("New project") }, text = { OutlinedTextField(n, { newName = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ app.projects.create(n).also { projects = app.projects.list() }; newName = null }) { Text("Create") } }, dismissButton = { TextButton({ newName = null }) { Text("Cancel") } }) }
    cloneUrl?.let { u -> AlertDialog({ cloneUrl = null }, title = { Text("Clone a repository") }, text = { Column { OutlinedTextField(u, { cloneUrl = it }, singleLine = true, label = { Text("https:// URL") }); Text("This is the one Git action that uses the network, and only because you asked.", style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton({ val url = u; cloneUrl = null; scope.launch { message = "Cloning…"; runCatching { app.git.clone(url, app.projects.create(url.trimEnd('/').substringAfterLast('/').removeSuffix(".git"))) }.onSuccess { message = "Cloned"; projects = app.projects.list() }.onFailure { message = "Clone failed: ${it.message}" } } }) { Text("Clone") } }, dismissButton = { TextButton({ cloneUrl = null }) { Text("Cancel") } }) }
}

@Composable
private fun ProjectView(app: KineApp, project: File) {
    var tab by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton({ app.selectedProject.value = null }) { Text("← Projects") }
            Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ confirmDelete = true }) { Text("Delete project") }
        }
        TabRow(tab) { listOf("Files", "Git", "Terminal").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
        when (tab) { 0 -> FilesTab(app, project); 1 -> GitTab(app, project); else -> TerminalTab(project) }
    }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Delete ${project.name}?") }, text = { Text("This deletes the project folder from the phone. Models and chats are not affected.") },
        confirmButton = { TextButton({ app.projects.delete(project); app.selectedProject.value = null; confirmDelete = false }) { Text("Delete") } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun FilesTab(app: KineApp, project: File) {
    var dir by remember(project) { mutableStateOf(project) }
    var open by remember(project) { mutableStateOf<File?>(null) }
    val landscape = LocalConfiguration.current.screenWidthDp >= 700
    val tree: @Composable (Modifier) -> Unit = { mod ->
        LazyColumn(mod) {
            if (dir != project) item { TextButton({ dir = dir.parentFile ?: project }) { Text("⬆ ..") } }
            items(app.projects.entries(dir), key = { it.file.path }) { e ->
                Text((if (e.isDir) "📁 " else "📄 ") + e.file.name, Modifier.fillMaxWidth().clickable { if (e.isDir) dir = e.file else open = e.file }.padding(12.dp), maxLines = 1)
            }
        }
    }
    val viewer: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            val f = open
            if (f == null) Text("Select a file to read it.", Modifier.padding(16.dp)) else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.relativeTo(project).path, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.labelLarge)
                    TextButton({ app.attached.value = (app.attached.value + f.relativeTo(project).path).distinct() }) { Text("Attach to chat") }
                }
                val text = remember(f) { app.projects.readText(f) }
                text.onSuccess { CodeBlock(it, Modifier.fillMaxSize()) }.onFailure { Text(it.message ?: "Cannot show this file", Modifier.padding(16.dp)) }
            }
        }
    }
    if (landscape) Row(Modifier.fillMaxSize()) { tree(Modifier.width(300.dp).fillMaxHeight()); viewer(Modifier.weight(1f).fillMaxHeight()) }
    else if (open == null) tree(Modifier.fillMaxSize())
    else Column { TextButton({ open = null }) { Text("← Files") }; viewer(Modifier.weight(1f)) }
}

@Composable
private fun GitTab(app: KineApp, project: File) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var output by remember { mutableStateOf("") }
    var isDiff by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val repo = app.git.isRepo(project)
    fun refresh() { scope.launch { runCatching { if (app.git.isRepo(project)) status = app.git.status(project) }.onFailure { error = it.message } } }
    LaunchedEffect(project) { refresh() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!repo) { Text("This folder is not a Git repository."); Button({ scope.launch { runCatching { app.git.init(project) }.onFailure { error = it.message }; refresh() } }) { Text("git init") } }
        else {
            status?.let { s -> Text("Branch: ${s.branch ?: "?"}${if (s.clean) " · clean" else ""}", style = MaterialTheme.typography.titleSmall)
                listOf("Staged" to s.staged, "Modified" to s.modified, "Untracked" to s.untracked, "Missing" to s.missing, "Conflicting" to s.conflicting).filter { it.second.isNotEmpty() }.forEach { (l, v) -> Text("$l: ${v.joinToString()}", style = MaterialTheme.typography.bodySmall) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ refresh() }) { Text("Refresh") }
                OutlinedButton({ scope.launch { isDiff = true; output = runCatching { app.git.diff(project, false) }.getOrElse { it.message ?: "diff failed" } } }) { Text("Diff") }
                OutlinedButton({ scope.launch { isDiff = true; output = runCatching { app.git.diff(project, true) }.getOrElse { it.message ?: "diff failed" } } }) { Text("Staged") }
                OutlinedButton({ scope.launch { isDiff = false; output = runCatching { app.git.log(project).joinToString("\n") { "${it.id}  ${it.message}  (${it.author})" }.ifEmpty { "No commits yet" } }.getOrElse { it.message ?: "log failed" } } }) { Text("Log") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(message, { message = it }, Modifier.weight(1f), singleLine = true, label = { Text("Commit message") })
                Button({ scope.launch { runCatching { app.git.stageAll(project); app.git.commit(project, message.ifBlank { "Update" }, "KineAi", "kineai@localhost") }.onSuccess { message = ""; error = null }.onFailure { error = it.message }; refresh() } }, enabled = status?.clean == false) { Text("Stage all + commit") }
            }
            error?.let { Text(it, color = Bad) }
            if (isDiff) GitDiffText(output, Modifier.weight(1f).fillMaxWidth()) else Text(output, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TerminalTab(project: File) {
    val session = remember(project) { TerminalSession(project) }
    DisposableEffect(session) { onDispose { session.close() } }
    val snap by session.snapshot.collectAsState()
    val running by session.running.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(snap.revision) { if (snap.lines.isNotEmpty()) list.scrollToItem(snap.lines.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A2E)).padding(6.dp), state = list) {
            items(snap.lines.size) { i -> Text(styled(snap.lines[i]), color = Color(0xFFD4D4D4), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("command (runs only when you press Run)") })
            if (running) OutlinedButton({ session.interrupt() }) { Text("Ctrl-C") } else Button({ session.submit(input); input = "" }) { Text("Run") }
        }
    }
}

private fun styled(line: StyledLine): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (s in line.spans.sortedBy { it.start }) {
        if (s.start > at) append(line.text.substring(at, minOf(s.start, line.text.length)))
        val seg = line.text.substring(minOf(s.start, line.text.length), minOf(s.end, line.text.length))
        withStyle(SpanStyle(color = s.style.foreground?.let { Color(it) } ?: Color.Unspecified)) { append(seg) }
        at = maxOf(at, s.end)
    }
    if (at < line.text.length) append(line.text.substring(at))
}
