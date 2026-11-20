package com.kineai.local.data

import android.content.Context
import java.io.File

/**
 * Three separate trees, so that deleting one never touches another:
 * models (large, re-downloadable), conversations (small, precious), projects (the user's code).
 */
class Paths(private val root: File, cache: File) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    val models: File = File(root, "models").apply { mkdirs() }
    val partials: File = File(root, "models/.partial").apply { mkdirs() }
    val conversations: File = File(root, "conversations").apply { mkdirs() }
    val projects: File = File(root, "projects").apply { mkdirs() }
    val diagnostics: File = File(root, "diagnostics").apply { mkdirs() }
    val exports: File = File(cache, "exports").apply { mkdirs() }
}

/** Writes [text] to [file] so a crash or kill never leaves a half-written file. */
fun atomicWrite(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
    if (!tmp.renameTo(file)) {
        file.delete()
        check(tmp.renameTo(file)) { "could not replace ${file.name}" }
    }
}
