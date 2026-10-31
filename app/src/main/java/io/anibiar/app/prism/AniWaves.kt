package io.anibiar.app.prism

import io.anibiar.app.core.int
import io.anibiar.app.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** AniWaves: strict identity matching (title, format, year, episode count), then every server of an episode. */
object AniWaves {
    private const val BASE = "https://aniwaves.ru"

    private class Card(val slug: String, val siteId: Int, val title: String, val japanese: String)
    private class Detail(val card: Card, val type: String, val year: Int?, val available: Int)
    private class Series(val siteId: Int, val slug: String, val title: String, val partOrdinal: Int)
    private class Ep(
        val number: Int, val source: String, val ids: String, val title: String, val airDate: String?, val duration: Int?,
        val filler: Boolean, val hasSub: Boolean, val hasDub: Boolean,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun format(v: String?): String {
        val n = (v ?: "").uppercase()
        return when {
            n.contains("SPECIAL") -> "special"; n == "TV" || n == "TV_SHORT" -> "tv"; n == "MOVIE" -> "movie"
            n == "OVA" -> "ova"; n == "ONA" -> "ona"; else -> ""
        }
    }

    private suspend fun ajax(path: String, referer: String): Any? {
        val j = Web.json("$BASE$path", referer)
        if (j.int("status") != 200) throw PrismException(j.str("message") ?: "AniWaves request failed")
        return j.opt("result")
    }

    private fun attr(tag: String, name: String) = Text.attr(tag, name)

    private fun cards(html: String): List<Card> {
        val found = LinkedHashMap<String, Card>()
        for (m in Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.groupValues[1]
            if (!Regex("""\bclass=["'][^"']*\bname\b[^"']*\bd-title\b""", RegexOption.IGNORE_CASE).containsMatchIn(tag)) continue
            val slug = Regex("""^/watch/([a-z0-9-]+)$""", RegexOption.IGNORE_CASE).find(attr(tag, "href"))?.groupValues?.get(1) ?: continue
            if (found.containsKey(slug)) continue
            val siteId = Regex("""-(\d+)$""").find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val title = Text.stripTags(m.groupValues[2])
            if (title.isEmpty()) continue
            found[slug] = Card(slug, siteId, title, attr(tag, "data-jp"))
        }
        return found.values.toList()
    }

    private fun field(html: String, label: String): String =
        Regex("""<div>\s*${Regex.escape(label)}:\s*<span[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { Text.stripTags(it) } ?: ""

    private suspend fun detail(c: Card): Detail {
        val html = Web.html("$BASE/watch/${c.slug}", "$BASE/")
        val year = Regex("""\d{4}""").find(field(html, "Date aired"))?.value?.toIntOrNull() ?: Regex("""\d{4}""").find(field(html, "Premiered"))?.value?.toIntOrNull()
        val nums = Regex("""\d+""").findAll(field(html, "Episodes")).mapNotNull { it.value.toIntOrNull() }.toList()
        return Detail(c, format(field(html, "Type")), year, nums.firstOrNull() ?: 0)
    }

    private fun ordinal(v: String?): Int {
        val m = Regex("""\b(?:part|special|chapter)\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b""").find((v ?: "").lowercase()) ?: return 0
        val w = m.groupValues[1]
        return w.toIntOrNull() ?: mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)[w] ?: 0
    }

    private val series = ConcurrentHashMap<Int, Series>()

    private suspend fun series(id: Int): Series {
        series[id]?.let { return it }
        return Meta.once("aniwaves:$id") {
            val media = Meta.media(id)
            val zip = Meta.zip(id)
            val titles = Meta.titles(media, zip)
            val expected = Meta.expectedCount(media, zip)
            val found = LinkedHashMap<String, Card>()
            for (list in Web.parallel(Text.searchTerms(titles, 20)) { q -> cards(Web.html("$BASE/filter?keyword=${enc(q)}", "$BASE/")) }) for (c in list) found.putIfAbsent(c.slug, c)
            fun idScore(c: Card) = Text.identityScore(titles, listOf(c.title, c.japanese, c.slug.replace("-", " ")))
            val short = found.values.map { it to idScore(it) }.filter { it.second >= 0.5 }.sortedByDescending { it.second }.take(12)
            val details = Web.parallel(short) { detail(it.first) }
            val expType = format(media.format)
            val expYear = media.startYear ?: media.seasonYear
            val primary = listOfNotNull(media.english, media.romaji, media.native)
            val partOrd = primary.maxOfOrNull { ordinal(it) } ?: 0
            class Valid(val d: Detail, val titleScore: Double, val score: Double, val part: Int)
            val valid = details.mapNotNull { d ->
                val ts = idScore(d.card)
                val partMapping = expected == 1 && expType == "special" && d.type == "special" && expYear != null && d.year == expYear &&
                    partOrd >= 2 && d.available == partOrd && ts >= 0.65
                if (ts < 0.68 && !partMapping) return@mapNotNull null
                if (expType.isNotEmpty() && d.type.isNotEmpty() && expType != d.type) return@mapNotNull null
                if (expYear != null && d.year != null && expYear != d.year) return@mapNotNull null
                val coverage = when {
                    expected == null || expected < 1 -> 0.5
                    d.available < 1 -> 0.0
                    media.status != "FINISHED" || expected < 6 -> 1.0
                    else -> minOf(1.0, d.available / Math.ceil(expected * 0.8))
                }
                if (expected != null && expected >= 6 && coverage < 0.8) return@mapNotNull null
                val score = ts * 0.72 + (if (expType.isNotEmpty() && d.type == expType) 0.14 else 0.07) + (if (expYear != null && d.year == expYear) 0.1 else 0.04) + coverage * 0.04
                Valid(d, ts, score, if (partMapping) partOrd else 0)
            }.sortedByDescending { it.score }
            val sel = valid.firstOrNull()
            val runner = valid.getOrNull(1)
            if (sel == null || (sel.score < 0.82 && sel.part == 0) || (runner != null && sel.score - runner.score < 0.08)) {
                throw PrismException("AniWaves has no confident match")
            }
            val s = Series(sel.d.card.siteId, sel.d.card.slug, sel.d.card.title, sel.part)
            series[id] = s
            s
        }
    }

    private fun parseEps(html: String): List<Ep> {
        val out = ArrayList<Ep>()
        val seen = HashSet<Int>()
        for (m in Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val a = m.groupValues[1]
            val number = attr(a, "data-num").toDoubleOrNull()?.toInt() ?: continue
            if (number < 1 || !seen.add(number)) continue
            val ids = attr(a, "data-ids")
            if (ids.isEmpty()) { seen.remove(number); continue }
            out.add(
                Ep(
                    number, attr(a, "data-slug").ifEmpty { number.toString() }, ids,
                    Text.stripTags(m.groupValues[2]).replace(Regex("^\\d+\\s*"), "").ifEmpty { "Episode $number" },
                    attr(a, "data-aired").ifEmpty { null }, attr(a, "data-duration").toIntOrNull(),
                    attr(a, "data-filler") == "1", attr(a, "data-sub") == "1", attr(a, "data-dub") == "1",
                ),
            )
        }
        return out.sortedBy { it.number }
    }

    private class Cached(val at: Long, val eps: List<Ep>)
    private val lists = ConcurrentHashMap<Int, Cached>()

    private suspend fun fetchEps(s: Series): List<Ep> {
        val eps = parseEps(ajax("/ajax/episode/list/${s.siteId}?vrf=", "$BASE/watch/${s.slug}")?.toString() ?: "")
        if (eps.isEmpty()) throw PrismException("AniWaves has no episodes for ${s.slug}")
        return eps
    }

    private fun align(src: List<Ep>, media: Media, expected: Int?, s: Series): List<Ep> {
        if (expected == null || src.size <= expected) return src
        val target = listOfNotNull(media.english, media.romaji, media.native).maxOfOrNull { ordinal(it) } ?: 0
        if (target < 2) return src
        var start = src.indexOfFirst { ordinal(it.title) == target }
        if (start < 0 && s.partOrdinal == target && src.size == target) start = target - 1
        if (start < 0 || src.size - start < expected) return src
        return src.subList(start, start + expected).mapIndexed { i, e ->
            Ep(i + 1, e.source, e.ids, e.title, e.airDate, e.duration, e.filler, e.hasSub, e.hasDub)
        }
    }

    private suspend fun aligned(id: Int, s: Series): List<Ep> {
        lists[id]?.let { if (System.currentTimeMillis() - it.at < 60_000) return it.eps }
        val media = Meta.media(id)
        val zip = Meta.zip(id)
        val eps = align(fetchEps(s), media, Meta.expectedCount(media, zip), s)
        lists[id] = Cached(System.currentTimeMillis(), eps)
        return eps
    }

    suspend fun episodes(id: Int): JSONObject {
        val s = series(id)
        val media = Meta.media(id)
        val zip = Meta.zip(id)
        val eps = aligned(id, s)
        val rows = eps.map { Web.Row(it.number, it.title, it.hasSub, it.hasDub, it.duration?.takeIf { d -> d > 0 }, it.filler, it.airDate) }
        return Web.episodeList(id, "aniwaves", rows, zip, Meta.expectedCount(media, zip), s.title, JSONObject().put("slug", s.slug))
    }

    private class Server(val audio: String, val linkId: String, val name: String)

    suspend fun watch(id: Int, audio: String, episode: Int): JSONObject {
        val s = series(id)
        val ep = aligned(id, s).firstOrNull { it.number == episode && (if (audio == "sub") it.hasSub else it.hasDub) }
            ?: throw PrismException("AniWaves has no $audio episode $episode")
        val referer = "$BASE/watch/${s.slug}/ep-${ep.source}"
        val html = ajax("/ajax/server/list?servers=${s.siteId}&eps=${enc(ep.source)}", referer)?.toString() ?: ""
        // servers are grouped by <div data-type="sub|dub">
        val markers = Regex("""<div\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.range.first to attr(it.groupValues[1], "data-type") }.filter { it.second == "sub" || it.second == "dub" }.toList()
        val servers = ArrayList<Server>()
        for ((i, mk) in markers.withIndex()) {
            val end = markers.getOrNull(i + 1)?.first ?: html.length
            for (li in Regex("""<li\b([^>]*)>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).findAll(html.substring(mk.first, end))) {
                val linkId = attr(li.groupValues[1], "data-link-id")
                if (linkId.isEmpty() || mk.second != audio) continue
                servers.add(Server(mk.second, linkId, Text.stripTags(li.groupValues[2]).ifEmpty { "AniWaves" }))
            }
        }
        if (servers.isEmpty()) throw PrismException("AniWaves has no $audio servers")

        class Got(val streams: List<JSONObject>, val intro: JSONObject?, val outro: JSONObject?)

        val got = Web.parallel(servers) { sv ->
            val src = ajax("/ajax/sources?id=${enc(sv.linkId)}&asi=0&autoPlay=0", referer) as? JSONObject ?: return@parallel null
            val embed = src.str("url") ?: return@parallel null
            val skip = src.opt("skip_data") as? JSONObject
            val x = Extractors.extract(embed, referer) ?: return@parallel null
            val ref = Web.origin(embed).ifEmpty { referer }
            val streams = x.sources.map {
                JSONObject().put("url", it.url).put("type", it.type).put("server", sv.name).put("referer", ref)
                    .put("quality", it.quality ?: JSONObject.NULL).put("subtitles", x.tracks.let { t ->
                        val a = JSONArray()
                        for (i in 0 until t.length()) t.optJSONObject(i)?.let { o ->
                            val f = o.str("file")
                            if (!f.isNullOrEmpty() && (o.str("kind") ?: "captions") != "thumbnails") a.put(JSONObject().put("url", f).put("label", o.str("label") ?: "English").put("default", o.optBoolean("default", false)))
                        }
                        a
                    })
            }
            if (streams.isEmpty()) null else Got(streams, Web.skip(skip?.opt("intro")) ?: Web.skip(x.intro), Web.skip(skip?.opt("outro")) ?: Web.skip(x.outro))
        }
        if (got.isEmpty()) throw PrismException("AniWaves: no playable stream for episode $episode")
        val streams = got.flatMap { it.streams }
        streams.forEachIndexed { i, o -> o.put("priority", if (i == 0) 5 else 4).put("isActive", i == 0) }
        return JSONObject().put("streams", JSONArray(streams))
            .put("intro", got.firstNotNullOfOrNull { it.intro } ?: JSONObject.NULL)
            .put("outro", got.firstNotNullOfOrNull { it.outro } ?: JSONObject.NULL)
    }
}
