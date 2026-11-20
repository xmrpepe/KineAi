package com.kineai.local.models

import org.json.JSONObject

enum class Fit(val label: String) {
    COMPATIBLE("Compatible"),
    LIMITED("May run with limits"),
    UNSUPPORTED("Unsupported"),
}

data class Verdict(
    val fit: Fit,
    val reasons: List<String>,
    /** Estimated peak memory for weights + f32 KV cache at the chosen context, in bytes. */
    val estimatedMemoryBytes: Long,
    val kvBytes: Long,
)

/** What the ported engine can run, as reported by libkinetic_android.so itself. */
data class EngineCaps(
    val architectures: Set<String>,
    /** quantization name (normalized, e.g. "Q4K") -> "neon" | "integer" | "reference" | "float" */
    val quantTiers: Map<String, String>,
    val gpu: String,
    val gpuReason: String,
    val cpuFeatures: JSONObject,
) {
    companion object {
        fun parse(json: JSONObject): EngineCaps {
            val caps = json.getJSONObject("capabilities")
            val archs = caps.getJSONArray("architectures").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val q = caps.getJSONObject("quantizations")
            return EngineCaps(
                archs,
                q.keys().asSequence().associate { normalizeQuant(it) to q.getString(it) },
                caps.optString("gpu", "none"),
                caps.optString("gpuReason", ""),
                json.optJSONObject("cpu") ?: JSONObject(),
            )
        }
    }
}

data class DeviceBudget(val totalRamBytes: Long, val availRamBytes: Long, val freeStorageBytes: Long)

fun normalizeQuant(s: String) = s.uppercase().replace("_", "").replace("-", "")

private val QUANT_RE = Regex("""(?i)(?<![A-Za-z0-9])((?:IQ|TQ|Q|MXFP)\d(?:_[0-9A-Z]+)*|BF16|F16|F32)(?![A-Za-z0-9])""")

/** The quantization named in a GGUF file name, e.g. "Qwen-Q4_K_M.gguf" -> "Q4_K_M". */
fun quantFromFileName(name: String): String? = QUANT_RE.findAll(name).lastOrNull()?.groupValues?.get(1)?.uppercase()

/**
 * Maps a file-name quantization (Q4_K_M, Q5_K_S, IQ4_XS ...) to the tensor type that dominates it
 * and which kernel tier the engine has for it. K-quant mixes also contain a few Q6_K/Q5_K/Q8_0
 * tensors; those are covered by the same tiers, so the dominant type decides.
 */
fun tierFor(quant: String, caps: EngineCaps): String? {
    val q = normalizeQuant(quant)
    val base =
        when {
            q.startsWith("Q4K") -> "Q4K"
            q.startsWith("Q5K") -> "Q5K"
            q.startsWith("Q6K") -> "Q6K"
            q.startsWith("Q3K") -> "Q3K"
            q.startsWith("Q2K") -> "Q2K"
            q.startsWith("IQ4NL") -> "IQ4NL"
            q.startsWith("IQ4XS") -> "IQ4XS"
            q.startsWith("IQ3XXS") -> "IQ3XXS"
            q.startsWith("IQ3") -> "IQ3S"
            q.startsWith("IQ2XXS") -> "IQ2XXS"
            q.startsWith("IQ2XS") -> "IQ2XS"
            q.startsWith("IQ2") -> "IQ2S"
            q.startsWith("IQ1S") -> "IQ1S"
            q.startsWith("IQ1M") -> "IQ1M"
            else -> q
        }
    return caps.quantTiers[base]
}

/** Estimated f32 KV-cache bytes. With layer geometry it is exact; without, a pessimistic fallback. */
fun kvBytes(context: Int, layers: Int?, kvDim: Int?, fileBytes: Long): Long =
    if (layers != null && kvDim != null) {
        2L * layers * context * kvDim * 4 // K and V, f32: Kinetic v0.1.0 keeps the cache in f32
    } else {
        // ~120 KB of f32 KV per token per GB of weights across common 1B-8B models (measured
        // from published layer geometry), so this errs on the safe side.
        (context.toLong() * (fileBytes / 1e9) * 120_000).toLong()
    }

fun classify(
    architecture: String?,
    quant: String?,
    fileBytes: Long,
    context: Int,
    layers: Int?,
    kvDim: Int?,
    split: Boolean,
    alreadyOnDisk: Boolean,
    caps: EngineCaps,
    device: DeviceBudget,
): Verdict {
    val reasons = mutableListOf<String>()
    val kv = kvBytes(context, layers, kvDim, fileBytes)
    val need = fileBytes + kv + OVERHEAD
    var fit = Fit.COMPATIBLE

    fun worst(f: Fit) { if (f.ordinal > fit.ordinal) fit = f }

    if (split) {
        reasons += "Split GGUF (multi-part) files cannot be loaded; pick a single-file quantization."
        worst(Fit.UNSUPPORTED)
    }
    if (architecture != null && architecture !in caps.architectures) {
        reasons += "Architecture \"$architecture\" is not implemented by Kinetic v0.1.0."
        worst(Fit.UNSUPPORTED)
    } else if (architecture == null) {
        reasons += "Architecture not known until the file is inspected."
        worst(Fit.LIMITED)
    }
    if (quant != null) {
        when (tierFor(quant, caps)) {
            null -> { reasons += "Quantization $quant is not recognized by the engine."; worst(Fit.UNSUPPORTED) }
            "reference" -> { reasons += "$quant has no integer kernel: it runs through the slow f32 reference path."; worst(Fit.LIMITED) }
            "integer" -> reasons += "$quant uses the scalar integer kernel (no NEON kernel yet), so it is slower than Q4_K, Q6_K, Q8_0, Q4_0 or Q5_0."
        }
    }
    if (!alreadyOnDisk && fileBytes + STORAGE_MARGIN > device.freeStorageBytes) {
        reasons += "Not enough free storage (${fmt(fileBytes)} needed, ${fmt(device.freeStorageBytes)} free)."
        worst(Fit.UNSUPPORTED)
    }
    if (need > device.totalRamBytes * 0.85) {
        reasons += "Needs about ${fmt(need)} at $context tokens of context; this phone has ${fmt(device.totalRamBytes)} of RAM."
        worst(Fit.UNSUPPORTED)
    } else if (need > device.totalRamBytes * 0.55 || need > device.availRamBytes) {
        reasons += "Needs about ${fmt(need)} at $context tokens; ${fmt(device.availRamBytes)} is free right now. Close apps or lower the context."
        worst(Fit.LIMITED)
    }
    if (reasons.isEmpty()) reasons += "Fits: about ${fmt(need)} at $context tokens."
    return Verdict(fit, reasons, need, kv)
}

const val OVERHEAD = 400L * 1024 * 1024
const val STORAGE_MARGIN = 512L * 1024 * 1024

fun fmt(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        else -> "$bytes B"
    }
