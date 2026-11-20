package com.kineai.local.diagnostics

import com.kineai.local.data.Paths
import com.kineai.local.device.DeviceMonitor
import com.kineai.local.engine.ChatStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Record(
    val timestamp: Long,
    val kind: String, // "chat" or "benchmark"
    val model: String,
    val contextSize: Int,
    val threads: Int,
    /** The compute device that actually ran: e.g. "CPU (NEON dotprod)". */
    val device: String,
    val loadMs: Long?,
    val promptTokens: Int,
    val promptTps: Double,
    val completionTokens: Int,
    val generationTps: Double,
    val peakRssMb: Long,
    val batteryStart: Int,
    val batteryEnd: Int,
    val batteryTempStartC: Float,
    val batteryTempEndC: Float,
    val thermalStart: String,
    val thermalEnd: String,
) {
    fun toJson() = JSONObject().put("t", timestamp).put("kind", kind).put("model", model).put("ctx", contextSize)
        .put("threads", threads).put("device", device).put("loadMs", loadMs ?: JSONObject.NULL).put("pt", promptTokens)
        .put("ptps", promptTps).put("ct", completionTokens).put("gtps", generationTps).put("peakMb", peakRssMb)
        .put("b0", batteryStart).put("b1", batteryEnd).put("c0", batteryTempStartC.toDouble()).put("c1", batteryTempEndC.toDouble())
        .put("th0", thermalStart).put("th1", thermalEnd)

    companion object {
        fun fromJson(o: JSONObject) = Record(
            o.getLong("t"), o.getString("kind"), o.getString("model"), o.getInt("ctx"), o.getInt("threads"), o.getString("device"),
            if (o.isNull("loadMs")) null else o.getLong("loadMs"), o.getInt("pt"), o.getDouble("ptps"), o.getInt("ct"),
            o.getDouble("gtps"), o.getLong("peakMb"), o.getInt("b0"), o.getInt("b1"), o.getDouble("c0").toFloat(),
            o.getDouble("c1").toFloat(), o.getString("th0"), o.getString("th1"),
        )
    }
}

class DiagnosticsStore(paths: Paths) {
    private val file = File(paths.diagnostics, "records.jsonl")
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<Record>> = _records.asStateFlow()

    private fun load(): List<Record> =
        runCatching { file.readLines().filter { it.isNotBlank() }.map { Record.fromJson(JSONObject(it)) } }.getOrDefault(emptyList())

    fun add(r: Record) {
        file.appendText(r.toJson().toString() + "\n")
        _records.value = (_records.value + r).takeLast(500)
    }

    fun clear() { file.delete(); _records.value = emptyList() }

    fun exportJson(): String = JSONArray(_records.value.map { it.toJson() }).toString(2)
}

/** A before/after snapshot of battery and thermal state around one generation. */
class Trend(private val monitor: DeviceMonitor) {
    private val start = monitor.vitals()
    fun finish(
        kind: String, model: String, ctx: Int, threads: Int, device: String, loadMs: Long?, stats: ChatStats,
    ): Record {
        val end = monitor.vitals()
        return Record(
            System.currentTimeMillis(), kind, model, ctx, threads, device, loadMs, stats.promptTokens - stats.cachedTokens,
            stats.promptTokensPerSecond, stats.completionTokens, stats.generationTokensPerSecond, stats.peakRssKb / 1024,
            start.batteryPercent, end.batteryPercent, start.batteryTempC, end.batteryTempC,
            DeviceMonitor.thermalName(start.thermalStatus), DeviceMonitor.thermalName(end.thermalStatus),
        )
    }
}

object BenchmarkSpec {
    const val RUNS = 3
    const val GENERATE_TOKENS = 128
    const val SEED = 1L

    /** Fixed text, so runs are comparable; the nonce defeats Kinetic's prompt-prefix cache. */
    fun prompt(run: Int) = """[{"role":"user","content":"(run $run) Write a Kotlin function that parses a CSV line into a list of fields, handling quoted fields with commas and escaped quotes. Include a short explanation of the approach and two unit-test examples. Be thorough and complete."}]"""
}

/** Runs the repeatable benchmark on the loaded model and stores one record per run. */
suspend fun runBenchmark(app: com.kineai.local.KineApp, onProgress: (String) -> Unit): List<Record> {
    val st = app.engine.state.value as? com.kineai.local.engine.EngineState.Ready ?: error("Load a model first")
    val s = app.settings.value
    val out = mutableListOf<Record>()
    for (run in 1..BenchmarkSpec.RUNS) {
        onProgress("Benchmark run $run of ${BenchmarkSpec.RUNS}…")
        val trend = Trend(app.monitor)
        val stats = app.engine.chat(BenchmarkSpec.prompt(run), BenchmarkSpec.GENERATE_TOKENS, 0f, BenchmarkSpec.SEED) { }
        val rec = trend.finish("benchmark", st.name, s.contextSize, s.threads, app.deviceLabel, if (run == 1) st.loadMs else null, stats)
        app.diagnostics.add(rec); out += rec
    }
    return out
}
