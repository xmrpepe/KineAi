package com.kineai.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kineai.local.Applied
import com.kineai.local.ChatController
import com.kineai.local.KineApp
import com.kineai.local.code.EditProposal
import com.kineai.local.code.Edits
import com.kineai.local.code.LineDiff
import com.kineai.local.data.ChatMessage
import com.kineai.local.engine.EngineState
import com.kineai.local.models.fmt
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm", Locale.US)

private fun tokens(n: Int) = if (n >= 1000) "%.1fk".format(n / 1000.0) else "$n"

/** Chat window laid out like AndCode's: menu / title / new, plain assistant text, blue user bubbles, rounded composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(app: KineApp, onMenu: () -> Unit, onOpenModels: () -> Unit) {
    val ui by app.chat.ui.collectAsState()
    val engine by app.engine.state.collectAsState()
    val project by app.selectedProject.collectAsState()
    val attached by app.attached.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val models by app.models.models.collectAsState()
    val online by app.monitor.online.collectAsState()
    val vitals = rememberVitals(app)
    var input by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var attachPicker by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val load = rememberLoadFlow(app) { loadError = it }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val msgs = ui.conversation.messages
    val atBottom by remember { derivedStateOf { val l = list.layoutInfo.visibleItemsInfo.lastOrNull(); l == null || l.index >= list.layoutInfo.totalItemsCount - 1 } }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    LaunchedEffect(msgs.lastOrNull()?.content?.length) { if (atBottom && msgs.isNotEmpty()) list.scrollToItem(msgs.size - 1) }

    val ctxLimit = ((engine as? EngineState.Ready)?.contextLength ?: settings.contextSize).coerceAtMost(settings.contextSize).coerceAtLeast(1)
    val usedTokens = remember(msgs.size, msgs.lastOrNull()?.content?.length) { (msgs.sumOf { it.content.length } / 3.5).toInt() }

    Column(Modifier.fillMaxSize()) {
        // Header: menu, title, new chat.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu") }
            Text(ui.conversation.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { app.chat.newConversation() }, enabled = !ui.generating) { Icon(Icons.Filled.Add, "New chat") }
        }

        (ui.notice ?: loadError)?.let { n ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Warn.copy(alpha = 0.15f)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { app.chat.dismissNotice(); loadError = null }) { Text("OK") }
            }
        }
        if (engine is EngineState.Stopped) Text((engine as EngineState.Stopped).reason, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Bad, style = MaterialTheme.typography.bodySmall)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask about code, or request a change", style = MaterialTheme.typography.titleMedium)
                    Text("Everything runs on this phone. Suggested edits appear as diffs you approve.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(msgs.size) { i -> MessageItem(msgs[i], project, app.chat, streaming = ui.generating && i == msgs.lastIndex) }
            }
            if (!atBottom && msgs.isNotEmpty()) {
                FilledTonalIconButton(onClick = { scope.launch { list.animateScrollToItem(msgs.size - 1) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.ArrowDownward, "Scroll to latest") }
            }
        }

        if (attached.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Attached: ${attached.joinToString()}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { app.attached.value = emptyList() }) { Text("Clear") }
        }

        // Composer: message field, then + / model pill / send.
        Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                TextField(
                    input, { input = it }, Modifier.fillMaxWidth().heightIn(max = 160.dp), placeholder = { Text("Message…") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { attachPicker = true }, enabled = project != null, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Icon(Icons.Filled.Add, "Attach a project file") }
                    Box {
                        Surface(Modifier.clip(RoundedCornerShape(50)).clickable { modelMenu = true }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (val e = engine) { is EngineState.Ready -> e.name; is EngineState.Loading -> "Loading…"; else -> "Select model" },
                                    Modifier.widthIn(max = 170.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                        }
                        DropdownMenu(modelMenu, { modelMenu = false }) {
                            models.forEach { m -> DropdownMenuItem(text = { Text(m.displayName, maxLines = 1) }, onClick = { modelMenu = false; load(m) }) }
                            DropdownMenuItem(text = { Text("Manage models…") }, onClick = { modelMenu = false; onOpenModels() })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (ui.generating) {
                        FilledIconButton(onClick = { app.chat.stop() }) { Icon(Icons.Filled.Stop, "Stop generating") }
                    } else {
                        FilledIconButton(
                            onClick = { val t = input.trim(); if (t.isNotEmpty()) { app.chat.send(t, project, attached); input = ""; app.attached.value = emptyList() } },
                            enabled = engine is EngineState.Ready && input.isNotBlank(),
                        ) { Icon(Icons.Filled.Send, "Send") }
                    }
                }
            }
        }

        // Status under the composer: connectivity, free RAM, speed and the context meter.
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (online) Chip("Online · inference local", MaterialTheme.colorScheme.onSurfaceVariant) else Chip("Offline · local", Ok)
            Chip("RAM ${fmt(vitals.availRamBytes)} free", if (vitals.lowMemory) Bad else MaterialTheme.colorScheme.onSurfaceVariant)
            ui.lastStats?.takeIf { !ui.generating }?.let { Chip("%.1f tok/s".format(it.generationTokensPerSecond), MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(progress = { (usedTokens.toFloat() / ctxLimit).coerceIn(0f, 1f) }, Modifier.size(width = 44.dp, height = 3.dp), color = if (usedTokens > ctxLimit * 0.9) Warn else MaterialTheme.colorScheme.primary)
                Text("${tokens(usedTokens)}/${tokens(ctxLimit)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun MessageItem(m: ChatMessage, project: File?, chat: ChatController, streaming: Boolean) {
    if (m.role == "user") {
        // Right-aligned bubble with its time, as in AndCode.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(clock.format(Date(m.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Surface(Modifier.widthIn(max = 320.dp), shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Text(m.content, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }
    // Assistant: no bubble, full-width Markdown; code blocks and edit proposals inline.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.content.isEmpty() && streaming) Text("Thinking on-device…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val segs = remember(m.content) { Edits.parse(m.content) }
        segs.forEach { s ->
            when {
                s.edit != null -> EditCard(s.edit, project, chat)
                s.isCode -> CodeBlockView(s.text)
                s.text.isNotBlank() -> MarkdownText(s.text.trim())
            }
        }
        if (!streaming) {
            when (m.status) {
                "cancelled" -> Text("Stopped by you.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                "interrupted" -> Text("Interrupted — the engine stopped before finishing. What was generated is kept.", style = MaterialTheme.typography.labelSmall, color = Warn)
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
