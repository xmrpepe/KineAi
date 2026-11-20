package com.kineai.local.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kineai.local.KineApp
import com.kineai.local.device.DeviceMonitor
import com.kineai.local.diagnostics.runBenchmark
import com.kineai.local.engine.EngineState
import com.kineai.local.models.fmt
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(app: KineApp) {
    val records by app.diagnostics.records.collectAsState()
    val engine by app.engine.state.collectAsState()
    val caps by app.caps.collectAsState()
    val v = rememberVitals(app)
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.refreshCapabilities() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(buildString {
                    appendLine("Device: ${app.settings.deviceName}")
                    appendLine("Compute device in use: ${app.deviceLabel}")
                    caps?.let { c ->
                        appendLine("CPU features: ${c.cpuFeatures}")
                        appendLine("GPU: ${c.gpuReason}")
                    } ?: appendLine("Engine capabilities: not reported yet")
                    appendLine("RAM: ${fmt(v.availRamBytes)} free of ${fmt(v.totalRamBytes)}${if (v.lowMemory) " (LOW)" else ""}")
                    appendLine("Thermal: ${DeviceMonitor.thermalName(v.thermalStatus)} · battery ${v.batteryPercent}% ${"%.1f".format(v.batteryTempC)}°C${if (v.charging) " charging" else ""}")
                    appendLine("Engine: ${when (val e = engine) { is EngineState.Ready -> "ready — ${e.name}, load ${e.loadMs} ms, RSS ${e.rssKb / 1024} MB (peak ${e.peakRssKb / 1024})"; is EngineState.Loading -> "loading"; is EngineState.Stopped -> "stopped — ${e.reason}"; EngineState.Idle -> "idle" }}")
                }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = { scope.launch { status = "Running…"; status = runCatching { runBenchmark(app) { status = it }; "Benchmark done" }.getOrElse { it.message } } }, enabled = engine is EngineState.Ready) { Text("Run benchmark") }
                OutlinedButton(onClick = { clip.setText(AnnotatedString(app.diagnostics.exportJson())) }) { Text("Copy JSON") }
                OutlinedButton(onClick = { app.diagnostics.clear() }) { Text("Clear") }
            }
            Text("Benchmark: ${com.kineai.local.diagnostics.BenchmarkSpec.RUNS} runs, fixed prompt, greedy decoding, ${com.kineai.local.diagnostics.BenchmarkSpec.GENERATE_TOKENS} tokens, prompt-cache defeated per run.", style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, color = Warn) }
        }
        items(records.asReversed()) { r ->
            Card(Modifier.fillMaxWidth()) {
                Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.timestamp))} · ${r.kind} · ${r.model}\n" +
                    "${r.device} · ctx ${r.contextSize} · ${r.threads} threads${r.loadMs?.let { " · load $it ms" } ?: ""}\n" +
                    "prompt ${r.promptTokens} tok @ ${"%.1f".format(r.promptTps)} tok/s · generated ${r.completionTokens} tok @ ${"%.1f".format(r.generationTps)} tok/s\n" +
                    "peak ${r.peakRssMb} MB · battery ${r.batteryStart}→${r.batteryEnd}% · ${"%.1f".format(r.batteryTempStartC)}→${"%.1f".format(r.batteryTempEndC)}°C · thermal ${r.thermalStart}→${r.thermalEnd}",
                    Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
