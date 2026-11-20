package com.kineai.local.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface EngineState {
    /** Engine process is up (or not yet bound) with no model in memory. */
    data object Idle : EngineState

    data class Loading(val path: String) : EngineState

    data class Ready(
        val path: String,
        val name: String,
        val contextLength: Int,
        val loadMs: Long,
        val rssKb: Long,
        val peakRssKb: Long,
    ) : EngineState

    /**
     * The engine process died (usually the kernel reclaiming memory) or the model failed.
     * The previous model is *not* reloaded automatically; [lastPath] lets the UI offer it.
     */
    data class Stopped(val reason: String, val lastPath: String?) : EngineState
}

data class ChatStats(
    val finish: String,
    val promptTokens: Int,
    val cachedTokens: Int,
    val completionTokens: Int,
    val prefillMs: Double,
    val decodeMs: Double,
    val rssKb: Long,
    val peakRssKb: Long,
) {
    val promptTokensPerSecond get() = if (prefillMs > 0) (promptTokens - cachedTokens) * 1000.0 / prefillMs else 0.0
    val generationTokensPerSecond get() = if (decodeMs > 0) completionTokens * 1000.0 / decodeMs else 0.0
}

class EngineException(message: String, val engineDied: Boolean = false) : Exception(message)

/** UI-side handle on the `:engine` process. All inference requests go through here. */
class EngineClient(private val context: Context) {
    private val ids = AtomicLong(1)
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _pressure = MutableStateFlow(0)

    /** Latest Android trim-memory level reported by the engine process (0 = none). */
    val memoryPressure: StateFlow<Int> = _pressure.asStateFlow()

    private var service: Messenger? = null
    private val connected = MutableStateFlow(false)
    private val pending = ConcurrentHashMap<Long, (Message) -> Unit>()
    private val failures = ConcurrentHashMap<Long, (Throwable) -> Unit>()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var lastPath: String? = null
    private var binding = false

