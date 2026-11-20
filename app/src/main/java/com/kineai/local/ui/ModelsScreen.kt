package com.kineai.local.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kineai.local.KineApp
import com.kineai.local.diagnostics.runBenchmark
import com.kineai.local.engine.EngineState
import com.kineai.local.models.DlState
import com.kineai.local.models.Download
import com.kineai.local.models.Fit
import com.kineai.local.models.HfFile
import com.kineai.local.models.HfModel
import com.kineai.local.models.InstalledModel
import com.kineai.local.models.Verdict
import com.kineai.local.models.classify
import com.kineai.local.models.fmt
import com.kineai.local.models.quantFromFileName
import com.kineai.local.models.tierFor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private fun fitColor(f: Fit) = when (f) { Fit.COMPATIBLE -> Ok; Fit.LIMITED -> Warn; Fit.UNSUPPORTED -> Bad }

@Composable
fun ModelsScreen(app: KineApp) {
    var tab by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<HfModel?>(null) }
    val downloads by app.downloader.downloads.collectAsState()
    Column(Modifier.fillMaxSize()) {
        StatusStrip(app)
        if (detail != null) { RepoDetail(app, detail!!) { detail = null }; return@Column }
        TabRow(tab) {
            listOf("Installed", "Discover", "Downloads${downloads.count { it.state != DlState.DONE }.let { if (it > 0) " ($it)" else "" }}").forEachIndexed { i, t ->
                Tab(tab == i, { tab = i }, text = { Text(t) })
            }
        }
        when (tab) { 0 -> Installed(app); 1 -> Discover(app) { detail = it }; else -> Downloads(app, downloads) }
    }
}

// ---------------------------------------------------------------- Installed

@Composable
private fun Installed(app: KineApp) {
    val models by app.models.models.collectAsState()
    val engine by app.engine.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    val load = rememberLoadFlow(app) { message = it }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                importing = "Copying…"
                app.models.import(ctx.contentResolver, uri) { d, t -> importing = "Copying… ${fmt(d)}${if (t > 0) " of ${fmt(t)}" else ""}" }
                message = "Imported. Inspect it, then load it."
            } catch (e: Exception) { message = e.message } finally { importing = null }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = importing == null) { Text("Import GGUF file") }
                Text(importing ?: "Pick one .gguf with the system picker; only that file is shared with the app.", style = MaterialTheme.typography.bodySmall)
            }
            message?.let { Text(it, color = Warn, modifier = Modifier.padding(top = 6.dp)) }
            Text("Free storage: ${fmt(app.paths.models.usableSpace)} · Models are stored separately from chats and projects.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        if (models.isEmpty()) item { Text("No models installed yet. Import a file, or use Discover to download one.") }
        items(models, key = { it.file.name }) { m -> InstalledRow(app, m, engine, load) { message = it } }
    }
}

