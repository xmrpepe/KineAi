package com.kineai.local.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kineai.local.KineApp
import com.kineai.local.engine.EngineState
import com.kineai.local.models.DlState
import com.kineai.local.models.HfFile
import com.kineai.local.models.fmt
import kotlinx.coroutines.launch

private val RECOMMENDED = listOf(
    "Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF" to "Fastest, lowest quality",
    "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF" to "Balanced starting point",
    "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF" to "Best quality here; needs more memory",
)

private const val STEPS = 5

/** AndCode-style installer: a step indicator, one decision per step, and a fixed Back / Next bar. */
@Composable
fun OnboardingScreen(app: KineApp, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by app.models.models.collectAsState()
    val downloads by app.downloader.downloads.collectAsState()
    val engine by app.engine.state.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var step by remember { mutableStateOf(0) }
    var found by remember { mutableStateOf<Map<String, HfFile?>>(emptyMap()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { msg = "Copying…"; msg = runCatching { app.models.import(ctx.contentResolver, uri) { d, _ -> msg = "Copying… ${fmt(d)}" }; "Imported" }.getOrElse { it.message } }
    }
    val load = rememberLoadFlow(app) { msg = it }
    val verified = testOutput?.startsWith("“") == true
    val canNext = when (step) { 2 -> models.isNotEmpty(); 4 -> verified; else -> true }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step > 0) step-- }, enabled = step > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Set up KineAi", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Step ${step + 1} of $STEPS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StepIndicator(step)
            when (step) {
                0 -> {
                    Text("Storage and privacy", style = MaterialTheme.typography.headlineMedium)
                    Text("• The language model runs on your Pixel through the Kinetic engine. Prompts, answers, chats and project files never leave the device.\n" +
                        "• The network is used only when you search for or download a model, or clone a repository.\n" +
                        "• Models are large: roughly 0.5–4 GB each, kept in app storage, separate from your chats and projects.\n" +
                        "• You have ${fmt(app.paths.models.usableSpace)} free.\n" +
                        "• Suggested changes appear as diffs; nothing is written until you tap Apply.", style = MaterialTheme.typography.bodyLarge)
                }
                1 -> {
                    Text("Development tools", style = MaterialTheme.typography.headlineMedium)
                    Text("Install only the essentials by default, or include a complete development toolchain.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(true, null, enabled = false)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text("Required tools", style = MaterialTheme.typography.titleMedium)
                                    Text("The on-device Kinetic engine, Git (JGit), a shell terminal using Android's built-in tools, and the file, diff and chat tools. These are already included and always available.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(false, null, enabled = false)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text("Install complete development tools", style = MaterialTheme.typography.titleMedium)
                                    Text("A full Linux userland with language runtimes, compilers and editors. Not available in this version: it needs a Linux runtime (proot) that has not been added yet. The terminal currently offers only what Android itself provides.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Text("Get a model", style = MaterialTheme.typography.headlineMedium)
                    OutlinedButton({ pick.launch(arrayOf("*/*")) }) { Text("Import a .gguf from this device") }
                    Text("or download one (needs a connection; Q4_K_M, SHA-256 verified):", style = MaterialTheme.typography.bodySmall)
                    RECOMMENDED.forEach { (repo, note) ->
                        val f = found[repo]
                        val dl = downloads.firstOrNull { it.repo == repo }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(repo.substringAfter('/'), style = MaterialTheme.typography.titleSmall)
                                Text(note, style = MaterialTheme.typography.bodySmall)
                                if (dl != null) {
                                    LinearProgressIndicator(progress = { dl.fraction }, modifier = Modifier.fillMaxWidth())
                                    Text("${dl.state} ${fmt(dl.downloadedBytes)} / ${fmt(dl.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                                    dl.error?.let { Text(it, color = Bad) }
                                    if (dl.state == DlState.FAILED || dl.state == DlState.PAUSED) Button({ app.downloader.resume(dl.id) }) { Text("Resume") }
                                } else if (f != null) Button({ app.downloader.start(f) }, enabled = f.sizeBytes + 512L * 1024 * 1024 < app.paths.models.usableSpace) { Text("Download ${fmt(f.sizeBytes)}") }
                                else OutlinedButton({ scope.launch { found = found + (repo to runCatching { app.hf.files(repo).firstOrNull { it.quant == "Q4_K_M" } }.getOrNull().also { if (it == null) msg = "Could not reach Hugging Face. Check your connection, or import a file." }) } }) { Text("Check size") }
                            }
                        }
                    }
                    if (models.isNotEmpty()) Text("Installed: ${models.joinToString { it.displayName }}", color = Ok)
                    msg?.let { Text(it, color = Warn) }
                }
                3 -> {
                    Text("Compute", style = MaterialTheme.typography.headlineMedium)
                    Text("Choose where the model runs. CPU is the measured, default path. GPU (Vulkan) is experimental on Android: if the driver or model is not supported the app falls back to the CPU and tells you why, and if it ever crashes the engine it switches itself off.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(settings.device == "cpu", { app.settings.update { it.copy(device = "cpu") } }, { Text("CPU (recommended)") })
                        FilterChip(settings.device == "vulkan", { app.settings.update { it.copy(device = "vulkan") } }, { Text("GPU (experimental)") })
                    }
                    Text("CPU threads: ${settings.threads}", style = MaterialTheme.typography.titleMedium)
                    Slider(settings.threads.toFloat(), { app.settings.update { st -> st.copy(threads = it.toInt()) } }, valueRange = 1f..8f, steps = 6)
                    Text("The Pixel 9 has 1 + 3 performance cores; 4 is the default. You can change all of this later in Settings.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("Verify inference", style = MaterialTheme.typography.headlineMedium)
                    Text("We load the model and have it generate a few words on this device, so you know it works before chat opens.", style = MaterialTheme.typography.bodyLarge)
                    val m = models.firstOrNull { it.file.name == settings.modelFile } ?: models.firstOrNull()
                    if (engine !is EngineState.Ready) {
                        Button({ m?.let(load) }, enabled = m != null && engine !is EngineState.Loading) { Text(if (engine is EngineState.Loading) "Loading…" else "Load ${m?.displayName ?: ""}") }
                        (engine as? EngineState.Stopped)?.let { Text(it.reason, color = Bad) }
                    } else {
                        (engine as EngineState.Ready).let { Text("Running on: ${it.device}", style = MaterialTheme.typography.bodyMedium); it.deviceNote?.let { n -> Text(n, color = Warn) } }
                        Button({ scope.launch { testing = true; testOutput = ""
                            runCatching { app.engine.chat("""[{"role":"user","content":"Reply with exactly: ready"}]""", 16, 0f, 1L) { testOutput = (testOutput ?: "") + it } }
                                .onSuccess { testOutput = "“${testOutput?.trim()}”  —  ${"%.1f".format(it.generationTokensPerSecond)} tok/s on ${app.deviceLabel}" }
                                .onFailure { testOutput = "Failed: ${it.message}" }
                            testing = false } }, enabled = !testing) { Text(if (testing) "Generating…" else "Run test") }
                        testOutput?.let { Text(it) }
                    }
                    msg?.let { Text(it, color = Warn) }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ step-- }, enabled = step > 0, modifier = Modifier.width(110.dp).height(52.dp)) { Text("Back", maxLines = 1, softWrap = false) }
            Button(
                onClick = {
                    if (step == 0 && android.os.Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    if (step < STEPS - 1) step++ else { app.settings.update { it.copy(onboarded = true) }; onDone() }
                },
                enabled = canNext, modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(if (step < STEPS - 1) "Next" else "Open chat", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun StepIndicator(current: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        repeat(STEPS) { i ->
            val done = i < current
            val active = i == current
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                else Text("${i + 1}", color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            if (i < STEPS - 1) Box(Modifier.weight(1f).height(2.dp).background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}