    private val inbound =
        Messenger(
            Handler(Looper.getMainLooper()) { m ->
                if (m.what == Protocol.MEMORY_PRESSURE) {
                    _pressure.value = m.data.getInt(Protocol.K_LEVEL)
                }
                m.data.getLong(Protocol.K_REQ).let { id -> pending[id]?.invoke(m) }
                true
            },
        )

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder)
                connected.value = true
                // A UI restart may find the engine still holding a model: adopt it, don't reload.
                scope.launch { adoptRunningModel() }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                connected.value = false
                val dead = EngineException("The engine process stopped (most likely low memory).", engineDied = true)
                failures.values.toList().forEach { it(dead) }
                failures.clear()
                pending.clear()
                _state.value = EngineState.Stopped(dead.message!!, lastPath)
            }
        }

    fun bind() {
        if (binding) return
        binding = true
        context.bindService(Intent(context, EngineService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private suspend fun awaitConnected(): Messenger {
        bind()
        withTimeoutOrNull(10_000) { connected.let { f -> while (!f.value) kotlinx.coroutines.delay(25) } }
        return service ?: throw EngineException("The engine service did not start", engineDied = true)
    }

    private fun send(what: Int, id: Long, fill: Bundle.() -> Unit = {}) {
        val m = Message.obtain(null, what).apply {
            replyTo = inbound
            data = Bundle().apply { putLong(Protocol.K_REQ, id); fill() }
        }
        service?.send(m) ?: throw EngineException("The engine service is not connected", engineDied = true)
    }

    private suspend fun adoptRunningModel() {
        val id = ids.getAndIncrement()
        val done = CompletableDeferred<Message>()
        pending[id] = { done.complete(it) }
        send(Protocol.STATUS, id)
        val m = withTimeoutOrNull(3_000) { done.await() } ?: return
        pending.remove(id)
        val path = m.data.getString(Protocol.K_PATH).orEmpty()
        if (path.isNotEmpty()) {
            val info = JSONObject(m.data.getString(Protocol.K_JSON).orEmpty().ifEmpty { "{}" })
            lastPath = path
            _state.value =
                EngineState.Ready(
                    path, info.optString("name"), info.optInt("contextLength"), 0,
                    m.data.getLong(Protocol.K_RSS_KB), m.data.getLong(Protocol.K_RSS_KB),
                )
        } else if (_state.value is EngineState.Stopped || _state.value is EngineState.Idle) {
            if (_state.value !is EngineState.Stopped) _state.value = EngineState.Idle
        }
    }

    private suspend fun <T> call(
        what: Int,
        fill: Bundle.() -> Unit,
        ok: Set<Int>,
        bad: Set<Int> = emptySet(),
        map: (Message) -> T,
    ): T {
        awaitConnected()
        val id = ids.getAndIncrement()
        val done = CompletableDeferred<Message>()
        pending[id] = { m -> if (m.what in ok || m.what in bad || m.what == Protocol.RESTART_NEEDED) done.complete(m) }
        failures[id] = { done.completeExceptionally(it) }
        try {
            send(what, id, fill)
            val m = done.await()
            if (m.what in bad) throw EngineException(m.data.getString(Protocol.K_ERROR) ?: "engine error")
            if (m.what == Protocol.RESTART_NEEDED) throw RestartNeeded()
            return map(m)
        } finally {
            pending.remove(id)
            failures.remove(id)
        }
    }

    private class RestartNeeded : Exception()

    /** Capabilities of the ported engine: architectures, per-quantization kernel tier, CPU features. */
    suspend fun capabilities(): JSONObject =
        call(Protocol.CAPABILITIES, {}, setOf(Protocol.CAPABILITIES_REPLY)) { JSONObject(it.data.getString(Protocol.K_JSON)!!) }

    suspend fun inspect(path: String): JSONObject =
        call(Protocol.INSPECT, { putString(Protocol.K_PATH, path) }, setOf(Protocol.INSPECTED)) {
            JSONObject(it.data.getString(Protocol.K_JSON)!!)
        }

    /** Loads [path]. A failed load leaves the engine in [EngineState.Stopped], never half-loaded. */
    suspend fun load(path: String, context: Int, threads: Int) {
        _state.value = EngineState.Loading(path)
        lastPath = path
        try {
            val m =
                try {
                    callLoad(path, context, threads)
                } catch (_: RestartNeeded) {
                    // Different thread count: the engine restarts itself, then we load once more.
                    connected.value = false
                    kotlinx.coroutines.delay(700)
                    callLoad(path, context, threads)
                }
            val info = JSONObject(m.data.getString(Protocol.K_JSON)!!)
            _state.value =
                EngineState.Ready(
                    path, info.optString("name"), info.optInt("contextLength"),
                    m.data.getLong(Protocol.K_LOAD_MS), m.data.getLong(Protocol.K_RSS_KB), m.data.getLong(Protocol.K_PEAK_RSS_KB),
                )
        } catch (e: EngineException) {
            _state.value = EngineState.Stopped("Could not load the model: ${e.message}", path)
            throw e
        }
    }

    private suspend fun callLoad(path: String, context: Int, threads: Int): Message =
        call(
            Protocol.LOAD,
            { putString(Protocol.K_PATH, path); putInt(Protocol.K_CTX, context); putInt(Protocol.K_THREADS, threads) },
            setOf(Protocol.LOADED), setOf(Protocol.LOAD_FAILED),
        ) { it }

    suspend fun unload() {
        call(Protocol.UNLOAD, {}, setOf(Protocol.UNLOADED)) { }
        lastPath = null
        _state.value = EngineState.Idle
    }

    /**
     * Streams a chat answer. [onToken] runs on the main thread. Cancelling the calling coroutine
     * cancels generation in the engine as well.
     */
    suspend fun chat(
        messagesJson: String,
        maxTokens: Int,
        temperature: Float,
        seed: Long,
        onToken: (String) -> Unit,
    ): ChatStats {
        awaitConnected()
        val id = ids.getAndIncrement()
        val done = CompletableDeferred<Message>()
        pending[id] = { m ->
            when (m.what) {
                Protocol.TOKEN -> onToken(m.data.getString(Protocol.K_TEXT).orEmpty())
                Protocol.CHAT_DONE, Protocol.CHAT_FAILED -> done.complete(m)
            }
        }
        failures[id] = { done.completeExceptionally(it) }
        try {
            send(Protocol.CHAT, id) {
                putString(Protocol.K_JSON, messagesJson)
                putInt(Protocol.K_MAX_TOKENS, maxTokens)
                putFloat(Protocol.K_TEMP, temperature)
                putLong(Protocol.K_SEED, seed)
            }
            val m = try {
                done.await()
            } catch (c: kotlinx.coroutines.CancellationException) {
                runCatching { send(Protocol.CANCEL, id) }
                throw c
            }
            if (m.what == Protocol.CHAT_FAILED) throw EngineException(m.data.getString(Protocol.K_ERROR) ?: "generation failed")
            val j = JSONObject(m.data.getString(Protocol.K_JSON)!!)
            return ChatStats(
                j.getString("finish"), j.getInt("promptTokens"), j.getInt("cachedTokens"), j.getInt("completionTokens"),
                j.getDouble("prefillMs"), j.getDouble("decodeMs"), m.data.getLong(Protocol.K_RSS_KB), m.data.getLong(Protocol.K_PEAK_RSS_KB),
            )
        } finally {
            pending.remove(id)
            failures.remove(id)
        }
    }

    /** Explicit user action: stop generating. The coroutine collecting [chat] receives the stats. */
    fun cancel(requestId: Long) = runCatching { send(Protocol.CANCEL, requestId) }
}
