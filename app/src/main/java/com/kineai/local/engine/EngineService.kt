package com.kineai.local.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * Runs Kinetic in its own process (`:engine`). A low-memory kill of this process leaves the UI,
 * the saved conversations and the project files untouched; the UI sees the binder die and shows
 * "engine stopped" instead of silently reloading a multi-gigabyte model.
 *
 * Inference is entirely in-process: the native library has no network imports (verified in CI).
 */
class EngineService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kinetic-native") }
    private val handlerThread = HandlerThread("engine-ipc").apply { start() }
    private val inbox = Handler(handlerThread.looper) { msg -> onMessage(msg); true }
    private val messenger = Messenger(inbox)

    private var handle = 0L
    private var loadedPath: String? = null
    private var threadsApplied = 0
    private val cancelFlag = AtomicLong(-1)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        NativeEngine.ensureLoaded()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && handle != 0L) {
            // Tell the UI; it decides. Nothing is unloaded or restarted silently.
            replyAll(Protocol.MEMORY_PRESSURE, Bundle().apply { putInt(Protocol.K_LEVEL, level) })
        }
    }

    private var client: Messenger? = null

    private fun replyAll(what: Int, data: Bundle) {
        val c = client ?: return
        runCatching { c.send(Message.obtain(null, what).apply { this.data = data }) }
    }

    private fun onMessage(msg: Message) {
        msg.replyTo?.let { client = it }
        val d = msg.data
        val req = d.getLong(Protocol.K_REQ)
        when (msg.what) {
            Protocol.LOAD -> worker.execute { doLoad(req, d) }
            Protocol.UNLOAD -> worker.execute { doUnload(req) }
            Protocol.CHAT -> worker.execute { doChat(req, d) }
            Protocol.CANCEL -> cancelFlag.set(req)
            Protocol.STATUS ->
                replyAll(
                    Protocol.STATUS_REPLY,
                    Bundle().apply {
                        putLong(Protocol.K_REQ, req)
                        putString(Protocol.K_PATH, loadedPath.orEmpty())
                        putString(Protocol.K_JSON, if (handle != 0L) NativeEngine.nativeModelInfo(handle) else "")
                        putLong(Protocol.K_RSS_KB, rssKb("VmRSS"))
                    },
                )
            Protocol.INSPECT ->
                worker.execute {
                    val json = runCatching { NativeEngine.nativeInspect(d.getString(Protocol.K_PATH)!!) }
                        .getOrElse { """{"ok":false,"status":"unsupported","reason":${quote(it.message)}}""" }
                    replyAll(Protocol.INSPECTED, Bundle().apply { putLong(Protocol.K_REQ, req); putString(Protocol.K_JSON, json) })
                }
            Protocol.CAPABILITIES ->
                worker.execute {
                    val json = NativeEngine.nativeCapabilities()
                    val feats = NativeEngine.nativeCpuFeatures()
                    replyAll(
                        Protocol.CAPABILITIES_REPLY,
                        Bundle().apply {
                            putLong(Protocol.K_REQ, req)
                            putString(Protocol.K_JSON, """{"capabilities":$json,"cpu":$feats}""")
                        },
                    )
                }
        }
    }

    private fun quote(s: String?) = org.json.JSONObject.quote(s ?: "error")

    private fun fail(what: Int, req: Long, t: Throwable) =
        replyAll(what, Bundle().apply { putLong(Protocol.K_REQ, req); putString(Protocol.K_ERROR, t.message ?: t.toString()) })

    private fun doLoad(req: Long, d: Bundle) {
        val path = d.getString(Protocol.K_PATH)!!
        val ctx = d.getInt(Protocol.K_CTX)
        val threads = d.getInt(Protocol.K_THREADS).coerceAtLeast(1)
        if (threadsApplied != 0 && threadsApplied != threads) {
            // Kinetic's worker pool is sized once per process. Restart this process; the UI
            // rebinds and retries. The UI is told first, and nothing else is lost.
            replyAll(Protocol.RESTART_NEEDED, Bundle().apply { putLong(Protocol.K_REQ, req) })
            Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 300)
            return
        }
        try {
            if (threadsApplied == 0) {
                NativeEngine.nativeSetThreads(threads)
                threadsApplied = threads
            }
            if (handle != 0L) {
                NativeEngine.nativeUnload(handle)
                handle = 0
            }
            startForegroundCompat("Loading model")
            val t0 = SystemClock.elapsedRealtime()
            handle = NativeEngine.nativeLoad(path, ctx)
            loadedPath = path
            val loadMs = SystemClock.elapsedRealtime() - t0
            replyAll(
                Protocol.LOADED,
                Bundle().apply {
                    putLong(Protocol.K_REQ, req)
                    putString(Protocol.K_JSON, NativeEngine.nativeModelInfo(handle))
                    putLong(Protocol.K_LOAD_MS, loadMs)
                    putLong(Protocol.K_RSS_KB, rssKb("VmRSS"))
                    putLong(Protocol.K_PEAK_RSS_KB, rssKb("VmHWM"))
                },
            )
        } catch (t: Throwable) {
            fail(Protocol.LOAD_FAILED, req, t)
        } finally {
            stopForegroundCompat()
        }
    }

    private fun doUnload(req: Long) {
        runCatching { if (handle != 0L) NativeEngine.nativeUnload(handle) }
        handle = 0
        loadedPath = null
        replyAll(Protocol.UNLOADED, Bundle().apply { putLong(Protocol.K_REQ, req); putLong(Protocol.K_RSS_KB, rssKb("VmRSS")) })
    }

    private fun doChat(req: Long, d: Bundle) {
        if (handle == 0L) {
            fail(Protocol.CHAT_FAILED, req, IllegalStateException("No model is loaded in the engine"))
            return
        }
        try {
            startForegroundCompat("Generating")
            val json =
                NativeEngine.nativeChat(
                    handle,
                    d.getString(Protocol.K_JSON)!!,
                    d.getInt(Protocol.K_MAX_TOKENS),
                    d.getFloat(Protocol.K_TEMP),
                    d.getLong(Protocol.K_SEED),
                ) { piece ->
                    replyAll(Protocol.TOKEN, Bundle().apply { putLong(Protocol.K_REQ, req); putString(Protocol.K_TEXT, piece) })
                    cancelFlag.get() != req
                }
            replyAll(
                Protocol.CHAT_DONE,
                Bundle().apply {
                    putLong(Protocol.K_REQ, req)
                    putString(Protocol.K_JSON, json)
                    putLong(Protocol.K_RSS_KB, rssKb("VmRSS"))
                    putLong(Protocol.K_PEAK_RSS_KB, rssKb("VmHWM"))
                },
            )
        } catch (t: Throwable) {
            fail(Protocol.CHAT_FAILED, req, t)
        } finally {
            stopForegroundCompat()
        }
    }

    private fun rssKb(key: String): Long =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }?.filter { it.isDigit() }?.toLong() ?: 0L
            }
        }.getOrDefault(0L)

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local inference", NotificationManager.IMPORTANCE_LOW))
        val n: Notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("KineAi")
                .setContentText("$text on this device")
                .setOngoing(true)
                .build()
        runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        startService(Intent(this, EngineService::class.java))
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        handlerThread.quitSafely()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val CHANNEL = "inference"
        const val NOTIFICATION_ID = 7
    }
}
