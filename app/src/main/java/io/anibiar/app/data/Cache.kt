package io.anibiar.app.data

import io.anibiar.app.App
import io.anibiar.app.core.Config
import io.anibiar.app.core.Net
import io.anibiar.app.core.parseObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class CacheHit(val atMs: Long, val data: String) {
    val ageMinutes: Long get() = (System.currentTimeMillis() - atMs) / 60_000
    val ageDays: Long get() = ageMinutes / (60 * 24)
}

/**
 * Two layers, both optional and both fail-safe (a broken cache never breaks the app):
 *   1. a file on the phone (instant, works offline)
 *   2. Upstash Redis over its REST API, only when its URL and token were given at build time
 * Values are JSON text.
 */
object AppCache {
    private const val PREFIX = "app:v1:"
    private val dir: File by lazy { File(App.ctx.filesDir, "cache").apply { mkdirs() } }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    private fun parse(raw: String): CacheHit? {
        val o = parseObject(raw) ?: return null
        val at = o.optLong("at", 0)
        val d = o.opt("d") as? String ?: return null
        return CacheHit(at, d)
    }

    /** Only the file on the phone. Instant. */
    suspend fun readLocal(key: String): CacheHit? = withContext(Dispatchers.IO) {
        try {
            val f = file(key)
            if (f.exists()) parse(f.readText()) else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun read(key: String, useUpstash: Boolean = true): CacheHit? {
        readLocal(key)?.let { return it }
        if (!useUpstash || !Config.upstashOn) return null
        return try {
            val res = Net.get(
                "${Config.upstashUrl}/get/${URLEncoder.encode(PREFIX + key, "UTF-8")}",
                mapOf("Authorization" to "Bearer ${Config.upstashToken}"),
                timeoutMs = 4_000,
            )
            if (!res.ok) return null
            val v = parseObject(res.body)?.opt("result") as? String ?: return null
            val hit = parse(v) ?: return null
            writeFile(key, v)
            hit
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun writeFile(key: String, raw: String) = withContext(Dispatchers.IO) {
        try { file(key).writeText(raw) } catch (_: Exception) {}
    }

    suspend fun write(key: String, data: String, ttlSeconds: Int = 86_400, useUpstash: Boolean = true) {
        val raw = JSONObject().put("at", System.currentTimeMillis()).put("d", data).toString()
        writeFile(key, raw)
        // Upstash caps a request body at about 1 MB
        if (!useUpstash || !Config.upstashOn || raw.length > 900_000) return
        try {
            val cmd = JSONArray().put("SET").put(PREFIX + key).put(raw).put("EX").put(ttlSeconds)
            Net.postJson(
                Config.upstashUrl,
                cmd.toString(),
                mapOf("Authorization" to "Bearer ${Config.upstashToken}"),
                timeoutMs = 6_000,
            )
        } catch (_: Exception) {
        }
    }
}
