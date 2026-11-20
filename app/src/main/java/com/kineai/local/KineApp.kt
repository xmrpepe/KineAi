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
import kotlinx.coroutines.launch

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
            val ready = engine.state.value as? com.kineai.local.engine.EngineState.Ready
            if (ready != null && ready.device.startsWith("Vulkan")) return "GPU (${ready.device})"
            val neon = _caps.value?.cpuFeatures?.optBoolean("dotprod") == true
            return if (neon) "CPU (NEON dotprod)" else "CPU (scalar)"
        }

    /** Set when the GPU path was switched off automatically, so the UI can explain it. */
    val gpuNotice = MutableStateFlow<String?>(null)
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

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
        watchGpuCrashes()
    }

    /**
     * The Vulkan path is experimental on Android GPUs. If the engine process dies while a GPU load
     * or GPU-backed model is active, switch back to the CPU for good (until the user re-enables it)
     * instead of crashing again on the next load.
     */
    private fun watchGpuCrashes() {
        appScope.launch {
            var gpuActive = false
            engine.state.collect { st ->
                when (st) {
                    is com.kineai.local.engine.EngineState.Loading -> gpuActive = settings.value.device == "vulkan"
                    is com.kineai.local.engine.EngineState.Ready -> {
                        gpuActive = st.device.startsWith("Vulkan")
                        st.deviceNote?.let { gpuNotice.value = it }
                    }
                    is com.kineai.local.engine.EngineState.Stopped -> {
                        if (gpuActive && st.reason.contains("engine process stopped", true)) {
                            settings.update { it.copy(device = "cpu") }
                            gpuNotice.value = "The GPU path crashed the engine, so KineAi switched back to the CPU. You can re-enable GPU in Settings."
                        }
                        gpuActive = false
                    }
                    else -> {}
                }
            }
        }
    }

    /** Why the last capability query failed, shown instead of a generic "not reachable". */
    @Volatile var capsError: String? = null

    suspend fun refreshCapabilities(): EngineCaps? =
        runCatching { EngineCaps.parse(engine.capabilities()) }
            .onFailure { capsError = "${it::class.java.simpleName}: ${it.message}"; android.util.Log.e("KineAi", "capabilities failed", it) }
            .getOrNull()?.also { _caps.value = it; capsError = null }
}
