package com.kineai.local

import android.app.Application
import com.kineai.local.data.ConversationStore
import com.kineai.local.data.Paths
import com.kineai.local.data.Settings
import com.kineai.local.device.DeviceMonitor
import com.kineai.local.diagnostics.DiagnosticsStore
import com.kineai.local.engine.EngineClient
import com.kineai.local.models.Downloader
import com.kineai.local.models.EngineCaps
import com.kineai.local.models.HuggingFace
import com.kineai.local.models.ModelStore
import com.kineai.local.workspace.GitService
import com.kineai.local.workspace.Projects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class KineApp : Application() {
    lateinit var paths: Paths
    lateinit var settings: Settings
    lateinit var engine: EngineClient
    lateinit var models: ModelStore
    lateinit var hf: HuggingFace
    lateinit var downloader: Downloader
    lateinit var monitor: DeviceMonitor
    lateinit var diagnostics: DiagnosticsStore
    lateinit var conversations: ConversationStore
    lateinit var projects: Projects
    lateinit var git: GitService
    lateinit var chat: ChatController

    /** The project the chat and terminal are working in, and the files attached to the next message. */
    val selectedProject = MutableStateFlow<java.io.File?>(null)
    val attached = MutableStateFlow<List<String>>(emptyList())

    private val _caps = MutableStateFlow<EngineCaps?>(null)

    /** What the ported engine reports it can run; null until the engine process has answered. */
    val caps: StateFlow<EngineCaps?> = _caps

    /** The compute device that really runs inference, for diagnostics. */
    val deviceLabel: String
        get() {
            val c = _caps.value?.cpuFeatures
            val neon = c?.optBoolean("dotprod") == true
            return if (neon) "CPU (NEON dotprod)" else "CPU (scalar)"
        }

    override fun onCreate() {
        super.onCreate()
        // JGit reads git config from the user's home; Android has none.
        System.setProperty("user.home", filesDir.absolutePath)
        paths = Paths(this)
        settings = Settings(this)
        monitor = DeviceMonitor(this, filesDir)
        engine = EngineClient(this)
        models = ModelStore(paths)
        hf = HuggingFace()
        downloader = Downloader(this, paths, models, hf)
        diagnostics = DiagnosticsStore(paths)
        conversations = ConversationStore(paths)
        projects = Projects(paths)
        git = GitService()
        chat = ChatController(this)
        engine.bind()
    }

    suspend fun refreshCapabilities(): EngineCaps? =
        runCatching { EngineCaps.parse(engine.capabilities()) }.getOrNull()?.also { _caps.value = it }
}
