package io.anibiar.app.prism

import io.anibiar.app.core.arr
import io.anibiar.app.core.obj
import io.anibiar.app.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** AniKoto: finds the show by title and MAL id, lists its episodes, and opens every server of an episode. */
object AniKoto {
    private const val BASE = "https://anikototv.to"
    private const val MAPPER = "https://mapper.nekostream.site/api/mal"
    private const val SPOOF = "https://hianimes.re/"

    private class Cand(val slug: String, val name: String, val jp: String)
    private class Row(
        val number: Int, val ids: String, val hasSub: Boolean, val hasDub: Boolean,
        val malId: Int?, val slug: String, val timestamp: String, val title: String,
    )
    private class Show(val slug: String, val title: String, val showId: String, val episodes: List<Row>)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun search(q: String): List<Cand> {
        val html = Web.html("$BASE/filter?keyword=${enc(q)}", "$BASE/")
        val out = LinkedHashMap<String, Cand>()
        Regex("""<a\s+class="name d-title"\s+href="https://anikototv\.to/watch/([^"/]+)(?:/ep-\d+)?"[^>]*data-jp="([^"]*)"[^>]*>([\s\S]*?)</a>""").findAll(html).forEach {
            out.putIfAbsent(it.groupValues[1], Cand(it.groupValues[1], Text.stripTags(it.groupValues[3]), Text.decodeEntities(it.groupValues[2])))
        }
        if (out.isEmpty()) {
            Regex("""<a\s+href="https://anikototv\.to/watch/([^"/]+)(?:/ep-\d+)?"[^>]*>([\s\S]*?)</a>""").findAll(html).forEach {
                out.putIfAbsent(it.groupValues[1], Cand(it.groupValues[1], it.groupValues[1], ""))
            }
        }
        return out.values.toList()
    }

