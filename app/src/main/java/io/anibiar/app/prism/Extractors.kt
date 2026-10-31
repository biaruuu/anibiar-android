package io.anibiar.app.prism

import io.anibiar.app.core.Net
import io.anibiar.app.core.arr
import io.anibiar.app.core.obj
import io.anibiar.app.core.objects
import io.anibiar.app.core.parseObject
import io.anibiar.app.core.str
import io.anibiar.app.data.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One playable link found inside an embed page. */
class Found(val url: String, val type: String, val variant: String? = null, val quality: String? = null)

/** What an extractor learned about an embed: links, subtitle tracks, intro/outro times and the host that must be sent as Referer. */
class Extracted(val sources: List<Found>, val tracks: JSONArray, val intro: Any?, val outro: Any?, val origin: String)

/** Video hosts the providers point at. Each one is a Kotlin port of the matching Anivexa extractor. */
object Extractors {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun canHandle(url: String) = Regex("megaplay\\.[^/]+/stream/|play\\.echovideo\\.ru/embed-(?:[01]|20)/|upn\\.one", RegexOption.IGNORE_CASE).containsMatchIn(url)

    suspend fun extract(embedUrl: String, referer: String?): Extracted? {
        return try {
            when {
                Regex("megaplay\\.[^/]+/stream/", RegexOption.IGNORE_CASE).containsMatchIn(embedUrl) -> megaplay(embedUrl, referer)
                Regex("play\\.echovideo\\.ru/embed-20/", RegexOption.IGNORE_CASE).containsMatchIn(embedUrl) -> datasv(embedUrl)
                Regex("play\\.echovideo\\.ru/embed-[01]/", RegexOption.IGNORE_CASE).containsMatchIn(embedUrl) -> vidplay(embedUrl)
                Regex("upn\\.one", RegexOption.IGNORE_CASE).containsMatchIn(embedUrl) -> nova(embedUrl)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /* ------------------------------------ MegaPlay ------------------------------------ */

    private suspend fun megaplay(embedUrl: String, referer: String?): Extracted? = coroutineScope {
        val page = embedUrl.toHttpUrl()
        val origin = "${page.scheme}://${page.host}"
        val html = Net.get(embedUrl, mapOf("User-Agent" to UA, "Accept" to "text/html,*/*", "Referer" to (referer ?: "$origin/")), 12_000)
        if (!html.ok) return@coroutineScope null
        val fileId = Regex("data-id=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html.body)?.groupValues?.get(1) ?: return@coroutineScope null
        val scripts = Vault.fetchScripts(page.host, html.body, embedUrl)
        val player = scripts.player ?: return@coroutineScope null
        val routes = Vault.scriptStrings(player).filter { Regex("^stream/getSources[\\w/-]*$", RegexOption.IGNORE_CASE).matches(it) }.sortedBy { it.length }
        val legacy = routes.firstOrNull()
        val modern = legacy?.let { l -> routes.firstOrNull { it != l && it.startsWith(l) } }
        if (legacy == null) return@coroutineScope null
        val headers = mapOf("User-Agent" to UA, "Accept" to "application/json,*/*", "Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest")
        suspend fun call(route: String): JSONObject? = try {
            val r = Net.get("$origin/$route?id=$fileId&id=$fileId", headers, 10_000)
            if (r.ok) parseObject(r.body) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val m = modern?.let { async { call(it) } }
        val l = async { call(legacy) }
        val modernData = m?.await()
        val legacyData = l.await()
        val key = scripts.signing?.let { Vault.signingKey(it) }
        fun url(d: JSONObject?): String? {
            d ?: return null
            val raw = d.obj("sources")?.str("file") ?: d.str("enc")?.let { Vault.decrypt(it, player) } ?: return null
            return Vault.sign(raw, key) ?: raw
        }
        val out = ArrayList<Found>()
        url(modernData)?.let { out.add(Found(it, "hls", "modern")) }
        url(legacyData)?.let { u -> if (out.none { it.url == u }) out.add(Found(u, "hls", "legacy")) }
        if (out.isEmpty()) return@coroutineScope null
        val meta = modernData ?: legacyData ?: JSONObject()
        Extracted(out, meta.arr("tracks") ?: JSONArray(), meta.opt("intro"), meta.opt("outro"), origin)
    }

    /* ------------------------------------ Vidplay / DataSV ------------------------------------ */

    private suspend fun echo(embedUrl: String, kind: String): JSONObject? {
        val u = embedUrl.toHttpUrl()
        val id = u.pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val r = Net.get(
            "${u.scheme}://${u.host}/$kind/getSources?id=$id",
            mapOf("User-Agent" to UA, "Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest"),
            10_000,
        )
        return if (r.ok) parseObject(r.body) else null
    }

    private suspend fun vidplay(embedUrl: String): Extracted? {
        val kind = Regex("/(embed-[01])/", RegexOption.IGNORE_CASE).find(embedUrl)?.groupValues?.get(1) ?: return null
        val data = echo(embedUrl, kind) ?: return null
        val list = ArrayList<Found>()
        when (val s = data.opt("sources")) {
            is JSONArray -> for (i in 0 until s.length()) {
                val v = s.opt(i)
                val f = if (v is String) v else (v as? JSONObject)?.let { it.str("file") ?: it.str("url") }
                if (!f.isNullOrEmpty()) list.add(Found(f, if (f.contains(".mp4")) "mp4" else "hls"))
            }
            is String -> if (s.isNotEmpty()) list.add(Found(s, "hls"))
        }
        if (list.isEmpty()) return null
        val u = embedUrl.toHttpUrl()
        return Extracted(list, data.arr("tracks") ?: JSONArray(), data.opt("intro"), data.opt("outro"), "${u.scheme}://${u.host}")
    }

    private suspend fun datasv(embedUrl: String): Extracted? {
        val data = echo(embedUrl, "embed-20") ?: return null
        val src = data.obj("sources") ?: return null
        val list = ArrayList<Found>()
        for (quality in src.keys()) {
            when (val v = src.opt(quality)) {
                is String -> if (v.isNotEmpty()) list.add(Found(v, "mp4", null, quality))
                is JSONArray -> for (i in 0 until v.length()) v.optString(i).takeIf { it.isNotEmpty() }?.let { list.add(Found(it, "mp4", null, quality)) }
            }
        }
        if (list.isEmpty()) return null
        val u = embedUrl.toHttpUrl()
        return Extracted(list, data.arr("tracks") ?: JSONArray(), data.opt("intro"), data.opt("outro"), "${u.scheme}://${u.host}")
    }

    /* ------------------------------------ Nova ------------------------------------ */

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private suspend fun nova(embedUrl: String): Extracted? {
        val id = Regex("upn\\.one/#([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(embedUrl)?.groupValues?.get(1) ?: return null
        val r = Net.get("https://nova.upn.one/api/v1/video?id=$id&w=1920&h=1080&r=", mapOf("User-Agent" to UA, "Referer" to "https://nova.upn.one/"), 10_000)
        if (!r.ok) return null
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(hex("6b69656d7469656e6d75613931316361"), "AES"), IvParameterSpec(hex("313233343536373839306f6975797472")))
        val data = parseObject(String(c.doFinal(hex(r.body.trim())), Charsets.UTF_8)) ?: return null
        val url = data.str("cf") ?: data.str("source") ?: return null
        return Extracted(listOf(Found(url, "hls")), JSONArray(), null, null, "https://nova.upn.one")
    }
}
