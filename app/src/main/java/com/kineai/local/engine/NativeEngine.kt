package com.kineai.local.engine

/**
 * JNI surface of `libkinetic_android.so` (crates/kinetic-android). Only ever loaded in the
 * `:engine` process; the UI process talks to it through [EngineService].
 */
object NativeEngine {
    fun interface TokenSink {
        /** Return false to cancel generation. */
        fun onToken(piece: String): Boolean
    }

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("kinetic_android")
            loaded = true
        }
    }

    @JvmStatic external fun nativeVersion(): String

    @JvmStatic external fun nativeSetThreads(threads: Int): Boolean

    @JvmStatic external fun nativeCpuFeatures(): String

    @JvmStatic external fun nativeCapabilities(): String

    @JvmStatic external fun nativeInspect(path: String): String

    /** [device] is "cpu" or "vulkan"; a failed Vulkan start falls back to the CPU and says why in model info. */
    @JvmStatic external fun nativeLoad(path: String, context: Int, device: String): Long

    @JvmStatic external fun nativeUnload(handle: Long)

    @JvmStatic external fun nativeModelInfo(handle: Long): String

    @JvmStatic external fun nativeCountTokens(handle: Long, text: String): Int

    @JvmStatic
    external fun nativeChat(
        handle: Long,
        messagesJson: String,
        maxTokens: Int,
        temperature: Float,
        seed: Long,
        sink: TokenSink,
    ): String
}