@Composable
private fun InstalledRow(app: KineApp, m: InstalledModel, engine: EngineState, load: (InstalledModel) -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val isLoaded = engine is EngineState.Ready && engine.path == m.file.absolutePath
    LaunchedEffect(m.file.name, settings.contextSize) { verdict = runCatching { verdictFor(app, m, settings.contextSize) }.getOrNull() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${fmt(m.sizeBytes)} · ${m.source}${if (m.verified) " · SHA-256 verified" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                Box2 {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Model actions") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Inspect") }, onClick = { menu = false; scope.launch { runCatching { app.engine.inspect(m.file.absolutePath) }.onSuccess { app.models.saveInspection(m, it); info = describe(it) }.onFailure { say(it.message ?: "inspect failed") } } })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = m.displayName })
                        DropdownMenuItem(text = { Text("Benchmark") }, enabled = isLoaded, onClick = { menu = false; scope.launch { busy = "Benchmark…"; runCatching { runBenchmark(app) { busy = it } }.onFailure { say(it.message ?: "benchmark failed") }; busy = null; say("Benchmark saved in Diagnostics") } })
                        DropdownMenuItem(text = { Text("Delete model file") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
            verdict?.let { v -> Text("${v.fit.label}: ${v.reasons.first()}", color = fitColor(v.fit), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLoaded) OutlinedButton(onClick = { scope.launch { app.engine.unload() } }) { Text("Unload") }
                else Button(onClick = { load(m) }, enabled = engine !is EngineState.Loading && verdict?.fit != Fit.UNSUPPORTED || verdict == null) { Text("Load") }
                busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    rename?.let { r -> AlertDialog({ rename = null }, title = { Text("Rename model") }, text = { OutlinedTextField(r, { rename = it }, singleLine = true) },
        confirmButton = { TextButton({ app.models.rename(m, r); rename = null }) { Text("Save") } }, dismissButton = { TextButton({ rename = null }) { Text("Cancel") } }) }
    info?.let { AlertDialog({ info = null }, title = { Text("Model details") }, text = { Text(it) }, confirmButton = { TextButton({ info = null }) { Text("Close") } }) }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Delete ${m.displayName}?") },
        text = { Text("This removes the ${fmt(m.sizeBytes)} model file only. Your conversations and project files are not touched.") },
        confirmButton = { TextButton({ scope.launch { if (isLoaded) app.engine.unload(); app.models.delete(m); confirmDelete = false } }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

@Composable private fun Box2(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box { content() }

private fun describe(o: org.json.JSONObject): String = buildString {
    appendLine("Status: ${o.optString("status")}${o.optString("reason").takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""}")
    appendLine("Architecture: ${o.optString("architecture")}")
    appendLine("Parameters: ${"%.2f".format(o.optLong("parameters") / 1e9)} B")
    appendLine("Trained context: ${o.optLong("contextLength")} tokens")
    appendLine("Layers: ${o.optLong("layers")}, width ${o.optLong("width")}")
    o.optJSONArray("quantizations")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { appendLine("${it.getString("type")}: ${it.getLong("tensors")} tensors, ${fmt(it.getLong("bytes"))}") } }
}

// ---------------------------------------------------------------- Discover

private data class RepoRow(val model: HfModel, val files: List<HfFile>?, val best: Pair<HfFile, Verdict>?, val error: String? = null)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Discover(app: KineApp, open: (HfModel) -> Unit) {
    val scope = rememberCoroutineScope()
    val caps by app.caps.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var query by remember { mutableStateOf("coder instruct") }
    var rows by remember { mutableStateOf<List<RepoRow>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var maxGb by remember { mutableStateOf<Float?>(null) }
    var quant by remember { mutableStateOf<String?>(null) }
    var arch by remember { mutableStateOf<String?>(null) }
    var license by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.refreshCapabilities() }

    fun search() {
        scope.launch {
            val c = app.caps.value ?: app.refreshCapabilities()
            if (c == null) { status = "The engine is not reachable, so models cannot be classified yet."; return@launch }
            status = "Searching Hugging Face…"; rows = emptyList()
            val found = runCatching { app.hf.search(query) }.getOrElse { status = "Search failed: ${it.message}. Search needs a network connection; installed models work offline."; return@launch }
            rows = found.map { RepoRow(it, null, null) }
            status = "Checking files…"
            val gate = Semaphore(4)
            found.map { m -> scope.async { gate.withPermit {
                val files = runCatching { app.hf.files(m.id) }.getOrNull()
                val budget = app.monitor.budget()
                val scored = files?.filter { !it.isCompanion && (quant == null || it.quant?.equals(quant, true) == true) && (maxGb == null || it.sizeBytes <= maxGb!! * 1e9) }?.map { f ->
                    f to classify(m.architecture, f.quant, f.sizeBytes, settings.contextSize, null, null, f.isSplit, false, c, budget)
                }.orEmpty()
                val best = scored.filter { it.second.fit == Fit.COMPATIBLE }.maxByOrNull { it.first.sizeBytes }
                    ?: scored.filter { it.second.fit == Fit.LIMITED }.minByOrNull { it.first.sizeBytes } ?: scored.minByOrNull { it.first.sizeBytes }
                rows = rows.map { if (it.model.id == m.id) RepoRow(m, files, best, if (files == null) "could not list files" else null) else it }
            } } }.awaitAll()
            status = null
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search GGUF models") })
                IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, "Search") }
            }
            Text("Size", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(null to "Any", 1f to "≤1 GB", 2f to "≤2 GB", 4f to "≤4 GB").forEach { (v, l) -> FilterChip(maxGb == v, { maxGb = v }, { Text(l) }) } }
            Text("Quantization", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(null, "Q4_K_M", "Q5_K_M", "Q6_K", "Q8_0").forEach { q -> FilterChip(quant == q, { quant = q }, { Text(q ?: "Any") }) } }
            Text("Architecture (engine-supported)", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(arch == null, { arch = null }, { Text("Any") })
                caps?.architectures?.sorted()?.take(12)?.forEach { a -> FilterChip(arch == a, { arch = a }, { Text(a) }) }
            }
            Text("License", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(null, "apache-2.0", "mit", "llama3.2").forEach { l -> FilterChip(license == l, { license = l }, { Text(l ?: "Any") }) } }
            Button(onClick = { search() }) { Text("Search") }
            status?.let { Text(it, color = Warn) }
            Text("Filters apply to the next search. Classification uses this engine's real architecture and kernel tables, your free RAM and storage.", style = MaterialTheme.typography.bodySmall)
        }
        val visible = rows.filter { (arch == null || it.model.architecture == arch) && (license == null || it.model.license == license) }
        for (fit in Fit.entries) {
            val group = visible.filter { (it.best?.second?.fit ?: if (it.files == null && it.error == null) null else Fit.UNSUPPORTED) == fit }
            if (group.isNotEmpty()) item { Text("${fit.label} (${group.size})", style = MaterialTheme.typography.titleMedium, color = fitColor(fit), modifier = Modifier.padding(top = 8.dp)) }
            items(group, key = { "${fit.name}-${it.model.id}" }) { r -> RepoCard(r) { open(r.model) } }
        }
        val pendingRows = visible.filter { it.files == null && it.error == null }
        if (pendingRows.isNotEmpty()) { item { Text("Checking (${pendingRows.size})", style = MaterialTheme.typography.titleMedium) }; items(pendingRows, key = { "p-${it.model.id}" }) { RepoCard(it) { open(it.model) } } }
    }
}

@Composable
private fun RepoCard(r: RepoRow, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.model.id, style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(r.model.architecture, r.model.license, "${r.model.downloads} downloads").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            r.best?.let { (f, v) -> Text("${f.path.substringAfterLast('/')} · ${fmt(f.sizeBytes)} · ~${fmt(v.estimatedMemoryBytes)} RAM at ${""}ctx", style = MaterialTheme.typography.bodySmall); Text(v.reasons.first(), color = fitColor(v.fit), style = MaterialTheme.typography.bodySmall) }
            r.error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ---------------------------------------------------------------- Detail

@Composable
private fun RepoDetail(app: KineApp, model: HfModel, back: () -> Unit) {
    val caps by app.caps.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var files by remember { mutableStateOf<List<HfFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloads by app.downloader.downloads.collectAsState()
    LaunchedEffect(model.id) { runCatching { app.hf.files(model.id) }.onSuccess { files = it }.onFailure { error = it.message } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = back) { Text("← Back") }
            Text(model.id, style = MaterialTheme.typography.titleLarge)
            Text("Source: https://huggingface.co/${model.id}", style = MaterialTheme.typography.bodySmall)
            Text(listOfNotNull("License: ${model.license ?: "not stated"}", model.architecture?.let { "Architecture: $it" }, model.contextLength?.let { "Trained context: $it" }, "${model.downloads} downloads · ${model.likes} likes").joinToString("\n"))
            Text("Pick one exact file below. Nothing else from the repository is downloaded. Memory estimates assume ${settings.contextSize} tokens of context and Kinetic's f32 KV cache.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = Bad) }
        }
        val list = files
        if (list == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        list?.let { fl ->
            if (fl.any { it.isCompanion }) item { Text("Companion files (vision projector, \"mmproj\") are listed but not needed for text chat and are not supported by this app.", style = MaterialTheme.typography.bodySmall) }
            items(fl, key = { it.path }) { f ->
                val v = caps?.let { classify(model.architecture, f.quant, f.sizeBytes, settings.contextSize, null, null, f.isSplit, false, it, app.monitor.budget()) }
                val dl = downloads.firstOrNull { it.path == f.path && it.repo == f.repo }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(f.path, style = MaterialTheme.typography.titleSmall)
                        Text("${f.quant ?: "unknown quant"} · ${fmt(f.sizeBytes)}${if (f.sha256 != null) " · SHA-256 published" else " · no trusted hash"}${if (f.isCompanion) " · companion" else ""}", style = MaterialTheme.typography.bodySmall)
                        v?.let { Text("${it.fit.label} · ~${fmt(it.estimatedMemoryBytes)} RAM", color = fitColor(it.fit), style = MaterialTheme.typography.bodySmall); it.reasons.forEach { r -> Text("• $r", style = MaterialTheme.typography.bodySmall) } }
                        if (dl != null && dl.state != DlState.DONE) Text("In Downloads: ${dl.state}", style = MaterialTheme.typography.bodySmall)
                        else if (dl?.state == DlState.DONE) Text("Installed ✓", color = Ok)
                        else Button(onClick = { app.downloader.start(f) }, enabled = v == null || v.fit != Fit.UNSUPPORTED && !f.isCompanion) { Text("Download this file") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Downloads

@Composable
private fun Downloads(app: KineApp, list: List<Download>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (list.isEmpty()) item { Text("No downloads. Downloads use the network only when you start them; everything else stays on the phone.") }
        items(list, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.fileName, style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${d.state} · ${fmt(d.downloadedBytes)} of ${fmt(d.totalBytes)}${if (d.bytesPerSecond > 0) " · ${fmt(d.bytesPerSecond)}/s" else ""}${if (d.state == DlState.DONE && d.verifiedHash) " · integrity verified" else if (d.state == DlState.DONE) " · no trusted hash to verify" else ""}", style = MaterialTheme.typography.bodySmall)
                    d.error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (d.state) {
                            DlState.RUNNING -> OutlinedButton({ app.downloader.pause(d.id) }) { Text("Pause") }
                            DlState.PAUSED -> Button({ app.downloader.resume(d.id) }) { Text("Resume") }
                            DlState.FAILED -> Button({ app.downloader.resume(d.id) }) { Text("Retry") }
                            DlState.DONE -> OutlinedButton({ app.downloader.dismiss(d.id) }) { Text("Dismiss") }
                            DlState.VERIFYING -> Text("Verifying…")
                        }
                        if (d.state != DlState.DONE) TextButton({ app.downloader.cancel(d.id) }) { Text("Cancel & delete partial") }
                    }
                }
            }
        }
    }
}