    private fun rows(html: String): List<Row> {
        val out = ArrayList<Row>()
        for (m in Regex("""<a\s+[^>]*data-id="([^"]*)"[^>]*>([\s\S]*?)</a>""").findAll(html)) {
            val tag = m.value
            fun a(n: String) = Regex("""data-$n="([^"]*)"""").find(tag)?.groupValues?.get(1) ?: ""
            val number = a("num").toIntOrNull() ?: continue
            val title = Regex("""<span\s+class="d-title"[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.groupValues?.get(1)?.let { Text.stripTags(it) } ?: ""
            out.add(Row(number, a("ids"), a("sub") == "1", a("dub") == "1", a("mal").toIntOrNull()?.takeIf { it != 0 }, a("slug"), a("timestamp"), title.ifEmpty { "Episode $number" }))
        }
        return out
    }

    private class Inspected(val cand: Cand, val showId: String, val episodes: List<Row>, val score: Double)

    private suspend fun inspect(c: Cand, score: Double): Inspected? {
        val html = Web.html("$BASE/watch/${c.slug}", "$BASE/")
        val showId = Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1) ?: return null
        val list = Web.json("$BASE/ajax/episode/list/$showId", "$BASE/watch/${c.slug}")
        val eps = rows(list.str("result") ?: "")
        return if (eps.isEmpty()) null else Inspected(c, showId, eps, score)
    }

    private val shows = ConcurrentHashMap<Int, Show>()

    private suspend fun show(id: Int): Show {
        shows[id]?.let { return it }
        return Meta.once("anikoto:$id") {
            val media = Meta.media(id)
            val zip = Meta.zip(id)
            val titles = Meta.titles(media, zip)
            val found = LinkedHashMap<String, Cand>()
            for (list in Web.parallel(Text.searchTerms(titles, 18)) { search(it) }) for (c in list) found.putIfAbsent(c.slug, c)
            val ranked = found.values.map { it to Text.identityScore(titles, listOf(it.name, it.jp, it.slug.replace("-", " "))) }
                .filter { it.second >= 0.28 }.sortedByDescending { it.second }.take(12)
            if (ranked.isEmpty()) throw PrismException("AniKoto has no candidates for this anime")
            val inspected = Web.parallel(ranked) { inspect(it.first, it.second) }
            val mal = media.idMal
            val expectedEps = media.episodes?.takeIf { it > 0 }
            val result: Show
            val exact = if (mal != null) inspected.filter { i -> i.episodes.any { it.malId == mal } } else emptyList()
            if (exact.isNotEmpty()) {
                fun metrics(i: Inspected): List<Int> {
                    val r = i.episodes.filter { it.malId == mal }
                    val nums = r.map { it.number }.toSet()
                    val inRange = if (expectedEps != null) nums.count { it in 1..expectedEps } else 0
                    val complete = if (expectedEps != null && inRange == expectedEps) 1 else 0
                    val same = if (expectedEps != null && nums.size == expectedEps) 1 else 0
                    val excess = if (expectedEps == null) 0 else maxOf(0, nums.size - expectedEps)
                    return listOf(-complete, -same, -inRange, excess)
                }
                val sorted = exact.sortedWith { a, b ->
                    val ma = metrics(a); val mb = metrics(b)
                    for (k in ma.indices) if (ma[k] != mb[k]) return@sortedWith ma[k].compareTo(mb[k])
                    b.score.compareTo(a.score)
                }
                val chosen = sorted[0]
                val eps = chosen.episodes.filter { it.malId == mal }.toMutableList()
                val numbers = eps.map { it.number }.toMutableSet()
                for (other in sorted.drop(1)) {
                    val r = other.episodes.filter { it.malId == mal }
                    if (r.isEmpty() || r.any { it.number in numbers }) continue
                    r.forEach { numbers.add(it.number); eps.add(it) }
                }
                eps.sortBy { it.number }
                var finalEps: List<Row> = eps
                if (expectedEps != null && eps.size > expectedEps) {
                    val range = LinkedHashMap<Int, Row>()
                    eps.forEach { if (it.number in 1..expectedEps) range.putIfAbsent(it.number, it) }
                    if (range.size == expectedEps) finalEps = range.values.sortedBy { it.number }
                }
                result = Show(chosen.cand.slug, chosen.cand.name, chosen.showId, finalEps)
            } else {
                val viable = inspected.filter { i -> i.score >= 0.72 && (mal == null || i.episodes.none { it.malId != null }) }.sortedByDescending { it.score }
                val chosen = viable.firstOrNull()
                val runner = viable.getOrNull(1)
                if (chosen == null || (runner != null && chosen.score - runner.score < 0.08)) throw PrismException("AniKoto has no confident match")
                result = Show(chosen.cand.slug, chosen.cand.name, chosen.showId, chosen.episodes)
            }
            shows[id] = result
            result
        }
    }

    suspend fun episodes(id: Int): JSONObject {
        val show = show(id)
        val zip = Meta.zip(id)
        val rows = show.episodes.map { Web.Row(it.number, it.title, it.hasSub, it.hasDub) }
        return Web.episodeList(id, "anikoto", rows, zip, null, show.title, JSONObject().put("slug", show.slug))
    }

    private class Item(val linkId: String, val name: String, val type: String)

    suspend fun watch(id: Int, audio: String, episode: Int): JSONObject {
        val show = show(id)
        val row = show.episodes.firstOrNull { it.number == episode && (if (audio == "sub") it.hasSub else it.hasDub) && it.ids.isNotEmpty() }
            ?: throw PrismException("AniKoto has no $audio episode $episode")
        val ajax = mapOf<String, String>()
        val serverData = try { Web.json("$BASE/ajax/server/list?servers=${enc(row.ids)}", "$BASE/") } catch (_: Exception) { null }
        val mapper = if (row.malId != null && row.slug.isNotEmpty() && row.timestamp.isNotEmpty()) {
            try { Web.json("$MAPPER/${row.malId}/${row.slug}/${row.timestamp}", "$BASE/") } catch (_: Exception) { null }
        } else null

        val items = ArrayList<Item>()
        val html = serverData?.str("result") ?: ""
        for (t in Regex("""<div class="type" data-type="([^"]+)">([\s\S]*?)</ul>\s*</div>""").findAll(html)) {
            val type = t.groupValues[1]
            for (li in Regex("""<li\s+([^>]*data-link-id[^>]*)>([\s\S]*?)</li>""").findAll(t.groupValues[2])) {
                val linkId = Regex("""data-link-id="([^"]+)"""").find(li.groupValues[1])?.groupValues?.get(1) ?: continue
                val name = Text.stripTags(li.groupValues[2])
                if (type == "dl" || name.lowercase().contains("download") || name.lowercase().contains("kiwi")) continue
                if (type == audio || (audio == "sub" && type == "hsub")) items.add(Item(linkId, name, type))
            }
        }
        if (mapper != null) {
            for (key in mapper.keys()) {
                if (key == "status") continue
                val url = mapper.obj(key)?.obj(audio)?.str("url") ?: continue
                items.add(Item(url, key.trimEnd('-', '_').trim(), audio))
            }
        }
        val seen = HashSet<String>()
        val unique = items.filter { seen.add("${it.name}:${it.type}") }

        class Out(val streams: List<JSONObject>, val tracks: JSONArray, val intro: JSONObject?, val outro: JSONObject?)

        val outs = Web.parallel(unique) { item ->
            val resolved = if (item.linkId.startsWith("http")) JSONObject().put("result", JSONObject().put("url", item.linkId))
            else Web.json("$BASE/ajax/server?get=${enc(item.linkId)}", "$BASE/")
            val result = resolved.obj("result")
            val embed = result?.str("url") ?: return@parallel null
            val skip = result.obj("skip_data")
            var intro = Web.skip(skip?.arr("intro"))
            var outro = Web.skip(skip?.arr("outro"))
            val hls = ArrayList<Found>()
            if (embed.contains("#aHR0c")) {
                try {
                    val u = String(Base64.getDecoder().decode(embed.substringAfter("#")))
                    if (u.contains(".m3u8")) hls.add(Found(u, "hls"))
                } catch (_: Exception) {}
            }
            val x = Extractors.extract(embed, SPOOF)
            val tracks = JSONArray()
            if (x != null) {
                x.sources.forEach { s -> if (hls.none { it.url == s.url }) hls.add(s) }
                for (i in 0 until x.tracks.length()) x.tracks.optJSONObject(i)?.let { t ->
                    if (!t.str("file").isNullOrEmpty()) tracks.put(
                        JSONObject().put("url", t.str("file")).put("label", t.str("label") ?: "English")
                            .put("srclang", lang(t.str("label") ?: "")).put("default", t.optBoolean("default", false)),
                    )
                }
                Web.skip(x.intro)?.let { intro = it }
                Web.skip(x.outro)?.let { outro = it }
            }
            val referer = x?.origin?.let { "$it/" } ?: Web.origin(embed)
            val streams = hls.map { s ->
                val o = JSONObject().put("url", s.url).put("type", s.type).put("server", item.name).put("referer", referer).put("subtitles", tracks)
                if (item.type == "hsub") o.put("subtitleType", "hardsub") else if (item.type == "sub") o.put("subtitleType", "softsub")
                if (s.variant != null) o.put("variant", s.variant)
                intro?.let { o.put("intro", it) }
                outro?.let { o.put("outro", it) }
                o
            }
            if (streams.isEmpty()) null else Out(streams, tracks, intro, outro)
        }

        val streams = ArrayList<JSONObject>()
        val tracks = JSONArray()
        val seenTrack = HashSet<String>()
        var intro: JSONObject? = null
        var outro: JSONObject? = null
        for (o in outs) {
            streams.addAll(o.streams)
            for (i in 0 until o.tracks.length()) o.tracks.optJSONObject(i)?.let { if (seenTrack.add(it.str("url") ?: "")) tracks.put(it) }
            if (intro == null) intro = o.intro
            if (outro == null) outro = o.outro
        }
        if (streams.isEmpty()) throw PrismException("AniKoto: no playable stream for episode $episode")
        // modern HLS first, then other HLS
        streams.sortBy { if (it.str("variant") == "modern") 0 else 1 }
        streams.forEachIndexed { i, s -> s.put("priority", if (i == 0) 5 else 4).put("isActive", i == 0) }
        val referer = streams[0].str("referer") ?: "$BASE/"
        return JSONObject().put("streams", JSONArray(streams)).put("subtitles", tracks)
            .put("intro", intro ?: JSONObject.NULL).put("outro", outro ?: JSONObject.NULL)
            .put("headers", JSONObject().put("User-Agent", Web.UA).put("Referer", referer))
    }

    private fun lang(label: String): String = when (label.lowercase().split(" ").firstOrNull()) {
        "english", "en" -> "en"; "japanese", "ja" -> "ja"; "french", "fr" -> "fr"; "german", "de" -> "de"
        "spanish", "es" -> "es"; "portuguese", "pt" -> "pt"; else -> "en"
    }
}
