package io.anibiar.app.prism

import io.anibiar.app.core.Net
import io.anibiar.app.core.parseObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** Small web helpers shared by the Prism providers. */
object Web {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** At most this many site requests at the same time, so a burst of searches can't choke the phone or the network. */
    val gate = Semaphore(10)

    private class Bad(var fails: Int, var until: Long)
    private val bad = java.util.concurrent.ConcurrentHashMap<String, Bad>()

    private fun host(url: String) = try { java.net.URI(url).host ?: url } catch (_: Exception) { url }

    /** A site that keeps timing out is left alone for two minutes, so it can't hold up the others. */
    private suspend fun fetch(url: String, h: Map<String, String>, timeoutMs: Long): io.anibiar.app.core.HttpResult {
        val host = host(url)
        val b = bad[host]
        if (b != null && b.until > System.currentTimeMillis()) throw PrismException("$host is not answering right now")
        return try {
            val r = fetch(url, h, timeoutMs)
            bad.remove(host)
            r
        } catch (e: java.io.IOException) {
            val x = bad.getOrPut(host) { Bad(0, 0) }
            x.fails++
            if (x.fails >= 2) { x.until = System.currentTimeMillis() + 120_000; x.fails = 0 }
            throw e
        }
    }

    suspend fun html(url: String, referer: String? = null, timeoutMs: Long = 9_000): String {
        val h = linkedMapOf("User-Agent" to UA, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language" to "en-US,en;q=0.9")
        if (referer != null) h["Referer"] = referer
        val r = fetch(url, h, timeoutMs)
        if (!r.ok) throw PrismException("HTTP ${r.code} for ${url.substringAfter("://").substringBefore("?").take(60)}")
        return r.body
    }

    suspend fun json(url: String, referer: String? = null, timeoutMs: Long = 9_000): JSONObject {
        val h = linkedMapOf("User-Agent" to UA, "Accept" to "application/json, text/javascript, */*; q=0.01", "X-Requested-With" to "XMLHttpRequest")
        if (referer != null) h["Referer"] = referer
        val r = fetch(url, h, timeoutMs)
        if (!r.ok) throw PrismException("HTTP ${r.code} for ${url.substringAfter("://").substringBefore("?").take(60)}")
        return parseObject(r.body) ?: throw PrismException("Invalid answer from ${url.substringAfter("://").substringBefore("/")}")
    }

    /** Runs [block] for every item at the same time and keeps the ones that worked. */
    suspend fun <T, R : Any> parallel(items: List<T>, block: suspend (T) -> R?): List<R> = coroutineScope {
        items.map { item ->
            async {
                try { block(item) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
            }
        }.mapNotNull { it.await() }
    }

    fun range(start: Number?, end: Number?): JSONObject? {
        val s = start?.toDouble() ?: return null
        val e = end?.toDouble() ?: return null
        return if (e > s) JSONObject().put("start", s).put("end", e) else null
    }

    fun skip(v: Any?): JSONObject? {
        if (v is JSONArray && v.length() >= 2) return range(v.optDouble(0), v.optDouble(1))
        if (v is JSONObject) return range(v.optDouble("start"), v.optDouble("end"))
        return null
    }

    fun origin(url: String): String = try {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}" + (if (u.port > 0) ":${u.port}" else "") + "/"
    } catch (_: Exception) { "" }

    /** Titles and counts that every provider scrapes into one common list shape. */
    class Row(val number: Int, val title: String?, val sub: Boolean, val dub: Boolean, val duration: Int? = null, val filler: Boolean = false, val airDate: String? = null)

    fun episodeList(id: Int, provider: String, rows: List<Row>, zip: JSONObject?, expected: Int?, title: String, extra: JSONObject = JSONObject()): JSONObject {
        val sub = JSONArray()
        val dub = JSONArray()
        for (row in rows.sortedBy { it.number }) {
            if (expected != null && row.number > expected) continue
            val m = Meta.episodeMeta(row.number, zip)
            fun item(audio: String) = JSONObject()
                .put("number", row.number)
                .put("title", m.title ?: row.title ?: "Episode ${row.number}")
                .put("duration", m.duration ?: row.duration ?: JSONObject.NULL)
                .put("filler", m.filler || row.filler)
                .put("description", m.description ?: JSONObject.NULL)
                .put("image", m.image ?: JSONObject.NULL)
                .put("airDate", m.airDate ?: row.airDate ?: JSONObject.NULL)
                .put("id", "watch/$provider/$id/$audio/$provider-${row.number}")
                .put("audio", audio)
            if (row.sub) sub.put(item("sub"))
            if (row.dub) dub.put(item("dub"))
        }
        val meta = JSONObject(extra.toString()).put("title", title).put("source", provider)
        return JSONObject().put("meta", meta).put("episodes", JSONObject().put("sub", sub).put("dub", dub))
    }
}
