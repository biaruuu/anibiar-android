package io.anibiar.app.data

import io.anibiar.app.App
import io.anibiar.app.core.Config
import io.anibiar.app.core.Net
import io.anibiar.app.core.arr
import io.anibiar.app.core.bool
import io.anibiar.app.core.int
import io.anibiar.app.core.jsonOf
import io.anibiar.app.core.obj
import io.anibiar.app.core.objects
import io.anibiar.app.core.parseObject
import io.anibiar.app.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/** A real answer from AniList (such as "not found"): retrying would not help. */
class DefiniteError(message: String) : Exception(message)

/**
 * AniList GraphQL, same questions as the website. Both doors are opened at the same moment (your site's cached copy
 * and AniList itself) and the first good answer wins, so one slow or blocked door never holds a screen.
 * The last good answer is also kept on the phone, so the app opens instantly.
 */
object AniList {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val MEMORY_MS = 5 * 60_000L
    private val gate = Semaphore(4)

    private class Mem(val at: Long, val data: JSONObject)

    private val memory = ConcurrentHashMap<String, Mem>()
    private val inflight = ConcurrentHashMap<String, Deferred<JSONObject>>()

    private suspend fun viaSite(body: String): JSONObject {
        val res = Net.postJson("${Config.siteUrl}/api/anilist", body, timeoutMs = 14_000)
        val data = parseObject(res.body)?.obj("data")
        if (res.code == 200 && data != null) return data
        throw IOException("site ${res.code}")
    }

    private suspend fun direct(body: String): JSONObject = gate.withPermit {
        val res = Net.postJson(ENDPOINT, body, mapOf("Accept" to "application/json"), timeoutMs = 14_000)
        val json = parseObject(res.body)
        if (res.code == 200 && json != null) {
            val errors = json.arr("errors")
            if (errors != null && errors.length() > 0) throw DefiniteError(errors.optJSONObject(0)?.str("message") ?: "AniList error")
            return@withPermit json.obj("data") ?: throw IOException("no data")
        }
        if (res.code == 404) throw DefiniteError("Not found.")
        throw IOException(if (res.code == 429) "AniList is busy right now. Try again in a moment." else "AniList error ${res.code}")
    }

    private suspend fun race(body: String): JSONObject = coroutineScope {
        val results = Channel<Result<JSONObject>>(Channel.UNLIMITED)
        val a = launch { results.send(runCatching { viaSite(body) }) }
        val b = launch { results.send(runCatching { direct(body) }) }
        var last: Throwable? = null
        for (i in 0 until 2) {
            val r = results.receive()
            if (r.isSuccess) {
                a.cancel(); b.cancel()
                return@coroutineScope r.getOrThrow()
            }
            val e = r.exceptionOrNull()!!
            if (e is DefiniteError) {
                a.cancel(); b.cancel()
                throw e
            }
            last = e
        }
        throw last ?: IOException("AniList did not answer")
    }

