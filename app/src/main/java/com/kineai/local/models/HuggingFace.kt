package com.kineai.local.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HfModel(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val license: String?,
    val architecture: String?,
    val contextLength: Int?,
    val totalParams: Long?,
    val tags: List<String>,
)

data class HfFile(
    val repo: String,
    val path: String,
    val sizeBytes: Long,
    /** SHA-256 from Hugging Face's LFS metadata: a trusted hash for integrity verification. */
    val sha256: String?,
    val isSplit: Boolean,
    val isCompanion: Boolean,
) {
    val quant: String? get() = quantFromFileName(path)
    val url: String get() = "https://huggingface.co/$repo/resolve/main/${path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}"
}

/** Network use: only when the user searches or downloads. Never reached from inference. */
class HuggingFace(val http: OkHttpClient = defaultClient()) {
    suspend fun search(query: String, sort: String = "downloads", limit: Int = 30): List<HfModel> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val url =
                "https://huggingface.co/api/models?search=$q&filter=gguf&sort=$sort&direction=-1&limit=$limit" +
                    "&expand[]=downloads&expand[]=likes&expand[]=tags&expand[]=gguf"
            val arr = JSONArray(get(url))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } }.orEmpty()
                val g = o.optJSONObject("gguf")
                HfModel(
                    id = o.getString("id"),
                    downloads = o.optLong("downloads"),
                    likes = o.optLong("likes"),
                    license = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:"),
                    architecture = g?.optString("architecture")?.ifEmpty { null },
                    contextLength = g?.optInt("context_length")?.takeIf { it > 0 },
                    totalParams = g?.optLong("total")?.takeIf { it > 0 },
                    tags = tags,
                )
            }
        }

    /** Lists the GGUF files of a repo with sizes and trusted hashes. Nothing is downloaded. */
    suspend fun files(repo: String): List<HfFile> =
        withContext(Dispatchers.IO) {
            val arr = JSONArray(get("https://huggingface.co/api/models/$repo/tree/main?recursive=true"))
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.getString("type") == "file" && it.getString("path").endsWith(".gguf", ignoreCase = true) }
                .map { o ->
                    val path = o.getString("path")
                    val lfs = o.optJSONObject("lfs")
                    HfFile(
                        repo, path,
                        sizeBytes = lfs?.optLong("size") ?: o.optLong("size"),
                        sha256 = lfs?.optString("oid")?.takeIf { it.length == 64 },
                        isSplit = Regex("""-\d{5}-of-\d{5}""").containsMatchIn(path),
                        isCompanion = path.contains("mmproj", ignoreCase = true),
                    )
                }
                .sortedBy { it.sizeBytes }
        }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).header("User-Agent", "KineAi-Android").build()).execute().use { r ->
            check(r.isSuccessful) { "Hugging Face answered HTTP ${r.code}" }
            return r.body!!.string()
        }
    }

    companion object {
        fun defaultClient() =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}
