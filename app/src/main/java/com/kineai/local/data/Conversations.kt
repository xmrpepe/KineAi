package com.kineai.local.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** "complete", "cancelled", or "interrupted" (engine died mid-answer; text so far is kept). */
    val status: String = "complete",
    val tokensPerSecond: Double? = null,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val modelName: String? = null,
    val project: String? = null,
    val messages: List<ChatMessage> = emptyList(),
)

/** One JSON file per conversation, written atomically. Survives app updates and model deletion. */
class ConversationStore(private val paths: Paths) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun file(id: String) = File(paths.conversations, "$id.json")

    fun list(): List<Conversation> =
        paths.conversations.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Conversation? = runCatching { json.decodeFromString<Conversation>(file(id).readText()) }.getOrNull()

    fun save(c: Conversation) = atomicWrite(file(c.id), json.encodeToString(c.copy(updatedAt = System.currentTimeMillis())))

    fun delete(id: String) {
        file(id).delete()
    }

    /** Backup: a zip of every conversation. */
    fun exportZip(out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out).use { zip ->
            paths.conversations.listFiles { f -> f.extension == "json" }.orEmpty().forEach { f ->
                zip.putNextEntry(ZipEntry("conversations/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
        }
        return n
    }

    /** Restore: merges a backup; an existing conversation is replaced only by a newer copy. */
    fun importZip(input: InputStream): Int {
        var n = 0
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                if (!e.isDirectory && e.name.endsWith(".json") && e.name.startsWith("conversations/")) {
                    val text = zip.readBytes().decodeToString()
                    val c = runCatching { json.decodeFromString<Conversation>(text) }.getOrNull() ?: return@forEach
                    require(!c.id.contains('/') && !c.id.contains("..")) { "unsafe id" }
                    val existing = load(c.id)
                    if (existing == null || existing.updatedAt < c.updatedAt) {
                        atomicWrite(file(c.id), json.encodeToString(c))
                        n++
                    }
                }
            }
        }
        return n
    }

    fun exportMarkdown(c: Conversation): String =
        buildString {
            appendLine("# ${c.title}")
            c.modelName?.let { appendLine("_Model: $it (local)_\n") }
            c.messages.forEach { appendLine("**${it.role}**\n\n${it.content}\n") }
        }
}