    /** A failed round is repeated once. */
    private suspend fun request(body: String): JSONObject {
        var last: Throwable? = null
        for (round in 0 until 2) {
            try {
                return race(body)
            } catch (e: DefiniteError) {
                throw Exception(e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                delay(600)
            }
        }
        throw Exception("Couldn't reach AniList. Check your connection and try again.")
    }

    /* ---- the copy kept on the phone ---- */

    private fun key(body: String): String {
        var h = 5381L
        for (c in body) h = ((h shl 5) + h + c.code) and 0x7fffffffL
        return "al-${h.toString(36)}${body.length.toString(36)}"
    }

    suspend fun query(q: String, variables: JSONObject = JSONObject()): JSONObject {
        val body = jsonOf("query" to q, "variables" to variables).toString()
        val hit = memory[body]
        if (hit != null && System.currentTimeMillis() - hit.at < MEMORY_MS) return hit.data
        val stored = AppCache.readLocal(key(body))
        val storedData = stored?.let { parseObject(it.data) }
        if (stored != null && storedData != null && stored.ageMinutes < 5) {
            memory[body] = Mem(stored.atMs, storedData)
            return storedData
        }
        val pending = inflight.getOrPut(body) {
            App.scope.async {
                try {
                    val data = request(body)
                    memory[body] = Mem(System.currentTimeMillis(), data)
                    AppCache.write(key(body), data.toString(), useUpstash = false)
                    data
                } finally {
                    inflight.remove(body)
                }
            }
        }
        // Older than 5 minutes but still on the phone: show it now and refresh in the background.
        if (stored != null && storedData != null && stored.ageDays < 2) return storedData
        return try {
            pending.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = memory[body]?.data ?: storedData
            old ?: throw e
        }
    }

    /* ---- the questions ---- */

    private const val MEDIA = """
        id
        title { romaji english }
        coverImage { extraLarge large color }
        format episodes seasonYear isAdult
    """

    suspend fun latestEpisodes(page: Int): LatestPage {
        val data = query(
            """query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 12) {
                pageInfo { lastPage }
                airingSchedules(notYetAired: false, sort: TIME_DESC) {
                  id airingAt episode
                  media { $MEDIA }
                }
              }
            }""",
            jsonOf("page" to page),
        )
        val pg = data.obj("Page") ?: throw Exception("AniList returned nothing.")
        val seen = HashSet<Int>()
        val items = ArrayList<LatestEpisode>()
        for (s in pg.arr("airingSchedules").objects()) {
            val m = s.obj("media") ?: continue
            if (m.bool("isAdult") == true || !seen.add(m.getInt("id"))) continue
            items.add(LatestEpisode(s.getInt("id"), s.optLong("airingAt"), s.getInt("episode"), Anime.from(m)))
        }
        return LatestPage(items, pg.obj("pageInfo")?.int("lastPage") ?: 1)
    }

    suspend fun trending(): List<Anime> {
        val data = query(
            """query {
              Page(page: 1, perPage: 8) {
                media(type: ANIME, sort: TRENDING_DESC, isAdult: false, status_in: [RELEASING, FINISHED]) {
                  $MEDIA
                  bannerImage description(asHtml: false) genres
                  status averageScore nextAiringEpisode { episode }
                }
              }
            }""",
        )
        return data.obj("Page")?.arr("media").objects().map { Anime.from(it) }.filter { it.banner != null || it.cover != null }
    }

    private suspend fun ranked(status: String): List<Anime> {
        val data = query(
            """query (${'$'}status: MediaStatus) {
              Page(page: 1, perPage: 10) {
                media(type: ANIME, status: ${'$'}status, sort: POPULARITY_DESC, isAdult: false) {
                  $MEDIA
                  bannerImage averageScore nextAiringEpisode { episode } status
                }
              }
            }""",
            jsonOf("status" to status),
        )
        return data.obj("Page")?.arr("media").objects().map { Anime.from(it) }
    }

    suspend fun topAiring() = ranked("RELEASING")
    suspend fun upcoming() = ranked("NOT_YET_RELEASED")

    suspend fun searchPage(search: String, page: Int, perPage: Int): SearchPage {
        val data = query(
            """query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: ANIME, search: ${'$'}search, isAdult: false, sort: SEARCH_MATCH) {
                  $MEDIA
                  status averageScore nextAiringEpisode { episode }
                }
              }
            }""",
            jsonOf("search" to search, "page" to page, "perPage" to perPage),
        )
        val pg = data.obj("Page") ?: throw Exception("AniList returned nothing.")
        return SearchPage(pg.arr("media").objects().map { Anime.from(it) }, pg.obj("pageInfo")?.bool("hasNextPage") == true)
    }

    fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[×✕]"), "x")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun score(hit: Anime, term: String, tokens: List<String>): Double {
        val titles = listOfNotNull(hit.title.english, hit.title.romaji, hit.title.native).plus(hit.synonyms).map { normalize(it) }
        var best = 0.0
        for (t in titles) {
            val s = when {
                t == term -> 100.0
                t.startsWith(term) -> 80.0
                t.split(' ').any { it.startsWith(if (tokens.isNotEmpty()) tokens.first() else term) } && tokens.all { t.contains(it) } -> 65.0
                t.contains(term) -> 55.0
                tokens.size > 1 && tokens.all { t.contains(it) } -> 45.0
                tokens.any { t.contains(it) } -> 15.0
                else -> 0.0
            }
            if (s > best) best = s
        }
        val pop = ((hit.popularity ?: 0) + 1).toDouble()
        val bonus = (ln(pop) / ln(10.0) * 2).coerceIn(0.0, 10.0)
        return best + bonus
    }

