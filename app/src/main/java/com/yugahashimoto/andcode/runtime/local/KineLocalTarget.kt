package com.yugahashimoto.andcode.runtime.local

import com.kineai.local.KineApp
import com.kineai.local.code.Edits
import com.kineai.local.code.LineDiff
import com.kineai.local.data.ChatMessage
import com.kineai.local.data.Conversation
import com.kineai.local.diagnostics.Trend
import com.kineai.local.engine.ChatStats
import com.kineai.local.engine.EngineException
import com.kineai.local.engine.EngineState
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeModelLimit
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodePathInfo
import com.yugahashimoto.andcode.core.api.OpenCodeProject
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSearchText
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeCapabilities
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The only runtime in this app: a chat with the model Kinetic runs on this phone.
 *
 * It implements AndCode's backend interface so AndCode's chat, file, Git and model-picker screens run
 * unchanged on local data. Nothing here opens a network connection. A model's edit proposals are
 * surfaced as permission requests (path + diff); a file is written only if the user taps Allow.
 */
class KineLocalTarget(private val app: KineApp) : RuntimeTarget {
    override val id: String = TARGET_ID
    override val displayName: String = "On-device (Kinetic)"
    override val kind: BackendKind = BackendKind.LOCAL
    override val type: RuntimeType = RuntimeType.LOCAL
    override val agent: LocalAgent = LocalAgent.KINETIC

