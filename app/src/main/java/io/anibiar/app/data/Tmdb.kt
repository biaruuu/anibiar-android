package io.anibiar.app.data

import io.anibiar.app.core.Config
import io.anibiar.app.core.Net
import io.anibiar.app.core.arr
import io.anibiar.app.core.dbl
import io.anibiar.app.core.int
import io.anibiar.app.core.jsonOf
import io.anibiar.app.core.objects
import io.anibiar.app.core.parseArray
import io.anibiar.app.core.parseObject
import io.anibiar.app.core.str
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class Artwork(val kind: String, val url: String) // kind: Backgrounds | Banners | Posters | Logos

/**
 * Title logos and artwork from TMDB, the same source and the same picking rules as the website (lib/logos.ts).
 * Needs TMDB_KEY at build time; without it the hero simply shows the plain text title.
 */
object Tmdb {
    val enabled: Boolean get() = Config.tmdbOn

    private val logos = ConcurrentHashMap<String, String>() // "" = known to have none
    private val art = ConcurrentHashMap<String, List<Artwork>>()

    private fun fnv(s: String): String {
        var h = 0x811c9dc5L
        for (c in s) h = ((h xor c.code.toLong()) * 0x01000193L) and 0xFFFFFFFFL
        return h.toString(36)
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        return try {
            val q = (params + ("api_key" to Config.tmdbKey)).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val res = Net.get("https://api.themoviedb.org/3/$path?$q", mapOf("Accept" to "application/json"), 10_000)
            if (res.ok) parseObject(res.body) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun kinds(movie: Boolean) = if (movie) listOf("movie", "tv") else listOf("tv", "movie")

    /** The best English logo (highest vote, then widest), or any logo when there is no English one. */
    suspend fun logo(name: String, movie: Boolean): String? {
        if (!enabled || name.isBlank()) return null
        val key = (if (movie) "m:" else "t:") + name
        logos[key]?.let { return it.ifEmpty { null } }
        val ck = "logo:${fnv(key)}"
        val hit = AppCache.read(ck)
        if (hit != null && hit.ageDays < 7) {
            val u = parseObject(hit.data)?.str("u") ?: ""
            logos[key] = u
            return u.ifEmpty { null }
        }
        var url: String? = null
        var failed = false
        for (kind in kinds(movie)) {
            val s = get("search/$kind", mapOf("query" to name))
            if (s == null) failed = true
            val id = s?.arr("results").objects().firstOrNull()?.int("id") ?: continue
            val img = get("$kind/$id/images", mapOf("include_image_language" to "en,ja,null"))
            if (img == null) failed = true
            val list = img?.arr("logos").objects()
            if (list.isEmpty()) continue
            val en = list.filter { it.str("iso_639_1") == "en" }
                .sortedWith(compareByDescending<JSONObject> { it.dbl("vote_average") ?: 0.0 }.thenByDescending { it.dbl("width") ?: 0.0 })
            val best = en.firstOrNull() ?: list.first()
            val path = best.str("file_path")
            if (path != null) {
                url = "https://image.tmdb.org/t/p/w500$path"
                break
            }
        }
        // don't remember a miss that was only a network hiccup
        if (url != null || !failed) {
            logos[key] = url ?: ""
            AppCache.write(ck, jsonOf("u" to (url ?: "")).toString(), 7 * 86_400)
        }
        return url
    }

    /** Backgrounds, posters and logos for the info page gallery. */
    suspend fun artwork(name: String, movie: Boolean): List<Artwork> {
        if (!enabled || name.isBlank()) return emptyList()
        val key = (if (movie) "m:" else "t:") + name
        art[key]?.let { return it }
        val ck = "art:${fnv(key)}"
        val hit = AppCache.read(ck)
        if (hit != null && hit.ageDays < 7) {
            val list = parseArray(hit.data).objects().map { Artwork(it.str("k") ?: "", it.str("u") ?: "") }.filter { it.url.isNotEmpty() }
            if (list.isNotEmpty()) {
                art[key] = list
                return list
            }
        }
        for (kind in kinds(movie)) {
            val id = get("search/$kind", mapOf("query" to name))?.arr("results").objects().firstOrNull()?.int("id") ?: continue
            val j = get("$kind/$id/images") ?: continue
            fun pick(field: String, label: String, size: String) =
                j.arr(field).objects().mapNotNull { i -> i.str("file_path")?.let { Artwork(label, "https://image.tmdb.org/t/p/$size$it") } }
            val out = pick("backdrops", "Backgrounds", "w780") + pick("posters", "Posters", "w500") + pick("logos", "Logos", "w500")
            if (out.isNotEmpty()) {
                art[key] = out
                AppCache.write(ck, JSONArray(out.map { jsonOf("k" to it.kind, "u" to it.url) }).toString(), 7 * 86_400)
                return out
            }
        }
        return emptyList()
    }
}
