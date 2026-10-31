package io.anibiar.app.prism

import io.anibiar.app.core.Net
import io.anibiar.app.core.parseArray
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** AnimeGG: direct MP4 files from its own embed pages (sub and dub). */
object AnimeGG {
    private const val BASE = "https://www.animegg.org"

    private class Cand(val slug: String, val text: String, val score: Double)
    private class Ep(val number: Int, val title: String, val slug: String, val hasSub: Boolean, val hasDub: Boolean)
    private class Series(val slug: String, val title: String, val offset: Boolean, val offsetBy: Int)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun search(q: String): List<Pair<String, String>> {
        val html = Web.html("$BASE/search/?q=${enc(q)}", "$BASE/")
        val out = ArrayList<Pair<String, String>>()
        for (m in Regex("""<a\b[^>]*class=["'][^"']*\bmse\b[^"']*["'][^>]*>[\s\S]*?</a>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE).find(m.value)?.value ?: ""
            val slug = Regex("""^/series/([^/?#]+)""").find(Text.attr(tag, "href"))?.groupValues?.get(1) ?: continue
            val strong = Regex("""<strong[^>]*>([\s\S]*?)</strong>""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)
            out.add(slug to (if (strong != null) Text.stripTags(strong) else slug.replace("-", " ")))
        }
        return out
    }

    private suspend fun searchFn(q: String): List<Pair<String, String>> {
        val r1 = ArrayList(search(q))
        // "Re:Zero" finds nothing here but "ReZero" lists every season, so a plain alphanumeric first word is tried too
        val compact = q.trim().split(Regex("\\s+")).first().replace(Regex("[^a-zA-Z0-9]"), "")
        if (compact.length >= 4 && !compact.equals(q, true)) {
            try {
                val have = r1.map { it.first }.toSet()
                search(compact).forEach { if (it.first !in have) r1.add(it) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
        return r1
    }

    private class Cached(val at: Long, val eps: List<Ep>)
    private val pages = ConcurrentHashMap<String, Cached>()

    private suspend fun scrape(slug: String): List<Ep> {
        pages[slug]?.let { if (System.currentTimeMillis() - it.at < 60_000) return it.eps }
        val html = Web.html("$BASE/series/$slug", "$BASE/")
        val out = ArrayList<Ep>()
        for (m in Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val block = m.groupValues[1]
            if (!Regex("""\banm_det_pop\b""").containsMatchIn(block)) continue
            val link = Regex("""<a\b[^>]*class=["'][^"']*anm_det_pop[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE).find(block)?.value ?: ""
            val href = Text.attr(link, "href").replace(Regex("#.*$"), "").removePrefix("/")
            val strong = Text.stripTags(Regex("""<strong[^>]*>([\s\S]*?)</strong>""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1) ?: "")
            val num = (Regex("""(\d+)-(\d+)\s*$""").find(strong) ?: Regex("""(\d+)\s*$""").find(strong))?.groupValues?.get(1)?.toIntOrNull()
            if (num == null || href.isEmpty()) continue
            val title = Text.stripTags(Regex("""<i\b[^>]*class=["'][^"']*anititle[^"']*["'][^>]*>([\s\S]*?)</i>""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1) ?: "").ifEmpty { strong }
            out.add(Ep(num, title, href, Regex("""\bbtn-subbed\b""").containsMatchIn(block), Regex("""\bbtn-dubbed\b""").containsMatchIn(block)))
        }
        val seen = HashSet<Int>()
        val res = out.sortedBy { it.number }.filter { seen.add(it.number) }
        pages[slug] = Cached(System.currentTimeMillis(), res)
        return res
    }

    private val seriesCache = ConcurrentHashMap<Int, Series>()

    private suspend fun series(id: Int): Series {
        seriesCache[id]?.let { return it }
        return Meta.once("animegg:$id") {
            val media = Meta.media(id)
            val zip = Meta.zip(id)
            val titles = Meta.titles(media, zip)
            val all = LinkedHashMap<String, String>()
            for (list in Web.parallel(Text.searchTerms(titles, 12)) { searchFn(it) }) for ((slug, text) in list) all.putIfAbsent(slug, text)
            val cands = all.mapNotNull { (slug, text) ->
                val best = titles.maxOfOrNull { Text.titleScore(it, text, slug) } ?: 0.0
                val identity = Text.identityScore(titles, listOf(text, slug.replace("-", " ")))
                if (identity >= 0.6 && best >= 0.5) Cand(slug, text, best * 0.65 + identity * 0.35) else null
            }.sortedByDescending { it.score }.take(6)
            val expected = Meta.expectedCount(media, zip)
            val offset = try { Meta.prequelOffset(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { 0 }
            val single = media.format?.uppercase() == "MOVIE" || expected == 1
            val minScore = if (single) 0.9 else 0.65
            class Sel(val c: Cand, val eps: List<Ep>, val mode: Boolean, val score: Double)
            val sels = Web.parallel(cands) { c ->
                val eps = scrape(c.slug)
                val local = if (expected != null) eps.count { it.number in 1..expected } else eps.size
                val off = if (expected != null && offset > 0) eps.count { it.number > offset && it.number <= offset + expected } else 0
                val useOffset = off > local
                val hits = maxOf(local, off)
                var countScore = 1.0
                if (expected != null && expected >= 6) {
                    val needed = if (media.status == "FINISHED") Math.ceil(expected * 0.9) else 1.0
                    countScore = if (hits >= needed) 1.0 else hits / needed
                }
                Sel(c, eps, useOffset, c.score * 0.7 + countScore * 0.3)
            }.filter { it.eps.isNotEmpty() && it.score >= minScore }.sortedByDescending { it.score }
            val top = sels.firstOrNull() ?: throw PrismException("AnimeGG has no match")
            val s = Series(top.c.slug, top.c.text, top.mode, offset)
            seriesCache[id] = s
            s
        }
    }

    suspend fun episodes(id: Int): JSONObject {
        val s = series(id)
        val media = Meta.media(id)
        val zip = Meta.zip(id)
        val expected = Meta.cap(media, zip)
        val rows = scrape(s.slug).mapNotNull {
            val n = if (s.offset) it.number - s.offsetBy else it.number
            if (n < 1) null else Web.Row(n, it.title, it.hasSub, it.hasDub)
        }
        return Web.episodeList(id, "animegg", rows, zip, expected, s.title, JSONObject().put("slug", s.slug).put("numbering", if (s.offset) "offset" else "local"))
    }

    private class Tab(val embedId: String, val server: String, val audio: String)

    suspend fun watch(id: Int, audio: String, episode: Int): JSONObject {
        val s = series(id)
        val providerEp = if (s.offset) episode + s.offsetBy else episode
        val ep = scrape(s.slug).firstOrNull { it.number == providerEp } ?: throw PrismException("AnimeGG has no episode $providerEp")
        val html = Web.html("$BASE/${ep.slug}", BASE)
        val tabs = ArrayList<Tab>()
        for (m in Regex("""<a\b[^>]*data-toggle=["']tab["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val embedId = Text.attr(m.value, "data-id")
            if (embedId.isEmpty()) continue
            val version = Text.attr(m.value, "data-version").ifEmpty { "subbed" }
            val a = if (version.startsWith("dub")) "dub" else "sub"
            if (a == audio) tabs.add(Tab(embedId, Text.attr(m.value, "data-mirror").ifEmpty { "AnimeGG" }, a))
        }
        val lists = Web.parallel(tabs) { tab ->
            val page = Web.html("$BASE/embed/${tab.embedId}", BASE)
            val raw = Regex("""var\s+videoSources\s*=\s*(\[[\s\S]*?\]);""").find(page)?.groupValues?.get(1) ?: return@parallel null
            val json = raw.replace(Regex("""([{,]\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*:"""), "$1\"$2\":").replace(Regex(""":\s*'([^']*)'"""), ": \"$1\"")
            val arr = parseArray(json) ?: return@parallel null
            val out = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = o.optString("file")
                if (f.isEmpty()) continue
                val url = if (f.startsWith("http")) f else "$BASE$f"
                out.add(
                    JSONObject().put("url", url).put("type", if (url.contains(".m3u8")) "hls" else "mp4")
                        .put("quality", o.optString("label", "")).put("server", tab.server).put("referer", "$BASE/"),
                )
            }
            if (out.isEmpty()) null else listOf(out.maxByOrNull { Regex("\\d+").find(it.optString("quality"))?.value?.toIntOrNull() ?: 0 }!!)
        }
        val streams = lists.flatten()
        if (streams.isEmpty()) throw PrismException("AnimeGG: no playable stream for episode $episode")
        streams.forEachIndexed { i, o -> o.put("priority", if (i == 0) 5 else 4).put("isActive", i == 0) }
        return JSONObject().put("streams", JSONArray(streams))
    }
}
