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

/** First run: storage, get a model, and prove inference works on this phone before chat opens. */
@Composable
fun OnboardingScreen(app: KineApp, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by app.models.models.collectAsState()
    val downloads by app.downloader.downloads.collectAsState()
    val engine by app.engine.state.collectAsState()
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Welcome to KineAi", style = MaterialTheme.typography.headlineMedium)
        Text("A coding assistant that runs entirely on this phone.", style = MaterialTheme.typography.titleMedium)
        when (step) {
            0 -> {
                Text("• The language model runs on your Pixel's CPU through the Kinetic engine. Prompts, answers, chats and project files never leave the device.\n" +
                    "• The network is used only when you search for or download a model, or clone a repository.\n" +
                    "• Models are large: roughly 0.5–4 GB each, kept in app storage, separate from your chats and projects.\n" +
                    "• You have ${fmt(app.paths.models.usableSpace)} free.\n" +
                    "• Suggested changes appear as diffs; nothing is written until you tap Apply.")
                Button({ if (android.os.Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS); step = 1 }) { Text("Continue") }
            }
            1 -> {
                Text("Get a model", style = MaterialTheme.typography.titleMedium)
                OutlinedButton({ pick.launch(arrayOf("*/*")) }) { Text("Import a .gguf from this device") }
                Text("or download one (needs a connection, Q4_K_M, SHA-256 verified):", style = MaterialTheme.typography.bodySmall)
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
                msg?.let { Text(it, color = Warn) }
                Button({ step = 2 }, enabled = models.isNotEmpty()) { Text(if (models.isEmpty()) "Waiting for a model…" else "Continue") }
            }
            else -> {
                Text("Verify inference", style = MaterialTheme.typography.titleMedium)
                Text("We will load the model and have it generate a few words on this device, so you know it works before chat opens.")
                val m = models.firstOrNull { it.file.name == app.settings.value.modelFile } ?: models.firstOrNull()
                if (engine !is EngineState.Ready) {
                    Button({ m?.let(load) }, enabled = m != null && engine !is EngineState.Loading) { Text(if (engine is EngineState.Loading) "Loading…" else "Load ${m?.displayName ?: ""}") }
                    (engine as? EngineState.Stopped)?.let { Text(it.reason, color = Bad) }
                } else {
                    Button({ scope.launch { testing = true; testOutput = ""
                        runCatching { app.engine.chat("""[{"role":"user","content":"Reply with exactly: ready"}]""", 16, 0f, 1L) { testOutput = (testOutput ?: "") + it } }
                            .onSuccess { testOutput = "“${testOutput?.trim()}”  —  ${"%.1f".format(it.generationTokensPerSecond)} tok/s on ${app.deviceLabel}" }
                            .onFailure { testOutput = "Failed: ${it.message}" }
                        testing = false } }, enabled = !testing) { Text(if (testing) "Generating…" else "Run test") }
                    testOutput?.let { Text(it) }
                    if (testOutput?.startsWith("“") == true) Button({ app.settings.update { it.copy(onboarded = true) }; onDone() }) { Text("Open chat") }
                }
                msg?.let { Text(it, color = Warn) }
            }
        }
    }
}
