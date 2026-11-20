package com.kineai.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kineai.local.ChatController
import com.kineai.local.KineApp
import com.kineai.local.code.EditProposal
import com.kineai.local.code.Edits
import com.kineai.local.code.LineDiff
import com.kineai.local.data.ChatMessage
import com.kineai.local.engine.EngineState
import com.kineai.local.Applied
import java.io.File

@Composable
fun ChatScreen(app: KineApp, onOpenModels: () -> Unit) {
    val ui by app.chat.ui.collectAsState()
    val engine by app.engine.state.collectAsState()
    val project by app.selectedProject.collectAsState()
    val attached by app.attached.collectAsState()
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    var attachPicker by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val msgs = ui.conversation.messages
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.content?.length) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        StatusStrip(app)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.conversation.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            project?.let { Text("📁 ${it.name}", style = MaterialTheme.typography.labelMedium) }
            Box {
                IconButton(onClick = { history = true }) { Icon(Icons.Filled.History, "Saved conversations") }
                DropdownMenu(history, { history = false }) {
                    app.conversations.list().take(30).forEach { c ->
                        DropdownMenuItem(text = { Text(c.title, maxLines = 1) }, onClick = { app.chat.open(c.id); history = false },
                            trailingIcon = { IconButton(onClick = { app.chat.delete(c.id) }) { Icon(Icons.Filled.Delete, "Delete chat") } })
                    }
                }
            }
            IconButton(onClick = { app.chat.newConversation() }, enabled = !ui.generating) { Icon(Icons.Filled.Add, "New chat") }
        }
        if (engine !is EngineState.Ready) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (val e = engine) { is EngineState.Stopped -> e.reason; is EngineState.Loading -> "Loading the model on this device…"; else -> "No model is loaded." })
                    if (engine !is EngineState.Loading) Button(onClick = onOpenModels) { Text("Open Models") }
                }
            }
        }
        ui.notice?.let { n ->
            Row(Modifier.fillMaxWidth().background(Warn.copy(alpha = 0.15f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { app.chat.dismissNotice() }) { Text("OK") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(msgs.size) { i -> MessageBubble(msgs[i], project, app.chat, streaming = ui.generating && i == msgs.lastIndex) }
        }
        ui.lastStats?.let { s ->
            if (!ui.generating) Text("Last: ${"%.1f".format(s.generationTokensPerSecond)} tok/s generation · ${"%.1f".format(s.promptTokensPerSecond)} tok/s prompt · peak ${s.peakRssKb / 1024} MB", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (attached.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp)) {
            Text("Attached: ${attached.joinToString()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { app.attached.value = emptyList() }) { Text("Clear") }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { attachPicker = true }, enabled = project != null) { Icon(Icons.Filled.AttachFile, "Attach a project file") }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f).heightIn(max = 160.dp), placeholder = { Text("Ask about code, or request a change…") })
            if (ui.generating) {
                IconButton(onClick = { app.chat.stop() }) { Icon(Icons.Filled.Stop, "Stop generating") }
            } else {
                IconButton(onClick = { val t = input.trim(); if (t.isNotEmpty()) { app.chat.send(t, project, attached); input = ""; app.attached.value = emptyList() } }, enabled = engine is EngineState.Ready) {
                    Icon(Icons.Filled.Send, "Send")
                }
            }
        }
    }
    if (attachPicker) project?.let { p -> FilePickerDialog(app, p, onPick = { app.attached.value = (app.attached.value + it).distinct(); attachPicker = false }, onDismiss = { attachPicker = false }) }
}

@Composable
private fun FilePickerDialog(app: KineApp, root: File, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val files = remember(root) { root.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile && it.length() < 200_000 }.take(400).map { it.relativeTo(root).path }.toList() }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, title = { Text("Attach a file") },
        text = { LazyColumn { items(files) { f -> TextButton(onClick = { onPick(f) }) { Text(f, maxLines = 1) } } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun MessageBubble(m: ChatMessage, project: File?, chat: ChatController, streaming: Boolean) {
    val mine = m.role == "user"
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier.fillMaxWidth(if (mine) 0.88f else 1f).background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mine) SelectionContainer { Text(m.content) }
            else {
                val segs = remember(m.content) { Edits.parse(m.content) }
                if (m.content.isEmpty() && streaming) Text("Thinking on-device…", style = MaterialTheme.typography.bodySmall)
                segs.forEach { s ->
                    when {
                        s.edit != null -> EditCard(s.edit, project, chat)
                        s.isCode -> Column { InlineCode(s.text); TextButton(onClick = { clip.setText(AnnotatedString(s.text)) }) { Text("Copy") } }
                        s.text.isNotBlank() -> SelectionContainer { Text(s.text.trim()) }
                    }
                }
            }
            if (!mine && !streaming) {
                when (m.status) {
                    "cancelled" -> Text("Stopped by you.", style = MaterialTheme.typography.labelSmall)
                    "interrupted" -> Text("Interrupted — the engine stopped before finishing. What was generated is kept.", style = MaterialTheme.typography.labelSmall, color = Warn)
                }
                m.tokensPerSecond?.let { Text("%.1f tok/s".format(it), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** A proposed file change, shown as a diff. Nothing is written until the user taps Apply. */
@Composable
private fun EditCard(edit: EditProposal, project: File?, chat: ChatController) {
    var decision by remember(edit) { mutableStateOf<String?>(null) }
    var applied by remember(edit) { mutableStateOf<Applied?>(null) }
    var error by remember(edit) { mutableStateOf<String?>(null) }
    val target = project?.let { Edits.resolveSafe(it, edit.path) }
    val old = remember(edit, decision) { target?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } }
    val diff = remember(edit, old) { if (decision == null) LineDiff.diff(LineDiff.lines(old.orEmpty()), LineDiff.lines(edit.newContent)) else null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (old == null) "New file: ${edit.path}" else "Proposed edit: ${edit.path}", style = MaterialTheme.typography.titleSmall)
            when {
                project == null -> Text("Open a project in Workspace to apply edits.", color = Warn)
                target == null -> Text("Refused: the path leaves the project folder.", color = Bad)
                decision == null -> {
                    if (diff != null) DiffView(diff, Modifier.heightIn(max = 280.dp)) else InlineCode(edit.newContent.take(4000))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { chat.applyEdit(project, edit).onSuccess { applied = it; decision = "applied" }.onFailure { error = it.message } }) { Text("Apply") }
                        OutlinedButton(onClick = { decision = "rejected" }) { Text("Reject") }
                    }
                }
                decision == "applied" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Applied to ${edit.path}", Modifier.weight(1f), color = Ok)
                    TextButton(onClick = { applied?.let { chat.undo(it) }; decision = "undone" }) { Text("Undo") }
                }
                decision == "undone" -> Text("Undone — file restored.")
                else -> Text("Rejected — no changes made.")
            }
            error?.let { Text(it, color = Bad) }
        }
    }
}
