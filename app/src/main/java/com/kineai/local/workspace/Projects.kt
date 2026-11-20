package com.kineai.local.workspace

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kineai.local.data.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FileEntry(val file: File, val isDir: Boolean, val size: Long)

class Projects(private val paths: Paths) {
    fun list(): List<File> = paths.projects.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }

    fun create(name: String): File {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifEmpty { "project" }
        var f = File(paths.projects, safe); var i = 1
        while (f.exists()) { f = File(paths.projects, "$safe-$i"); i++ }
        f.mkdirs()
        return f
    }

    /** Deletes one project folder after the UI has confirmed. Models and chats are untouched. */
    fun delete(dir: File) { require(dir.parentFile?.canonicalFile == paths.projects.canonicalFile); dir.deleteRecursively() }

    fun entries(dir: File): List<FileEntry> =
        dir.listFiles().orEmpty().filter { it.name != ".git" || true }
            .map { FileEntry(it, it.isDirectory, if (it.isFile) it.length() else 0) }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.file.name.lowercase() })

    /** Reads a text file for the viewer; refuses binaries and very large files. */
    fun readText(file: File, maxBytes: Long = 2L * 1024 * 1024): Result<String> = runCatching {
        require(file.length() <= maxBytes) { "File is larger than ${maxBytes / 1024 / 1024} MB" }
        val bytes = file.readBytes()
        require(bytes.take(4096).none { it == 0.toByte() }) { "Binary file" }
        bytes.decodeToString()
    }

    /** Copies a folder the user picked (Storage Access Framework tree) into a new project. */
    suspend fun importTree(resolver: ContentResolver, context: android.content.Context, tree: Uri, onFile: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, tree) ?: error("Cannot open that folder")
            val dest = create(root.name ?: "imported")
            fun copy(src: DocumentFile, to: File, depth: Int) {
                if (depth > 32) return
                for (child in src.listFiles()) {
                    val name = child.name?.replace("/", "_") ?: continue
                    val out = File(to, name)
                    if (child.isDirectory) { out.mkdirs(); copy(child, out, depth + 1) }
                    else {
                        onFile(name)
                        resolver.openInputStream(child.uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                }
            }
            copy(root, dest, 0)
            dest
        }
}
