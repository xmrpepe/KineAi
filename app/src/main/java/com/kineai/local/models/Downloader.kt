package com.kineai.local.models

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kineai.local.data.Paths
import com.kineai.local.data.atomicWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

enum class DlState { RUNNING, PAUSED, FAILED, VERIFYING, DONE }

data class Download(
    val id: String,
    val repo: String,
    val path: String,
    val url: String,
    val totalBytes: Long,
    val sha256: String?,
    val state: DlState,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val bytesPerSecond: Long = 0,
    val verifiedHash: Boolean = false,
) {
    val fileName get() = path.substringAfterLast('/')
    val fraction get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Resumable, single-file downloads. Only the exact GGUF the user picked is fetched, into
 * `models/.partial/<id>.part`, resumed with HTTP Range, verified against Hugging Face's SHA-256
 * when it publishes one, and only then moved into `models/`. Failed or cancelled partials are deleted.
 */
class Downloader(private val context: Context, private val paths: Paths, private val store: ModelStore, private val hf: HuggingFace) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _all = MutableStateFlow<List<Download>>(emptyList())
    val downloads: StateFlow<List<Download>> = _all.asStateFlow()
    private val stateFile = File(paths.partials, "downloads.json")

    init { restore(); cleanOrphans() }

    private fun partFile(id: String) = File(paths.partials, "$id.part")

    private fun persist() {
        val arr = JSONArray()
        _all.value.filter { it.state != DlState.DONE }.forEach {
            arr.put(JSONObject().put("id", it.id).put("repo", it.repo).put("path", it.path).put("url", it.url)
                .put("total", it.totalBytes).put("sha", it.sha256 ?: ""))
        }
        runCatching { atomicWrite(stateFile, arr.toString()) }
    }

    private fun restore() {
        val arr = runCatching { JSONArray(stateFile.readText()) }.getOrNull() ?: return
        _all.value = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val id = it.getString("id")
            Download(id, it.getString("repo"), it.getString("path"), it.getString("url"), it.getLong("total"),
                it.optString("sha").ifEmpty { null }, DlState.PAUSED, partFile(id).takeIf { f -> f.exists() }?.length() ?: 0)
        }
    }

    /** Deletes partial files that no download record owns (e.g. after a crash mid-import). */
    private fun cleanOrphans() {
        val owned = _all.value.map { "${it.id}.part" }.toSet()
        paths.partials.listFiles { f -> f.extension == "part" && f.name !in owned }?.forEach { it.delete() }
    }

    fun start(file: HfFile) {
        val id = (file.repo + "/" + file.path).hashCode().toUInt().toString(16) + "-" + file.sizeBytes
        if (_all.value.any { it.id == id && it.state != DlState.DONE && it.state != DlState.FAILED }) { resume(id); return }
        _all.update { l -> l.filter { it.id != id } + Download(id, file.repo, file.path, file.url, file.sizeBytes, file.sha256, DlState.PAUSED, partFile(id).takeIf { f -> f.exists() }?.length() ?: 0) }
        persist()
        resume(id)
    }

    private fun mutate(id: String, f: (Download) -> Download) = _all.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun resume(id: String) {
        if (jobs[id]?.isActive == true) return
        mutate(id) { it.copy(state = DlState.RUNNING, error = null) }
        notifyService()
        jobs[id] = scope.launch {
            try { run(id) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                mutate(id) { it.copy(state = DlState.FAILED, error = e.message ?: e.toString()) }
            } finally { notifyService() }
        }
    }

    fun pause(id: String) {
        jobs[id]?.cancel()
        mutate(id) { it.copy(state = DlState.PAUSED, bytesPerSecond = 0) }
        notifyService()
    }

    /** Stops and removes the incomplete file. */
    fun cancel(id: String) {
        jobs[id]?.cancel()
        partFile(id).delete()
        _all.update { l -> l.filter { it.id != id } }
        persist(); notifyService()
    }

    fun dismiss(id: String) { _all.update { l -> l.filter { it.id != id } }; persist() }

    private fun notifyService() {
        val active = _all.value.count { it.state == DlState.RUNNING || it.state == DlState.VERIFYING }
        val i = Intent(context, DownloadService::class.java).putExtra("active", active)
        if (active > 0) ContextCompat.startForegroundService(context, i) else context.startService(i)
    }

    private suspend fun run(id: String) {
        val d = _all.value.first { it.id == id }
        val part = partFile(id)
        var have = if (part.exists()) part.length() else 0L
        if (have > d.totalBytes && d.totalBytes > 0) { part.delete(); have = 0 }
        val needed = (d.totalBytes - have).coerceAtLeast(0)
        if (needed + STORAGE_MARGIN > paths.models.usableSpace) {
            error("Not enough free storage: ${fmt(needed)} more needed, ${fmt(paths.models.usableSpace)} free")
        }
        if (have < d.totalBytes || d.totalBytes <= 0) {
            val req = Request.Builder().url(d.url).header("User-Agent", "KineAi-Android").apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            hf.http.newCall(req).execute().use { r ->
                if (r.code == 416) { /* already complete */ } else {
                    check(r.isSuccessful) { "Hugging Face answered HTTP ${r.code}" }
                    if (r.code == 200 && have > 0) { part.delete(); have = 0 } // server ignored Range: restart cleanly
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(have)
                        val body = r.body!!.byteStream()
                        val buf = ByteArray(1 shl 16)
                        var done = have; var winStart = System.nanoTime(); var winBytes = 0L
                        while (true) {
                            val n = body.read(buf)
                            if (n < 0) break
                            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw CancellationException()
                            raf.write(buf, 0, n); done += n; winBytes += n
                            val now = System.nanoTime()
                            if (now - winStart > 500_000_000L) {
                                val bps = winBytes * 1_000_000_000L / (now - winStart)
                                mutate(id) { it.copy(downloadedBytes = done, bytesPerSecond = bps) }
                                winStart = now; winBytes = 0
                            }
                        }
                        mutate(id) { it.copy(downloadedBytes = done) }
                    }
                }
            }
        }
        if (d.totalBytes > 0 && part.length() != d.totalBytes) error("Connection ended early (${fmt(part.length())} of ${fmt(d.totalBytes)}); resume to continue.")
        mutate(id) { it.copy(state = DlState.VERIFYING, bytesPerSecond = 0) }
        val magic = part.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (String(magic) != "GGUF") { part.delete(); error("The downloaded file is not a GGUF model.") }
        val actual = sha256Of(part)
        if (d.sha256 != null && !actual.equals(d.sha256, true)) { part.delete(); mutate(id) { it.copy(downloadedBytes = 0) }; error("Integrity check failed: SHA-256 does not match Hugging Face's published hash. The file was deleted; start the download again.") }
        val dest = store.uniqueName(d.fileName)
        check(part.renameTo(dest)) { "Could not move the finished download into place" }
        store.register(dest, "huggingface.co/${d.repo}", actual, verified = d.sha256 != null)
        mutate(id) { it.copy(state = DlState.DONE, verifiedHash = d.sha256 != null) }
        persist()
    }
}