    override val capabilities =
        RuntimeCapabilities(
            permissions = true,
            providerModelList = true,
            diffCapable = true,
            fileEditing = true,
            configEditing = false,
            mcpOAuth = false,
        )

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Connected(VERSION))
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val bus = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 4096)
    override fun events(): Flow<OpenCodeEvent> = bus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Job>()
    private val pendingEdits = ConcurrentHashMap<String, PendingEdit>()

    private data class PendingEdit(val sessionId: String, val root: File, val path: String, val content: String)

    private val store get() = app.conversations

    // ---------------------------------------------------------------- connection

    override suspend fun connect(): Result<OpenCodeHealth> = Result.success(health())

    override fun disconnect() = Unit

    override suspend fun health() = OpenCodeHealth(healthy = true, version = VERSION)

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        app.projects.list().map { WorkspaceRef(id = it.absolutePath, name = it.name, path = it.absolutePath) }

    // ---------------------------------------------------------------- sessions

    private fun projectDir(c: Conversation): String? = c.project?.let { File(app.paths.projects, it).absolutePath }

    private fun Conversation.toSession(): OpenCodeSession {
        val used = (messages.sumOf { it.content.length } / 3.5).toLong()
        return OpenCodeSession(
            id = id,
            title = title,
            directory = projectDir(this),
            projectId = project,
            time = OpenCodeTime(created = createdAt, updated = updatedAt),
            tokens = OpenCodeSessionTokens(input = used, output = 0),
        )
    }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        withContext(Dispatchers.IO) {
            store.list().filter { directory == null || projectDir(it) == File(directory).absolutePath }.map { it.toSession() }
        }

    override suspend fun session(sessionId: String): OpenCodeSession =
        withContext(Dispatchers.IO) { (store.load(sessionId) ?: error("Session not found: $sessionId")).toSession() }

    override suspend fun createSession(title: String?, directory: String?): OpenCodeSession =
        withContext(Dispatchers.IO) {
            val c = Conversation(title = title?.takeIf { it.isNotBlank() } ?: "New chat", project = directory?.let { File(it).name })
            store.save(c)
            c.toSession().also { bus.tryEmit(OpenCodeEvent.SessionCreated(it)) }
        }

    override suspend fun renameSession(sessionId: String, title: String): OpenCodeSession =
        withContext(Dispatchers.IO) {
            val c = store.load(sessionId)?.copy(title = title) ?: error("Session not found")
            store.save(c)
            c.toSession().also { bus.tryEmit(OpenCodeEvent.SessionUpdated(it)) }
        }

    override suspend fun deleteSession(sessionId: String): Boolean {
        running.remove(sessionId)?.cancel()
        store.delete(sessionId)
        return true
    }

    // ---------------------------------------------------------------- messages

    private fun messageId(index: Int, m: ChatMessage) = "msg-$index-${m.createdAt}"

    private fun ChatMessage.toOpenCode(sessionId: String, index: Int, modelId: String?): OpenCodeMessage {
        val mid = messageId(index, this)
        val done = status != "interrupted" || role == "user"
        return OpenCodeMessage(
            info = OpenCodeMessageInfo(
                id = mid, sessionId = sessionId, role = role,
                time = OpenCodeTime(created = createdAt, completed = if (done) createdAt else null),
                model = modelId?.let { OpenCodeModelReference(PROVIDER_ID, it) },
                error = if (status == "interrupted" && role == "assistant" && content.isNotBlank()) {
                    OpenCodeMessageError(name = "Interrupted", data = mapOf("message" to JsonPrimitive("Interrupted - the engine stopped before finishing. What was generated is kept.")))
                } else null,
            ),
            parts = listOf(OpenCodePart(id = "part-$mid", sessionId = sessionId, messageId = mid, type = "text", text = content)),
        )
    }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> =
        withContext(Dispatchers.IO) {
            val c = store.load(sessionId) ?: return@withContext emptyList()
            c.messages.mapIndexed { i, m -> m.toOpenCode(sessionId, i, c.modelName) }
        }

    // ---------------------------------------------------------------- providers / models

    override suspend fun listProviders(): ProviderCatalog {
        val s = app.settings.value
        val models = app.models.models.value
        val ctx = s.contextSize.toLong()
        val map = models.associate { m ->
            m.file.name to OpenCodeModel(id = m.file.name, providerId = PROVIDER_ID, name = m.displayName, limit = OpenCodeModelLimit(context = ctx, output = s.maxTokens.toLong()))
        }
        val default = s.modelFile?.takeIf { it in map } ?: models.firstOrNull()?.file?.name
        return ProviderCatalog(
            all = listOf(OpenCodeProvider(id = PROVIDER_ID, name = "On-device models", models = map)),
            default = default?.let { mapOf(PROVIDER_ID to it) } ?: emptyMap(),
            connected = if (map.isEmpty()) emptyList() else listOf(PROVIDER_ID),
        )
    }

    override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

    // ---------------------------------------------------------------- sending

    override suspend fun sendMessage(sessionId: String, request: PromptRequest) {
        if (running[sessionId]?.isActive == true) error("The model is still answering. Wait for it or stop it first.")
        running[sessionId] = scope.launch { runTurn(sessionId, request) }
    }

    override suspend fun abortSession(sessionId: String): Boolean {
        running[sessionId]?.cancel()
        return true
    }

    private fun emitStatus(sessionId: String, status: String) = bus.tryEmit(OpenCodeEvent.SessionStatusChanged(sessionId, status))

    private fun emitError(sessionId: String, message: String, name: String? = null) =
        bus.tryEmit(OpenCodeEvent.SessionError(sessionId, message, name))

    /** Makes sure the requested model is the one in memory. Loading is the user's explicit choice of that model. */
    private suspend fun ensureModel(modelId: String?): EngineState.Ready {
        val s = app.settings.value
        val wanted = (modelId ?: s.modelFile) ?: app.models.models.value.firstOrNull()?.file?.name
            ?: throw EngineException("No model is installed. Open Models to import or download one.")
        val model = app.models.models.value.firstOrNull { it.file.name == wanted }
            ?: throw EngineException("The model $wanted is not installed.")
        val st = app.engine.state.value
        if (st is EngineState.Ready && st.path == model.file.absolutePath) return st
        if (st is EngineState.Loading) throw EngineException("A model is still loading.")
        app.settings.update { it.copy(modelFile = model.file.name) }
        app.engine.load(model.file.absolutePath, s.contextSize, s.threads, s.device)
        return app.engine.state.value as? EngineState.Ready ?: throw EngineException("The model did not load.")
    }

    private suspend fun runTurn(sessionId: String, request: PromptRequest) {
        val settings = app.settings.value
        var conv = store.load(sessionId) ?: run { emitError(sessionId, "Session not found"); return }
        emitStatus(sessionId, "busy")
        val ready =
            try {
                ensureModel(request.modelId)
            } catch (e: CancellationException) {
                emitStatus(sessionId, "idle"); throw e
            } catch (e: Exception) {
                emitError(sessionId, e.message ?: "Could not load the model")
                emitStatus(sessionId, "idle")
                return
            }
        val projectRoot = projectDir(conv)?.let(::File)

        // Text attachments (data: URLs) are inlined; other types are not understood by a text model.
        val attachments = request.attachments.mapNotNull { a ->
            val raw = a.url.substringAfter("base64,", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!a.mime.startsWith("text/") && a.mime != "application/json") return@mapNotNull null
            runCatching { "File `${a.filename}`:\n```\n${String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT)).take(200_000)}\n```\n" }.getOrNull()
        }.joinToString("\n")
        val sent = if (attachments.isEmpty()) request.text else "$attachments\n${request.text}"

        val userMsg = ChatMessage("user", request.text)
        val history = conv.messages.filter { !(it.role == "assistant" && it.content.isBlank()) }
        val placeholder = ChatMessage("assistant", "", status = "interrupted")
        conv = conv.copy(
            title = if (conv.messages.isEmpty() && conv.title.isBlank() || conv.title == "New chat") request.text.lineSequence().first().take(48).ifBlank { "New chat" } else conv.title,
            modelName = ready.name, messages = conv.messages + userMsg + placeholder,
        )
        store.save(conv)
        val userIdx = conv.messages.size - 2
        val asstIdx = conv.messages.size - 1
        val userId = messageId(userIdx, userMsg)
        val asstId = messageId(asstIdx, placeholder)
        val partId = "part-$asstId"
        bus.tryEmit(OpenCodeEvent.SessionUpdated(conv.toSession()))
        bus.tryEmit(OpenCodeEvent.MessageUpdated(userMsg.toOpenCode(sessionId, userIdx, conv.modelName).info))
        bus.tryEmit(OpenCodeEvent.MessagePartUpdated(userMsg.toOpenCode(sessionId, userIdx, conv.modelName).parts.first()))
        bus.tryEmit(
            OpenCodeEvent.MessageUpdated(
                OpenCodeMessageInfo(asstId, sessionId, "assistant", OpenCodeTime(created = placeholder.createdAt), model = OpenCodeModelReference(PROVIDER_ID, ready.path.substringAfterLast('/'))),
            ),
        )
        bus.tryEmit(OpenCodeEvent.MessagePartUpdated(OpenCodePart(partId, sessionId, asstId, "text", "")))

        // Context budget: ~3 chars/token for code; drop the oldest turns first.
        val limit = ready.contextLength.coerceAtMost(settings.contextSize)
        val budget = ((limit - settings.maxTokens - 300) * 3).coerceAtLeast(600)
        var chars = Edits.SYSTEM_PROMPT.length + sent.length
        val kept = ArrayDeque<ChatMessage>()
        for (m in history.asReversed()) { if (chars + m.content.length > budget) break; chars += m.content.length; kept.addFirst(m) }
        if (sent.length + Edits.SYSTEM_PROMPT.length > budget) {
            emitError(sessionId, "That message is too long for a ${settings.contextSize}-token context. Raise the context in Settings or send less.")
            emitStatus(sessionId, "idle")
            return
        }
        val payload = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", Edits.SYSTEM_PROMPT))
            kept.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            put(JSONObject().put("role", "user").put("content", sent))
        }.toString()

        val answer = StringBuilder()
        var status = "complete"
        var stats: ChatStats? = null
        var lastSave = 0L
        val trend = Trend(app.monitor)
        try {
            stats = app.engine.chat(payload, settings.maxTokens, settings.temperature, -1L) { piece ->
                answer.append(piece)
                bus.tryEmit(OpenCodeEvent.MessagePartDelta(sessionId, asstId, partId, "text", piece))
                val now = System.currentTimeMillis()
                if (now - lastSave > 1500) {
                    lastSave = now
                    runCatching { store.save(conv.copy(messages = conv.messages.dropLast(1) + placeholder.copy(content = answer.toString()))) }
                }
            }
            if (stats.finish == "cancelled") status = "cancelled"
        } catch (e: CancellationException) {
            status = "cancelled"
        } catch (e: EngineException) {
            status = "interrupted"
            emitError(
                sessionId,
                if (e.engineDied) "The engine stopped (probably low memory). Your chat is saved; the model was not reloaded automatically." else "Generation failed: ${e.message}",
            )
        } finally {
            val text = answer.toString()
            val tps = stats?.generationTokensPerSecond
            conv = conv.copy(messages = conv.messages.dropLast(1) + placeholder.copy(content = text, status = status, tokensPerSecond = tps))
            runCatching { store.save(conv) }
            val done = OpenCodeMessageInfo(
                asstId, sessionId, "assistant", OpenCodeTime(created = placeholder.createdAt, completed = System.currentTimeMillis()),
                model = OpenCodeModelReference(PROVIDER_ID, ready.path.substringAfterLast('/')),
                tokens = stats?.let { OpenCodeSessionTokens(input = (it.promptTokens).toLong(), output = it.completionTokens.toLong()) },
                error = if (status == "cancelled") OpenCodeMessageError(name = "MessageAbortedError") else null,
            )
            bus.tryEmit(OpenCodeEvent.MessagePartUpdated(OpenCodePart(partId, sessionId, asstId, "text", text)))
            bus.tryEmit(OpenCodeEvent.MessageUpdated(done))
            if (stats != null && stats.completionTokens > 0) {
                app.diagnostics.add(trend.finish("chat", ready.name, settings.contextSize, settings.threads, app.deviceLabel, null, stats))
            }
            if (status == "complete") proposeEdits(sessionId, projectRoot, text)
            bus.tryEmit(OpenCodeEvent.SessionUpdated(conv.toSession().copy(tokens = OpenCodeSessionTokens(input = stats?.promptTokens?.toLong() ?: 0, output = stats?.completionTokens?.toLong() ?: 0))))
            emitStatus(sessionId, "idle")
        }
    }

    /** Turns the model's `edit <path>` blocks into permission requests carrying a diff. Nothing is written here. */
    private fun proposeEdits(sessionId: String, root: File?, answer: String) {
        val edits = Edits.parse(answer).mapNotNull { it.edit }
        if (edits.isEmpty()) return
        if (root == null) { emitError(sessionId, "The model proposed file edits, but no project is open to apply them to."); return }
        for (edit in edits) {
            val target = Edits.resolveSafe(root, edit.path)
            if (target == null) { emitError(sessionId, "Refused an edit outside the project: ${edit.path}"); continue }
            val old = target.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            val diff = LineDiff.diff(LineDiff.lines(old.orEmpty()), LineDiff.lines(edit.newContent))
            val summary = diff?.let { d -> "+${d.count { it.kind == com.kineai.local.code.DiffKind.ADDED }} -${d.count { it.kind == com.kineai.local.code.DiffKind.REMOVED }}" } ?: "whole file"
            val shown = diff?.filter { it.kind != com.kineai.local.code.DiffKind.SAME }?.take(40)?.map { (if (it.kind == com.kineai.local.code.DiffKind.ADDED) "+ " else "- ") + it.text }.orEmpty()
            val id = "edit-" + UUID.randomUUID()
            pendingEdits[id] = PendingEdit(sessionId, root, edit.path, edit.newContent)
            bus.tryEmit(
                OpenCodeEvent.PermissionAsked(
                    PermissionRequest(id = id, sessionId = sessionId, permission = (if (old == null) "create " else "edit ") + edit.path + " ($summary)", patterns = shown),
                ),
            )
        }
    }

    override suspend fun respondToPermission(sessionId: String, permissionId: String, response: PermissionResponse, remember: Boolean): Boolean {
        val pending = pendingEdits.remove(permissionId) ?: return false
        if (response != PermissionResponse.REJECT) {
            // "Always" is deliberately treated as "once": the app never auto-applies edits.
            runCatching { WorkspaceFileWriter.writeOrCreate(pending.root, pending.path, pending.content) }
                .onFailure { emitError(sessionId, "Could not apply ${pending.path}: ${it.message}"); return false }
        }
        bus.tryEmit(OpenCodeEvent.PermissionReplied(sessionId, permissionId))
        return true
    }

    // ---------------------------------------------------------------- workspace files

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> =
        app.projects.list().map { OpenCodeProject(id = it.name, worktree = it.absolutePath, name = it.name) }

    override suspend fun currentProject(directory: String?): OpenCodeProject {
        val d = directory?.let(::File) ?: app.projects.list().firstOrNull() ?: app.paths.projects
        return OpenCodeProject(id = d.name, worktree = d.absolutePath, name = d.name)
    }

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo {
        val dir = directory ?: app.paths.projects.absolutePath
        return OpenCodePathInfo(home = app.filesDir.absolutePath, state = app.filesDir.absolutePath, config = app.filesDir.absolutePath, worktree = dir, directory = dir)
    }

    @Suppress("unused")
    private fun resolveUnused(directory: String, path: String): File =
        Edits.resolveSafe(File(directory), path.removePrefix("./").ifEmpty { "." }.let { if (it == ".") "" else it }.ifEmpty { "." }) ?: error("Path leaves the project")

    override suspend fun listFiles(directory: String, path: String): List<OpenCodeFileNode> =
        withContext(Dispatchers.IO) {
            val root = File(directory).canonicalFile
            val dir = if (path == "." || path.isEmpty()) root else File(root, path).canonicalFile
            check(dir.path.startsWith(root.path)) { "Path leaves the project" }
            dir.listFiles().orEmpty().filter { it.name != ".git" }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .map { OpenCodeFileNode(name = it.name, path = it.relativeTo(root).path, absolute = it.absolutePath, type = if (it.isDirectory) "directory" else "file") }
        }

    override suspend fun readFile(directory: String, path: String): OpenCodeFileContent =
        withContext(Dispatchers.IO) {
            val f = Edits.resolveSafe(File(directory).canonicalFile, path) ?: error("Path leaves the project")
            val text = app.projects.readText(f).getOrElse { throw IllegalStateException(it.message) }
            OpenCodeFileContent(type = "text", content = text)
        }

    override suspend fun readFileForEditing(directory: String, path: String): String =
        withContext(Dispatchers.IO) { WorkspaceFileWriter.readForEditing(File(directory), path) }

    override suspend fun writeFile(directory: String, path: String, content: String, expectedContent: String?) =
        withContext(Dispatchers.IO) { WorkspaceFileWriter.write(File(directory), path, content, expectedContent) }

    override suspend fun searchText(directory: String, pattern: String): List<OpenCodeSearchMatch> =
        withContext(Dispatchers.IO) {
            val root = File(directory)
            val out = mutableListOf<OpenCodeSearchMatch>()
            root.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile && it.length() < 1_000_000 }.forEach { f ->
                if (out.size >= 200) return@forEach
                val lines = runCatching { f.readLines() }.getOrNull() ?: return@forEach
                lines.forEachIndexed { i, l ->
                    if (out.size < 200 && l.contains(pattern, ignoreCase = true)) {
                        out += OpenCodeSearchMatch(OpenCodeSearchText(f.relativeTo(root).path), OpenCodeSearchText(l + "\n"), i + 1, 0)
                    }
                }
            }
            out
        }

    override suspend fun findFiles(directory: String, query: String, includeDirectories: Boolean?, type: String?, limit: Int?): List<String> =
        withContext(Dispatchers.IO) {
            val root = File(directory)
            root.walkTopDown().onEnter { it.name != ".git" }.filter { (it.isFile || includeDirectories == true) && it != root && it.name.contains(query, ignoreCase = true) }
                .map { it.relativeTo(root).path }.take(limit ?: 100).toList()
        }

    // ---------------------------------------------------------------- Git (JGit, offline)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo =
        OpenCodeVcsInfo(branch = runCatching { if (app.git.isRepo(File(directory))) app.git.status(File(directory)).branch else null }.getOrNull())

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = vcsStatus(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> {
        val dir = File(directory)
        if (!app.git.isRepo(dir)) return emptyList()
        val s = app.git.status(dir)
        return (s.staged.map { it to "added" } + s.modified.map { it to "modified" } + s.untracked.map { it to "added" } + s.missing.map { it to "deleted" })
            .distinctBy { it.first }.map { (p, st) -> OpenCodeFileChange(file = p, status = st) }
    }

    override suspend fun vcsDiff(directory: String, mode: String, context: Int?): List<OpenCodeFileChange> {
        val dir = File(directory)
        if (!app.git.isRepo(dir)) return emptyList()
        val text = app.git.diff(dir, false) + "\n" + app.git.diff(dir, true)
        return splitDiff(text)
    }

    override suspend fun sessionDiff(sessionId: String, directory: String?, messageId: String?): List<OpenCodeFileChange> =
        if (directory == null) emptyList() else vcsDiff(directory)

    private fun splitDiff(text: String): List<OpenCodeFileChange> {
        val files = mutableListOf<OpenCodeFileChange>()
        var path: String? = null
        val buf = StringBuilder()
        fun flush() {
            val p = path ?: return
            val patch = buf.toString()
            files += OpenCodeFileChange(file = p, patch = patch, added = patch.lines().count { it.startsWith("+") && !it.startsWith("+++") }, removed = patch.lines().count { it.startsWith("-") && !it.startsWith("---") }, status = "modified")
        }
        for (line in text.lines()) {
            if (line.startsWith("diff --git ")) { flush(); buf.clear(); path = line.substringAfterLast(" b/") }
            if (path != null) buf.appendLine(line)
        }
        flush()
        return files
    }

    companion object {
        const val TARGET_ID = "kinetic-local"
        const val PROVIDER_ID = "kinetic"
        const val VERSION = "Kinetic 0.1.0 (on-device)"
    }
}
