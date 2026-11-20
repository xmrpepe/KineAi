package com.kineai.local.code

import java.io.File

/** A file change the model proposed. Nothing is applied until the user approves it. */
data class EditProposal(val path: String, val newContent: String)

data class Segment(val text: String, val edit: EditProposal? = null, val language: String? = null, val isCode: Boolean = false)

object Edits {
    const val SYSTEM_PROMPT = """You are a coding assistant running fully on the user's phone. You cannot run commands, browse, or change files yourself.
When the user asks for a change to a file, reply with a short explanation and then ONE fenced block per file whose info string is `edit` followed by the file path relative to the project, containing the COMPLETE new content of that file. Example:
```edit src/main.py
print("hello")
```
The user reviews a diff and decides whether to apply it. For explanations or snippets that are not edits, use ordinary fenced blocks with a language name."""

    private val FENCE = Regex("```([^\\n`]*)\\n(.*?)(```|\\z)", RegexOption.DOT_MATCHES_ALL)

    /** Splits an answer into prose, code blocks and edit proposals. Tolerates an unclosed fence while streaming. */
    fun parse(answer: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var last = 0
        for (m in FENCE.findAll(answer)) {
            if (m.range.first > last) out += Segment(answer.substring(last, m.range.first))
            val info = m.groupValues[1].trim()
            val body = m.groupValues[2].removeSuffix("\n")
            val closed = m.groupValues[3] == "```"
            if (info.startsWith("edit ") && closed) {
                val p = info.removePrefix("edit ").trim()
                out += Segment(body, EditProposal(p, body + "\n"), "edit", true)
            } else {
                out += Segment(body, null, info.substringBefore(' ').ifEmpty { null }, true)
            }
            last = m.range.last + 1
        }
        if (last < answer.length) out += Segment(answer.substring(last))
        return out
    }

    /** Resolves [relative] inside [root]; refuses anything that escapes the project folder. */
    fun resolveSafe(root: File, relative: String): File? {
        if (relative.isBlank() || relative.startsWith("/") || relative.split('/', '\\').any { it == ".." }) return null
        val f = File(root, relative).canonicalFile
        return f.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }
    }
}

enum class DiffKind { SAME, ADDED, REMOVED }

data class DiffLine(val kind: DiffKind, val text: String)

object LineDiff {
    private const val MAX_MIDDLE = 2500

    /** Line diff with common head/tail trimmed. Returns null when the changed region is too large to diff cheaply. */
    fun diff(old: List<String>, new: List<String>): List<DiffLine>? {
        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) head++
        var tail = 0
        while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++
        val a = old.subList(head, old.size - tail)
        val b = new.subList(head, new.size - tail)
        if (a.size > MAX_MIDDLE || b.size > MAX_MIDDLE) return null
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<DiffLine>(old.size + new.size)
        for (i in 0 until head) out += DiffLine(DiffKind.SAME, old[i])
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { out += DiffLine(DiffKind.SAME, a[i]); i++; j++ }
                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { out += DiffLine(DiffKind.REMOVED, a[i]); i++ }
                else -> { out += DiffLine(DiffKind.ADDED, b[j]); j++ }
            }
        }
        for (k in old.size - tail until old.size) out += DiffLine(DiffKind.SAME, old[k])
        return out
    }

    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")
}