    /** "Type a few letters, find the show": exact and prefix title matches first, popularity breaks ties. */
    suspend fun searchSmart(raw: String, limit: Int): List<Anime> {
        val term = normalize(raw)
        if (term.isEmpty()) return emptyList()
        val tokens = term.split(' ').filter { it.isNotEmpty() }

        suspend fun run(q: String): List<Anime> {
            val data = query(
                """query (${'$'}search: String) {
                  Page(page: 1, perPage: 12) {
                    media(type: ANIME, search: ${'$'}search, isAdult: false, sort: SEARCH_MATCH) {
                      $MEDIA
                      title { romaji english native }
                      synonyms popularity status averageScore nextAiringEpisode { episode }
                    }
                  }
                }""",
                jsonOf("search" to q),
            )
            return data.obj("Page")?.arr("media").objects().map { Anime.from(it) }
        }

        var hits = run(raw.trim())
        if (hits.isEmpty() && tokens.size > 1) hits = run(tokens.dropLast(1).joinToString(" "))
        return hits.withIndex().map { (i, a) -> a to (score(a, term, tokens) - i * 0.01) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    suspend fun detail(id: Int): Detail? {
        val mini = "id title { romaji english } coverImage { extraLarge large color } format episodes seasonYear"
        val data = query(
            """query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                $mini
                idMal bannerImage description(asHtml: false) genres status duration season siteUrl
                title { romaji english native }
                startDate { year month day } endDate { year month day }
                averageScore meanScore popularity nextAiringEpisode { episode }
                studios { edges { isMain node { name } } }
                trailer { id site thumbnail }
                relations { edges { relationType(version: 2) node { $mini type startDate { year } } } }
                recommendations(sort: RATING_DESC, perPage: 12) {
                  nodes { mediaRecommendation { $mini isAdult } }
                }
              }
            }""",
            jsonOf("id" to id),
        )
        val m = data.obj("Media") ?: return null
        var studio: String? = null
        val edges = m.obj("studios")?.arr("edges").objects()
        for (e in edges) if (e.bool("isMain") == true) studio = e.obj("node")?.str("name")
        if (studio == null) studio = edges.firstOrNull()?.obj("node")?.str("name")
        val trailer = m.obj("trailer")
        val relations = ArrayList<Relation>()
        for (e in m.obj("relations")?.arr("edges").objects()) {
            val n = e.obj("node") ?: continue
            if (n.str("type") != "ANIME") continue
            relations.add(Relation((e.str("relationType") ?: "").replace('_', ' '), Anime.from(n), n.str("type"), n.obj("startDate")?.int("year")))
        }
        val recs = ArrayList<Anime>()
        for (n in m.obj("recommendations")?.arr("nodes").objects()) {
            val r = n.obj("mediaRecommendation") ?: continue
            if (r.bool("isAdult") == true) continue
            recs.add(Anime.from(r))
        }
        return Detail(
            anime = Anime.from(m),
            idMal = m.int("idMal"),
            duration = m.int("duration"),
            season = m.str("season"),
            start = FuzzyDate.from(m.obj("startDate")),
            end = FuzzyDate.from(m.obj("endDate")),
            meanScore = m.int("meanScore"),
            studio = studio,
            trailerId = trailer?.str("id"),
            trailerSite = trailer?.str("site"),
            relations = relations,
            recommendations = recs,
            siteUrl = m.str("siteUrl"),
        )
    }
}
