package com.kineai.local

import com.kineai.local.code.EditProposal
import com.kineai.local.code.Edits
import com.kineai.local.data.ChatMessage
import com.kineai.local.data.Conversation
import com.kineai.local.diagnostics.Trend
import com.kineai.local.engine.ChatStats
import com.kineai.local.engine.EngineException
import com.kineai.local.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatUi(
    val conversation: Conversation = Conversation(),
    val generating: Boolean = false,
    val notice: String? = null,
    val lastStats: ChatStats? = null,
)

/** Result of applying an approved edit; [previous] lets the user undo it. */
data class Applied(val file: File, val previous: String?)

/**
 * Direct chat with the loaded local model. Generation lives in an app-level scope, so leaving the
 * chat screen does not stop it, and every token is persisted so a killed engine loses nothing.
 */
class ChatController(private val app: KineApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(ChatUi(conversation = app.conversations.list().firstOrNull() ?: Conversation()))
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var lastSaved = 0L

    fun newConversation() { if (!_ui.value.generating) _ui.value = ChatUi() }

    fun open(id: String) {
        if (_ui.value.generating) return
        app.conversations.load(id)?.let { _ui.value = ChatUi(conversation = it) }
    }

    fun delete(id: String) {
        app.conversations.delete(id)
        if (_ui.value.conversation.id == id && !_ui.value.generating) _ui.value = ChatUi()
    }

    fun dismissNotice() { _ui.value = _ui.value.copy(notice = null) }

    fun stop() { job?.cancel() }

    private fun persist(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < 1500) return
        lastSaved = now
        runCatching { app.conversations.save(_ui.value.conversation) }
    }

    private fun mutateConversation(f: (Conversation) -> Conversation) { _ui.value = _ui.value.copy(conversation = f(_ui.value.conversation)) }

    /**
     * Sends [text], plus the current contents of [attached] files from [project]. Only the model's
     * words come back: nothing is executed or written as a result.
     */
    fun send(text: String, project: File?, attached: List<String>) {
        val state = app.engine.state.value
        if (_ui.value.generating) return
        if (state !is EngineState.Ready) {
            _ui.value = _ui.value.copy(notice = "No model is loaded. Open Models and load one first.")
            return
        }
        val settings = app.settings.value
        val sys = Edits.SYSTEM_PROMPT
        val attachBlock = attached.mapNotNull { rel ->
            project?.let { Edits.resolveSafe(it, rel) }?.takeIf { it.isFile }?.let { f ->
                app.projects.readText(f, 200_000).getOrNull()?.let { "File `$rel`:\n```\n$it\n```\n" }
            }
        }.joinToString("\n")
        val sent = if (attachBlock.isEmpty()) text else "$attachBlock\n$text"

        val userMsg = ChatMessage("user", text + attached.takeIf { it.isNotEmpty() }?.joinToString(prefix = "\n\n_Attached: ", postfix = "_") { "`$it`" }.orEmpty())
        val placeholder = ChatMessage("assistant", "", status = "interrupted")
        val history = _ui.value.conversation.messages.filter { !(it.role == "assistant" && it.content.isBlank()) }
        mutateConversation { c ->
            c.copy(
                title = if (c.messages.isEmpty()) text.lineSequence().first().take(48).ifBlank { "New chat" } else c.title,
                modelName = state.name, project = project?.name ?: c.project,
                messages = c.messages + userMsg + placeholder,
            )
        }
        persist(force = true)

        // Context budget: ~3 chars/token is a conservative estimate for code. Drop oldest turns first.
        val budgetChars = ((state.contextLength.coerceAtMost(settings.contextSize) - settings.maxTokens - 300) * 3).coerceAtLeast(600)
        var chars = sys.length + sent.length
        val kept = ArrayDeque<ChatMessage>()
        for (m in history.asReversed()) { if (chars + m.content.length > budgetChars) break; chars += m.content.length; kept.addFirst(m) }
        val dropped = history.size - kept.size
        if (sent.length + sys.length > budgetChars) {
            mutateConversation { c -> c.copy(messages = c.messages.dropLast(2)) }
            _ui.value = _ui.value.copy(notice = "That message (with attachments) is too long for a ${settings.contextSize}-token context. Attach less, or raise the context in Settings.")
            return
        }
        val payload = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", sys))
            kept.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            put(JSONObject().put("role", "user").put("content", sent))
        }.toString()

        _ui.value = _ui.value.copy(generating = true, notice = if (dropped > 0) "Older messages ($dropped) were left out to fit the context window." else null)
        val trend = Trend(app.monitor)
        job = scope.launch {
            val answer = StringBuilder()
            var status = "complete"
            var stats: ChatStats? = null
            try {
                stats = app.engine.chat(payload, settings.maxTokens, settings.temperature, -1L) { piece ->
                    answer.append(piece)
                    mutateConversation { c -> c.copy(messages = c.messages.dropLast(1) + placeholder.copy(content = answer.toString())) }
                    persist()
                }
                if (stats.finish == "cancelled") status = "cancelled"
                else if (stats.finish == "length") status = "complete"
            } catch (e: CancellationException) {
                status = "cancelled"
            } catch (e: EngineException) {
                status = "interrupted"
                _ui.value = _ui.value.copy(
                    notice = if (e.engineDied) "The engine stopped (probably low memory). Your conversation is saved. The model was not reloaded automatically: open Models to load it again."
                    else "Generation failed: ${e.message}",
                )
            } finally {
                val tps = stats?.generationTokensPerSecond
                mutateConversation { c -> c.copy(messages = c.messages.dropLast(1) + placeholder.copy(content = answer.toString(), status = status, tokensPerSecond = tps)) }
                _ui.value = _ui.value.copy(generating = false, lastStats = stats ?: _ui.value.lastStats)
                persist(force = true)
                if (stats != null && stats.completionTokens > 0) {
                    app.diagnostics.add(trend.finish("chat", state.name, settings.contextSize, settings.threads, app.deviceLabel, null, stats))
                }
            }
        }
    }

    /** Writes an edit the user approved. Returns what is needed to undo it. */
    fun applyEdit(project: File, edit: EditProposal): Result<Applied> = runCatching {
        val target = Edits.resolveSafe(project, edit.path) ?: error("Refusing to write outside the project: ${edit.path}")
        val previous = if (target.isFile) target.readText() else null
        target.parentFile?.mkdirs()
        com.kineai.local.data.atomicWrite(target, edit.newContent)
        Applied(target, previous)
    }

    fun undo(applied: Applied) {
        if (applied.previous == null) applied.file.delete() else com.kineai.local.data.atomicWrite(applied.file, applied.previous)
    }
}
