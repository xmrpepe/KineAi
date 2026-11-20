package com.kineai.local.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EngineSettings(
    val modelFile: String? = null,
    val contextSize: Int = DEFAULT_CONTEXT,
    val threads: Int = DEFAULT_THREADS,
    val device: String = "cpu",
    val temperature: Float = 0.2f,
    val maxTokens: Int = 1024,
    val onboarded: Boolean = false,
) {
    companion object {
        // Conservative until measured on a Pixel 9: Kinetic's KV cache is f32, so context is
        // expensive. 4 threads matches the Tensor G4's X4 + three A720 performance cores.
        const val DEFAULT_CONTEXT = 4096
        const val DEFAULT_THREADS = 4
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("kineai", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<EngineSettings> = _flow.asStateFlow()
    val value get() = _flow.value

    private fun read() =
        EngineSettings(
            modelFile = prefs.getString("model", null),
            contextSize = prefs.getInt("ctx", EngineSettings.DEFAULT_CONTEXT),
            threads = prefs.getInt("threads", EngineSettings.DEFAULT_THREADS),
            device = prefs.getString("device", "cpu")!!,
            temperature = prefs.getFloat("temp", 0.2f),
            maxTokens = prefs.getInt("maxTokens", 1024),
            onboarded = prefs.getBoolean("onboarded", false),
        )

    fun update(f: (EngineSettings) -> EngineSettings) {
        val n = f(_flow.value)
        prefs.edit()
            .putString("model", n.modelFile).putInt("ctx", n.contextSize).putInt("threads", n.threads)
            .putString("device", n.device).putFloat("temp", n.temperature).putInt("maxTokens", n.maxTokens)
            .putBoolean("onboarded", n.onboarded).apply()
        _flow.value = n
    }

    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
}
