package io.anibiar.app.data

import io.anibiar.app.core.Net
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * "Nimbus": the second server that runs inside the app, a Kotlin port of the AnimeHeaven source of the AniVault scraper.
 * It finds the anime on animeheaven.me by title (strict scoring, so a wrong season is rejected instead of played),
 * reads the episode list, opens the episode's gate page with the session cookies and returns the direct MP4.
 * Sub only; the picture carries its own subtitles.
 */
object Heaven {
    const val NAME = "Nimbus"
    private const val BASE = "https://animeheaven.me"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cookies = LinkedHashMap<String, String>()
    private val ids = ConcurrentHashMap<Int, String>()
    private val episodeLists = ConcurrentHashMap<String, List<Pair<String, Double>>>() // heaven id -> (gate key, number)

    private fun baseHeaders(extra: Map<String, String> = emptyMap()) = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$BASE/",
    ) + extra

    private fun remember(setCookies: List<String>) {
        synchronized(cookies) {
            for (raw in setCookies) {
                val pair = raw.substringBefore(';').trim()
                val i = pair.indexOf('=')
                if (i > 0) cookies[pair.substring(0, i).trim()] = pair.substring(i + 1)
            }
        }
    }

    private fun cookieHeader(key: String? = null): String = synchronized(cookies) {
        val jar = LinkedHashMap(cookies)
        if (key != null) jar["key"] = key
        jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /* ------------------------------ title matching ------------------------------ */

    private val typeWords = setOf("ova", "ona", "special", "specials", "movie", "film", "recap", "picture", "pv", "season", "part", "cour", "saga")

    private fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun words(t: String) = t.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split(Regex("\\s+")).filter { it.length >= 2 }

    private fun addsTypeWord(query: String, candidate: String): Boolean {
        val q = words(query).toSet()
        return words(candidate).any { it in typeWords && it !in q }
    }

    private fun score(query: String, title: String): Int {
        val needle = norm(query)
        val hay = norm(title)
        if (needle.isEmpty() || hay.isEmpty()) return 0
        if (hay == needle) return 100
        val ratio = minOf(needle.length, hay.length).toDouble() / maxOf(needle.length, hay.length)
        val queryLonger = needle.length > hay.length
        val missing = if (queryLonger) words(query).size - words(title).size else 0
        val spinoff = !queryLonger && addsTypeWord(query, title)
        if (hay.startsWith(needle) || needle.startsWith(hay)) {
            if (queryLonger && missing >= 2) return (ratio * 30).toInt()
            if (spinoff) return (ratio * 30).toInt()
            return if (ratio >= 0.6) 80 else (ratio * 60).toInt()
        }
        if (hay.contains(needle) || needle.contains(hay)) {
            if (queryLonger && missing >= 2) return (ratio * 25).toInt()
            if (spinoff) return (ratio * 25).toInt()
            return if (ratio >= 0.6) 60 else (ratio * 45).toInt()
        }
        var matches = 0
        for (ch in needle) if (hay.contains(ch)) matches++
        return (matches.toDouble() / maxOf(needle.length, 1) * 40).toInt()
    }

    private class Hit(val id: String, val title: String)

    private suspend fun search(q: String): List<Hit> {
        val url = "$BASE/fastsearch.php?xhr=1&s=" + URLEncoder.encode(q, "UTF-8")
        val r = Net.getRaw(url, baseHeaders(mapOf("Accept" to "text/html,*/*")), 12_000)
        remember(r.setCookies)
        if (r.code !in 200..299) return emptyList()
        val doc = Jsoup.parse(r.body)
        return doc.select("a[href*=\"anime.php?\"]").mapNotNull { a ->
            val id = a.attr("href").substringAfter('?', "").trim()
            val title = a.select(".fastname").text().trim().ifEmpty { a.select("img").attr("alt").trim() }
            if (id.isEmpty() || title.isEmpty()) null else Hit(id, title)
        }
    }

    private suspend fun findId(titles: List<String>): String? {
        if (titles.isEmpty()) return null
        val tried = HashSet<String>()
        val all = ArrayList<Hit>()
        for (title in titles.take(3)) {
            val plain = title.replace(Regex("[’']s\\b", RegexOption.IGNORE_CASE), "")
            val variants = listOf(
                title,
                plain,
                title.replace(Regex("[’']"), ""),
                title.split(Regex("[:(|-]")).first().trim(),
                title.replace(Regex("[’']"), "").split(Regex("\\s+")).take(2).joinToString(" "),
            ).filter { it.length >= 3 && tried.add(it.lowercase()) }
            for (v in variants) {
                val found = try { search(v) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                all.addAll(found)
                if (found.any { h -> titles.any { t -> score(t, h.title) >= 80 } }) break
            }
            if (all.any { h -> titles.any { t -> score(t, h.title) >= 80 } }) break
        }
        val best = all.distinctBy { it.id }.map { h -> h to (titles.maxOf { t -> score(t, h.title) }) }.maxByOrNull { it.second } ?: return null
        // below 60 it is more likely a different anime than the right one: better no stream than a wrong one
        return if (best.second >= 60) best.first.id else null
    }

    /* ------------------------------ episodes and stream ------------------------------ */

    private suspend fun episodes(heavenId: String): List<Pair<String, Double>> {
        episodeLists[heavenId]?.let { return it }
        val r = Net.getRaw("$BASE/anime.php?$heavenId", baseHeaders(), 15_000)
        remember(r.setCookies)
        if (r.code !in 200..299) return emptyList()
        val doc = Jsoup.parse(r.body)
        val gate = Regex("""gate[ha]\s*\(\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val gateLoose = Regex("""gate[ha]\s*\(\s*([^,)\s]+)""", RegexOption.IGNORE_CASE)
        val marker = Regex("""(?:gate[ha]\s*\(|gate\.php|episode)""", RegexOption.IGNORE_CASE)
        val out = ArrayList<Pair<String, Double>>()
        for (a in doc.select("a")) {
            val href = a.attr("href")
            val attrs = listOf(a.attr("onmouseover"), a.attr("onclick"), href).joinToString(" ")
            val text = a.text().replace(Regex("\\s+"), " ").trim()
            if (!marker.containsMatchIn("$attrs $text")) continue
            val key = gate.find(attrs)?.groupValues?.get(1)
                ?: gateLoose.find(attrs)?.groupValues?.get(1)
                ?: Regex("""[?&](?:key|id)=([^&#]+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)
                ?: continue
            val raw = a.select(".watch2").firstOrNull()?.text()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: Regex("""episode\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                ?: Regex("""^([0-9]+(?:\.[0-9]+)?)$""").find(text)?.groupValues?.get(1)
                ?: continue
            val num = raw.replace(Regex("^0+(\\d)"), "$1").toDoubleOrNull() ?: continue
            out.add(java.net.URLDecoder.decode(key, "UTF-8") to num)
        }
        val unique = out.distinctBy { it.first }.sortedBy { it.second }
        if (unique.isNotEmpty()) episodeLists[heavenId] = unique
        return unique
    }

    private suspend fun gate(key: String): String? {
        val r = Net.getRaw(
            "$BASE/gate.php",
            baseHeaders(mapOf("Cookie" to cookieHeader(key), "Origin" to BASE, "Cache-Control" to "no-cache")),
            15_000,
        )
        if (r.code !in 200..299) return null
        val doc = Jsoup.parse(r.body)
        // the gate sometimes answers with a placeholder page instead of the episode: never play that
        if (Regex("watch\\s+episode\\s+on\\s+animeheaven\\.me", RegexOption.IGNORE_CASE).containsMatchIn(doc.text().replace(Regex("\\s+"), " "))) return null
        val sources = doc.select("video source").map { it.attr("src").trim() }.filter { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        return sources.firstOrNull { Regex("""\.mp4(?:$|\?)""", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: sources.firstOrNull()
    }

    suspend fun stream(anilistId: Int, episode: Int, dub: Boolean): Vault.Result? {
        if (dub) return null
        val heavenId = ids[anilistId] ?: findId(AniList.titles(anilistId))?.also { ids[anilistId] = it } ?: return null
        val list = episodes(heavenId)
        val ep = list.firstOrNull { it.second.roundToInt() == episode && it.second - it.second.toInt() == 0.0 }
            ?: list.firstOrNull { it.second.roundToInt() == episode }
            ?: return null
        val url = gate(ep.first) ?: return null
        val source = StreamSource(
            id = "nimbus-0",
            name = NAME,
            soft = false,
            hls = !Regex("""\.mp4(?:$|\?)""", RegexOption.IGNORE_CASE).containsMatchIn(url),
            src = url,
            headers = linkedMapOf("User-Agent" to UA, "Accept" to "*/*", "Referer" to "$BASE/", "Cookie" to cookieHeader(ep.first)),
            subtitles = emptyList(),
        )
        return Vault.Result(source, null, null)
    }
}
