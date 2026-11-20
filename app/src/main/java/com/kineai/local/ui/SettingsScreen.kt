package com.kineai.local.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.kineai.local.models.Fit
import com.kineai.local.models.Verdict
import com.kineai.local.models.fmt
import kotlinx.coroutines.launch

private val CONTEXTS = listOf(1024, 2048, 4096, 8192, 16384, 32768)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: KineApp) {
    val s by app.settings.flow.collectAsState()
    val models by app.models.models.collectAsState()
    val caps by app.caps.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val load = rememberLoadFlow(app) { message = it }
    val selected = models.firstOrNull { it.file.name == s.modelFile }
    LaunchedEffect(s.modelFile, s.contextSize, models) { verdict = selected?.let { runCatching { verdictFor(app, it, s.contextSize) }.getOrNull() } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { app.conversations.exportZip(it) }.let { "Exported $it conversations" } }.getOrElse { it.message } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { app.conversations.importZip(it) }.let { "Restored $it conversations" } }.getOrElse { it.message } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("Model", style = MaterialTheme.typography.titleMedium)
        if (models.isEmpty()) Text("No models installed.")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            models.forEach { m -> FilterChip(m.file.name == s.modelFile, { app.settings.update { it.copy(modelFile = m.file.name) } }, { Text(m.displayName) }) }
        }

        Text("Context size: ${s.contextSize} tokens", style = MaterialTheme.typography.titleMedium)
        Slider(CONTEXTS.indexOf(s.contextSize).coerceAtLeast(0).toFloat(), { app.settings.update { st -> st.copy(contextSize = CONTEXTS[it.toInt()]) } }, valueRange = 0f..(CONTEXTS.size - 1).toFloat(), steps = CONTEXTS.size - 2)
        verdict?.let { v ->
            val color = when (v.fit) { Fit.COMPATIBLE -> Ok; Fit.LIMITED -> Warn; Fit.UNSUPPORTED -> Bad }
            Text("${v.fit.label}: est. ${fmt(v.estimatedMemoryBytes)} (weights + ${fmt(v.kvBytes)} f32 KV cache).\n${v.reasons.joinToString("\n")}", color = color, style = MaterialTheme.typography.bodySmall)
        }
        Text("Defaults are conservative starting points; the Diagnostics benchmark shows what your Pixel actually does.", style = MaterialTheme.typography.bodySmall)

        Text("CPU threads: ${s.threads}", style = MaterialTheme.typography.titleMedium)
        Slider(s.threads.toFloat(), { app.settings.update { st -> st.copy(threads = it.toInt()) } }, valueRange = 1f..8f, steps = 6)
        Text("Pixel 9 (Tensor G4) has 1 X4 + 3 A720 performance cores and 4 A520 efficiency cores; 4 is the default. Changing it restarts the engine process on the next load (chats are unaffected).", style = MaterialTheme.typography.bodySmall)

        Text("Compute device", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(true, {}, { Text("CPU (NEON)") })
            FilterChip(false, {}, { Text("GPU (Vulkan)") }, enabled = false)
        }
        Text("GPU unavailable: ${caps?.gpuReason ?: "the engine has not reported yet"}. CPU is used automatically.", style = MaterialTheme.typography.bodySmall)

        Text("Generation", style = MaterialTheme.typography.titleMedium)
        Text("Temperature ${"%.2f".format(s.temperature)}")
        Slider(s.temperature, { app.settings.update { st -> st.copy(temperature = it) } }, valueRange = 0f..1.2f)
        Text("Max answer length ${s.maxTokens} tokens")
        Slider(s.maxTokens.toFloat(), { app.settings.update { st -> st.copy(maxTokens = it.toInt()) } }, valueRange = 128f..4096f)

        Button(onClick = { selected?.let(load) }, enabled = selected != null) { Text("Apply and (re)load model") }

        Text("Conversations", style = MaterialTheme.typography.titleMedium)
        Text("Saved separately from models and projects; deleting a model never deletes chats.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ export.launch("kineai-conversations.zip") }) { Text("Export backup") }
            OutlinedButton({ restore.launch(arrayOf("application/zip", "*/*")) }) { Text("Restore backup") }
        }
        message?.let { Text(it, color = Warn) }

        Text("About", style = MaterialTheme.typography.titleMedium)
        Text("Inference: Kinetic v0.1.0 (Apache-2.0, © Velocity) ported to Android arm64. UI base: AndCode v1.2.29 (MIT). See the licenses folder and docs/ANDROID_PORT.md.", style = MaterialTheme.typography.bodySmall)
    }
}
