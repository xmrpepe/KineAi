package com.kineai.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kineai.local.KineApp
import com.kineai.local.device.DeviceMonitor
import com.kineai.local.device.Vitals
import com.kineai.local.engine.EngineException
import com.kineai.local.engine.EngineState
import com.kineai.local.models.Fit
import com.kineai.local.models.InstalledModel
import com.kineai.local.models.Verdict
import com.kineai.local.models.classify
import com.kineai.local.models.fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Polls device vitals while visible. */
@Composable
fun rememberVitals(app: KineApp, periodMs: Long = 2000): Vitals {
    var v by remember { mutableStateOf(app.monitor.vitals()) }
    LaunchedEffect(Unit) { while (true) { v = app.monitor.vitals(); delay(periodMs) } }
    return v
}

@Composable
fun Chip(text: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.padding(end = 2.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

val Ok = Color(0xFF4CAF50)
val Warn = Color(0xFFFFB300)
val Bad = Color(0xFFEF5350)

/** Model, memory and connectivity at a glance. The offline chip states what is true: inference is local either way. */
@Composable
fun StatusStrip(app: KineApp) {
    val state by app.engine.state.collectAsState()
    val online by app.monitor.online.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val v = rememberVitals(app)
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (val s = state) {
            is EngineState.Ready -> Chip("${s.name} · ctx ${settings.contextSize} · ${s.rssKb / 1024} MB", Ok, Icons.Filled.Memory)
            is EngineState.Loading -> Chip("Loading model…", Warn, Icons.Filled.Memory)
            is EngineState.Stopped -> Chip("Engine stopped", Bad, Icons.Filled.Memory)
            EngineState.Idle -> Chip("No model loaded", Warn, Icons.Filled.Memory)
        }
        Chip("RAM free ${fmt(v.availRamBytes)}", if (v.lowMemory) Bad else MaterialTheme.colorScheme.onSurfaceVariant)
        if (online) Chip("Online · inference stays local", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Wifi)
        else Chip("Offline · fully local", Ok, Icons.Filled.CloudOff)
        if (v.thermalStatus >= 2) Chip("Thermal: ${DeviceMonitor.thermalName(v.thermalStatus)}", Warn)
    }
}

/** Verdict for an installed model at [context], using the engine's own capability tables. */
suspend fun verdictFor(app: KineApp, model: InstalledModel, context: Int): Verdict {
    val caps = app.caps.value ?: app.refreshCapabilities() ?: throw EngineException("The engine is not reachable (${app.capsError ?: "no reply"})", engineDied = true)
    val insp = model.inspect ?: app.engine.inspect(model.file.absolutePath).also { app.models.saveInspection(model, it) }
    val width = insp.optLong("width").toInt().takeIf { it > 0 }
    val heads = insp.optLong("headCount").toInt().takeIf { it > 0 }
    val kvHeads = insp.optLong("headCountKv").toInt().takeIf { it > 0 } ?: heads
    val keyLen = insp.optLong("keyLength").toInt().takeIf { it > 0 } ?: if (width != null && heads != null) width / heads else null
    val kvDim = if (keyLen != null && kvHeads != null) keyLen * kvHeads else null
    val quants = insp.optJSONArray("quantizations")
    val dominant = quants?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.maxByOrNull { it.getLong("bytes") }?.getString("type") }
    var verdict = classify(
        insp.optString("architecture").ifEmpty { null }, dominant, model.sizeBytes, context,
        insp.optLong("layers").toInt().takeIf { it > 0 }, kvDim, split = false, alreadyOnDisk = true, caps = caps, device = app.monitor.budget(),
    )
    if (insp.optString("status") == "unsupported") {
        verdict = verdict.copy(fit = Fit.UNSUPPORTED, reasons = listOf(insp.optString("reason")) + verdict.reasons)
    }
    return verdict
}

/** Loads a model after warning about settings likely to exceed memory. Returns the request function plus its dialogs. */
@Composable
fun rememberLoadFlow(app: KineApp, onError: (String) -> Unit = {}): (InstalledModel) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<InstalledModel, Verdict>?>(null) }
    val settings by app.settings.flow.collectAsState()

    fun doLoad(m: InstalledModel, ctx: Int) {
        app.settings.update { it.copy(modelFile = m.file.name, contextSize = ctx) }
        scope.launch {
            try { app.engine.load(m.file.absolutePath, ctx, app.settings.value.threads) }
            catch (e: Exception) { onError(e.message ?: "Load failed") }
        }
    }

    pending?.let { (m, v) ->
        val lower = (settings.contextSize / 2).coerceAtLeast(1024)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (v.fit == Fit.UNSUPPORTED) "This is unlikely to fit" else "This may run with limits") },
            text = { Text(v.reasons.joinToString("\n\n") + "\n\nKV cache at ${settings.contextSize} tokens: ${fmt(v.kvBytes)} (Kinetic keeps it in f32). If memory runs out, Android ends the engine process; your chats are safe, but the model must be loaded again by you.") },
            confirmButton = { TextButton(onClick = { val x = m; pending = null; doLoad(x, settings.contextSize) }) { Text("Load anyway") } },
            dismissButton = {
                Row {
                    if (settings.contextSize > 1024) TextButton(onClick = { val x = m; pending = null; doLoad(x, lower) }) { Text("Load with $lower") }
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                }
            },
        )
    }
    return { m ->
        scope.launch {
            try {
                val v = verdictFor(app, m, app.settings.value.contextSize)
                if (v.fit == Fit.COMPATIBLE) doLoad(m, app.settings.value.contextSize) else pending = m to v
            } catch (e: Exception) { onError(e.message ?: "Could not inspect the model") }
        }
    }
}
