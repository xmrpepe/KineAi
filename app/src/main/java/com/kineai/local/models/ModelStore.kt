package com.kineai.local.models

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.kineai.local.data.Paths
import com.kineai.local.data.atomicWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class InstalledModel(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String?,
    /** True when [sha256] was compared against a trusted hash (Hugging Face LFS metadata). */
    val verified: Boolean,
    val source: String,
    /** Cached nativeInspect JSON, if inspected. */
    val inspect: JSONObject?,
)

/** Models live in their own directory and index, separate from projects and conversations. */
class ModelStore(private val paths: Paths) {
    private val indexFile = File(paths.models, "index.json")
    private val _models = MutableStateFlow<List<InstalledModel>>(emptyList())
    val models: StateFlow<List<InstalledModel>> = _models.asStateFlow()

    init { refresh() }

    private fun readIndex(): JSONObject = runCatching { JSONObject(indexFile.readText()) }.getOrDefault(JSONObject())

    fun refresh() {
        val index = readIndex()
        _models.value =
            paths.models.listFiles { f -> f.isFile && f.extension.equals("gguf", true) }.orEmpty()
                .sortedBy { it.name }
                .map { f ->
                    val e = index.optJSONObject(f.name)
                    InstalledModel(
                        f, e?.optString("name")?.ifEmpty { null } ?: f.nameWithoutExtension, f.length(),
                        e?.optString("sha256")?.ifEmpty { null }, e?.optBoolean("verified") ?: false,
                        e?.optString("source") ?: "imported", e?.optJSONObject("inspect"),
                    )
                }
    }

    private fun mutateIndex(file: String, f: (JSONObject) -> Unit) {
        val index = readIndex()
        val e = index.optJSONObject(file) ?: JSONObject().also { index.put(file, it) }
        f(e)
        atomicWrite(indexFile, index.toString(2))
        refresh()
    }

    fun register(file: File, source: String, sha256: String?, verified: Boolean) =
        mutateIndex(file.name) { it.put("source", source); sha256?.let { h -> it.put("sha256", h) }; it.put("verified", verified) }

    fun rename(model: InstalledModel, name: String) = mutateIndex(model.file.name) { it.put("name", name.trim()) }

    fun saveInspection(model: InstalledModel, inspect: JSONObject) = mutateIndex(model.file.name) { it.put("inspect", inspect) }

    /** Deletes only the weights file and its index entry: chats and projects are separate trees. */
    fun delete(model: InstalledModel) {
        model.file.delete()
        val index = readIndex().apply { remove(model.file.name) }
        atomicWrite(indexFile, index.toString(2))
        refresh()
    }

    fun uniqueName(name: String): File {
        var f = File(paths.models, name)
        var i = 1
        while (f.exists()) { f = File(paths.models, name.removeSuffix(".gguf") + "-$i.gguf"); i++ }
        return f
    }

    /**
     * Imports a GGUF the user picked with the system file picker (Storage Access Framework: the
     * app sees only that one document). It is copied into app storage because the engine
     * memory-maps a real file. Returns the installed file.
     */
    suspend fun import(resolver: ContentResolver, uri: Uri, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            var name = "imported.gguf"
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
            if (!name.endsWith(".gguf", true)) name += ".gguf"
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (size > 0 && size + STORAGE_MARGIN > paths.models.usableSpace) {
                error("Not enough free storage: ${fmt(size)} needed, ${fmt(paths.models.usableSpace)} free")
            }
            val part = File(paths.partials, "import-${System.nanoTime()}.part")
            val md = MessageDigest.getInstance("SHA-256")
            try {
                resolver.openInputStream(uri)!!.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var first = true
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (first) {
                                check(n >= 4 && String(buf, 0, 4) == "GGUF") { "This file is not a GGUF model." }
                                first = false
                            }
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, size)
                        }
                    }
                }
                val dest = uniqueName(name)
                check(part.renameTo(dest)) { "Could not move the imported file into place" }
                register(dest, "imported from device storage", md.digest().joinToString("") { "%02x".format(it) }, false)
                dest
            } finally {
                part.delete()
            }
        }
}

fun sha256Of(file: File, onProgress: (Long) -> Unit = {}): String {
    val md = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buf = ByteArray(1 shl 20)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            done += n
            onProgress(done)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
