package io.anibiar.app.data

import io.anibiar.app.App
import io.anibiar.app.core.Config
import io.anibiar.app.prism.Prism
import kotlinx.coroutines.withTimeoutOrNull
import io.anibiar.app.core.Net
import io.anibiar.app.core.arr
import io.anibiar.app.core.bool
import io.anibiar.app.core.dbl
import io.anibiar.app.core.int
import io.anibiar.app.core.obj
import io.anibiar.app.core.objects
import io.anibiar.app.core.parseArray
import io.anibiar.app.core.parseObject
import io.anibiar.app.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ApiException(message: String) : Exception(message)

class TimeRange(val start: Double, val end: Double) {
    companion object {
        fun from(j: Any?): TimeRange? {
            val o = j as? JSONObject ?: return null
            val s = o.dbl("start")
            val e = o.dbl("end")
            return if (s != null && e != null && e > s) TimeRange(s, e) else null
        }
    }
}

class WatchEpisode(
    val number: Int,
    var title: String,
    var description: String? = null,
    var image: String? = null,
    var airDate: String? = null,
    var duration: Int? = null,
    val filler: Boolean = false,
    /** Providers that have this episode, in priority order. */
    val providers: MutableList<String> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("n", number).put("t", title).put("d", description).put("i", image)
        .put("a", airDate).put("u", duration).put("f", filler).put("p", JSONArray(providers))

    companion object {
        fun fromJson(j: JSONObject) = WatchEpisode(
            number = j.getInt("n"),
            title = j.str("t") ?: "Episode ${j.getInt("n")}",
            description = j.str("d"),
            image = j.str("i"),
            airDate = j.str("a"),
            duration = j.int("u"),
            filler = j.bool("f") == true,
            providers = (j.arr("p")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } } } ?: emptyList()).toMutableList(),
        )
    }
}

class EpisodesPayload(val title: String?, val sub: List<WatchEpisode>, val dub: List<WatchEpisode>) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("sub", JSONArray(sub.map { it.toJson() }))
        .put("dub", JSONArray(dub.map { it.toJson() }))

    companion object {
        fun fromJson(j: JSONObject) = EpisodesPayload(
            j.str("title"),
            j.arr("sub").objects().map { WatchEpisode.fromJson(it) },
            j.arr("dub").objects().map { WatchEpisode.fromJson(it) },
        )
    }
}

class StreamSubtitle(val label: String, val lang: String, val src: String, val isDefault: Boolean, val headers: Map<String, String> = emptyMap())

class StreamSource(
    val id: String,
    val name: String,
    /** true = separate subtitle tracks, false = subtitles burned into the video. */
    val soft: Boolean,
    val hls: Boolean,
    val src: String,
    val headers: Map<String, String>,
    val subtitles: List<StreamSubtitle>,
    val intro: TimeRange? = null,
    val outro: TimeRange? = null,
)

class WatchPayload(val streams: List<StreamSource>, val intro: TimeRange? = null, val outro: TimeRange? = null)

class SkipTimes(val intro: TimeRange? = null, val outro: TimeRange? = null, val length: Double? = null)

class AvatarDeco(
    val id: String,
    val label: String,
    /** Full address of the sprite sheet image. */
    val sheet: String,
    val frames: Int,
    val cols: Int,
    val frameSize: Int,
    val fps: Double,
    val scale: Double,
)

private val NAMES = listOf("Nova", "Orion", "Lyra", "Atlas", "Comet", "Zephyr", "Pulse", "Ember", "Echo", "Halo", "Vega", "Aero")

private fun hash(text: String): Int {
    var h = 5381L
    for (c in text) h = (h * 33 + c.code) and 0xFFFFFFFFL
    return (h % Int.MAX_VALUE).toInt()
}

/** Invented names for the servers (the same idea as the website: real provider names are never shown). */
private fun nameFor(provider: String, server: String, taken: MutableSet<String>): String {
    var i = hash("$provider:${server.lowercase()}") % NAMES.size
    for (n in NAMES.indices) {
        if (taken.add(NAMES[i])) return NAMES[i]
        i = (i + 1) % NAMES.size
    }
    val fallback = "Server ${taken.size + 1}"
    taken.add(fallback)
    return fallback
}

/**
 * Episodes and streams from Prism, the engine built into the app, intro / outro times from AniSkip.
 * Stream links need a Referer header; the player sends it itself, so no proxy is needed.
 */
object Streams {
    private suspend fun getJson(url: String, timeoutMs: Long): JSONObject {
        val res = try {
            Net.get(url, mapOf("Accept" to "application/json", "User-Agent" to Net.UA), timeoutMs)
        } catch (e: IOException) {
            throw ApiException("The streaming server did not answer. Try again in a moment.")
        }
        val json = parseObject(res.body)
        if (res.code != 200 || json == null) {
            throw ApiException(json?.str("error") ?: "Streaming server error (HTTP ${res.code}).")
        }
        return json
    }

    /* --------------------------------- episodes --------------------------------- */

    private class Mem(val at: Long, val data: EpisodesPayload)

    private val memory = ConcurrentHashMap<Int, Mem>()
    private val inflight = ConcurrentHashMap<Int, Deferred<EpisodesPayload>>()
    private val listeners = ConcurrentHashMap<Int, CopyOnWriteArrayList<(EpisodesPayload) -> Unit>>()

    /**
     * Episodes of an anime. Every provider is asked at the same time and the first answer is returned straight away;
     * slower providers are merged in later and delivered through [onUpdate]. A copy saved on the phone (or in Upstash)
     * is returned instantly and refreshed quietly in the background.
     */
    suspend fun episodes(animeId: Int, force: Boolean = false, onUpdate: ((EpisodesPayload) -> Unit)? = null): EpisodesPayload {
        if (onUpdate != null) listeners.getOrPut(animeId) { CopyOnWriteArrayList() }.add(onUpdate)
        val hit = memory[animeId]
        if (!force && hit != null && System.currentTimeMillis() - hit.at < 3 * 60_000) return hit.data
        if (!force) {
            val cached = AppCache.read("ep:$animeId")
            val parsed = cached?.let { c -> parseObject(c.data)?.let { runCatching { EpisodesPayload.fromJson(it) }.getOrNull() } }
            if (cached != null && parsed != null && (parsed.sub.isNotEmpty() || parsed.dub.isNotEmpty())) {
                memory[animeId] = Mem(cached.atMs, parsed)
                if (cached.ageMinutes >= 3) refresh(animeId, quiet = true)
                return parsed
            }
        }
        return refresh(animeId, quiet = false).await()
    }

    private fun refresh(id: Int, quiet: Boolean): Deferred<EpisodesPayload> =
        inflight.getOrPut(id) { App.scope.async { try { runEpisodes(id, quiet) } finally { inflight.remove(id) } } }

    private fun merge(order: List<String>, data: Map<String, JSONObject>, audio: String): List<WatchEpisode> {
        val byNumber = sortedMapOf<Int, WatchEpisode>()
        for (provider in order) {
            val list = data[provider]?.obj("episodes")?.arr(audio).objects()
            for (raw in list) {
                val number = raw.int("number") ?: continue
                if (number < 0) continue
                val have = byNumber[number]
                val dur = raw.int("duration")
                if (have == null) {
                    byNumber[number] = WatchEpisode(
                        number = number,
                        title = raw.str("title") ?: "Episode $number",
                        description = raw.str("description"),
                        image = raw.str("image"),
                        airDate = raw.str("airDate"),
                        duration = dur,
                        filler = raw.bool("filler") == true,
                        providers = mutableListOf(provider),
                    )
                } else {
                    have.providers.add(provider)
                    if (Regex("^Episode \\d+$").matches(have.title) && raw.str("title") != null) have.title = raw.str("title")!!
                    if (have.description == null) have.description = raw.str("description")
                    if (have.image == null) have.image = raw.str("image")
                    if (have.airDate == null) have.airDate = raw.str("airDate")
                    if (have.duration == null) have.duration = dur
                }
            }
        }
        return byNumber.values.toList()
    }

    private fun build(order: List<String>, data: Map<String, JSONObject>): EpisodesPayload? {
        val sub = merge(order, data, "sub")
        val dub = merge(order, data, "dub")
        if (sub.isEmpty() && dub.isEmpty()) return null
        var title: String? = null
        for (p in order) {
            val t = data[p]?.obj("meta")?.str("title")
            if (t != null) { title = t; break }
        }
        return EpisodesPayload(title, sub, dub)
    }

    private suspend fun runEpisodes(id: Int, quiet: Boolean): EpisodesPayload {
        val order = Config.providers
        val found = ConcurrentHashMap<String, JSONObject>()
        val done = CompletableDeferred<EpisodesPayload>()
        var pending = order.size
        var failures = 0
        val lock = Any()

        fun deliver(p: EpisodesPayload) {
            memory[id] = Mem(System.currentTimeMillis(), p)
            App.scope.launch { AppCache.write("ep:$id", p.toJson().toString()) }
            listeners[id]?.forEach { it(p) }
        }

        val jobs = order.map { provider ->
            App.scope.launch {
                var failed = false
                try {
                    val raw = (withTimeoutOrNull(25_000) { Prism.episodes(provider, id) } ?: throw ApiException("Timed out"))
                    found[provider] = raw
                    val p = build(order, found.toMap())
                    if (p != null) {
                        if (!done.isCompleted) done.complete(p)
                        val left = synchronized(lock) { pending }
                        if (!quiet && left > 1) deliver(p)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                } finally {
                    val left = synchronized(lock) {
                        if (failed) failures++
                        pending--
                        pending
                    }
                    if (left == 0) {
                        val p = build(order, found.toMap())
                        if (p != null) {
                            deliver(p)
                            if (!done.isCompleted) done.complete(p)
                        } else if (!done.isCompleted) {
                            done.completeExceptionally(
                                ApiException(
                                    if (failures >= order.size) "Could not reach the streaming server. Check your connection and try again."
                                    else "No server has this anime yet. Try again later.",
                                ),
                            )
                        }
                    }
                }
            }
        }
        jobs.forEach { /* started above */ }
        return done.await()
    }

    /** Episode counts (sub, dub) when this anime's episode list is already known (memory), otherwise null. */
    fun counts(animeId: Int): Pair<Int, Int>? = memory[animeId]?.data?.let { it.sub.size to it.dub.size }

    private val dubMemory = ConcurrentHashMap<String, Boolean>()

    /**
     * Does this episode have an English dub on any server? Every provider is asked at the same moment and the answer
     * is "yes" as soon as one of them lists it, so a slow or sub-only provider never causes a wrong "no".
     */
    suspend fun hasDub(animeId: Int, episode: Int): Boolean {
        val key = "$animeId:$episode"
        dubMemory[key]?.let { return it }
        memory[animeId]?.data?.dub?.let { list -> if (list.any { it.number == episode }) { dubMemory[key] = true; return true } }
        dubGate.acquire()
        try {
            return hasDubNow(animeId, episode, key)
        } finally {
            dubGate.release()
        }
    }

    /** The home screen asks about many cards at once; only one is looked up at a time so the phone and the sites aren't flooded. */
    private val dubGate = kotlinx.coroutines.sync.Semaphore(1)

    private suspend fun hasDubNow(animeId: Int, episode: Int, key: String): Boolean {
        dubMemory[key]?.let { return it }
        val order = Config.providers
        val result = CompletableDeferred<Boolean>()
        val lock = Any()
        var left = order.size
        var answered = 0
        for (provider in order) {
            App.scope.launch {
                var ok = false
                try {
                    val raw = (withTimeoutOrNull(20_000) { Prism.episodes(provider, animeId) } ?: throw ApiException("Timed out"))
                    ok = true
                    val has = raw.obj("episodes")?.arr("dub").objects().any { it.int("number") == episode }
                    if (has) result.complete(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    val last = synchronized(lock) { if (ok) answered++; --left == 0 }
                    if (last && !result.isCompleted) result.complete(false)
                }
            }
        }
        val r = result.await()
        // a "no" is only remembered when at least one server really answered
        if (r || synchronized(lock) { answered } > 0) dubMemory[key] = r
        return r
    }

    /* ----------------------------------- streams ----------------------------------- */

    private class WatchMem(val at: Long, val data: WatchPayload)

    private val watchMemory = ConcurrentHashMap<String, WatchMem>()

    /**
     * Streams for one episode. Providers are asked at the same time; the first one with a playable stream is
     * returned right away and the others are added through [onMore] (so playback starts as early as possible).
     */
    private class Flight(val job: Deferred<WatchPayload>, val more: CopyOnWriteArrayList<(WatchPayload) -> Unit>)

    private val flights = HashMap<String, Flight>()

    /** Asking twice for the same episode (a warm-up and the real call) shares one set of requests. */
    suspend fun watch(
        animeId: Int,
        dub: Boolean,
        episode: Int,
        providers: List<String> = emptyList(),
        onMore: ((WatchPayload) -> Unit)? = null,
    ): WatchPayload {
        val key = "$animeId:${if (dub) "dub" else "sub"}:$episode:${providers.joinToString(",")}"
        val hit = watchMemory[key]
        if (hit != null && System.currentTimeMillis() - hit.at < 90_000) return hit.data
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                val more = CopyOnWriteArrayList<(WatchPayload) -> Unit>()
                val job = App.scope.async { watchNow(animeId, dub, episode, providers) { p -> more.forEach { it(p) } } }
                job.invokeOnCompletion { synchronized(flights) { flights.remove(key) } }
                Flight(job, more)
            }
        }
        if (onMore != null) flight.more.add(onMore)
        return flight.job.await()
    }

    private suspend fun watchNow(
        animeId: Int,
        dub: Boolean,
        episode: Int,
        providers: List<String>,
        onMore: ((WatchPayload) -> Unit)?,
    ): WatchPayload {
        val key = "$animeId:${if (dub) "dub" else "sub"}:$episode:${providers.joinToString(",")}"
        val audio = if (dub) "dub" else "sub"
        // "aurora" is the server built into the app (Vault.kt); it is asked together with the others
        val natives = listOf(Vault.NAME.lowercase(), Heaven.NAME.lowercase())
        val order = Config.providers.filter { providers.isEmpty() || providers.contains(it) } + natives
        val taken = HashSet<String>()
        val results = ConcurrentHashMap<String, ParsedWatch>()
        val done = CompletableDeferred<WatchPayload>()
        var pending = order.size
        val lock = Any()

        fun buildPayload(): WatchPayload {
            val streams = ArrayList<StreamSource>()
            var intro: TimeRange? = null
            var outro: TimeRange? = null
            for (p in order) {
                val r = results[p] ?: continue
                streams.addAll(r.streams)
                if (intro == null) intro = r.intro
                if (outro == null) outro = r.outro
            }
            // soft-sub servers (separate captions) go first, then hard-sub ones; each keeps its own order
            return WatchPayload(streams.sortedByDescending { it.soft }, intro, outro)
        }
        var grace = false

        for (provider in order) {
            App.scope.launch {
                try {
                    val r = if (provider in natives) {
                        val v = (if (provider == Heaven.NAME.lowercase()) Heaven.stream(animeId, episode, dub) else Vault.stream(animeId, episode, dub))
                            ?: throw ApiException("$provider has no stream for this episode.")
                        synchronized(taken) { taken.add(v.source.name) }
                        ParsedWatch(listOf(v.source), v.intro, v.outro)
                    } else {
                        val raw = (withTimeoutOrNull(25_000) { Prism.watch(provider, animeId, audio, episode) } ?: throw ApiException("Timed out"))
                        synchronized(taken) { parseWatch(provider, dub, raw, taken) }
                    }
                    if (r.streams.isNotEmpty()) {
                        results[provider] = r
                        val p = buildPayload()
                        watchMemory[key] = WatchMem(System.currentTimeMillis(), p)
                        if (done.isCompleted) {
                            onMore?.invoke(p)
                        } else if (p.streams.any { it.soft }) {
                            done.complete(p)
                        } else if (!grace) {
                            // only hard-sub so far: give a soft-sub server up to 1.5 s to answer before settling
                            grace = true
                            App.scope.launch {
                                kotlinx.coroutines.delay(1_500)
                                if (!done.isCompleted) done.complete(buildPayload())
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    val left = synchronized(lock) { --pending }
                    if (left == 0 && !done.isCompleted) {
                        if (results.isNotEmpty()) done.complete(buildPayload())
                        else done.completeExceptionally(ApiException("No playable stream was found for this episode. Try another episode or try again later."))
                    }
                }
            }
        }
        return done.await()
    }

    private class ParsedWatch(val streams: List<StreamSource>, val intro: TimeRange?, val outro: TimeRange?)

    private val thumbs = Regex("thumbnail|storyboard|sprite|preview", RegexOption.IGNORE_CASE)
    private val httpUrl = Regex("^https?://", RegexOption.IGNORE_CASE)

    private fun parseWatch(provider: String, dub: Boolean, data: JSONObject, taken: MutableSet<String>): ParsedWatch {
        val streams = ArrayList<StreamSource>()
        val seen = HashSet<String>()
        val all = data.arr("streams").objects()
        fun prio(s: JSONObject) = s.dbl("priority") ?: 0.0
        val ordered = all.sortedWith(Comparator { a, b ->
            val act = (if (b.bool("isActive") == true) 1 else 0) - (if (a.bool("isActive") == true) 1 else 0)
            if (act != 0) act else prio(b).compareTo(prio(a))
        })
        val topHeaders = data.obj("headers")
        for (s in ordered) {
            val type = (s.str("type") ?: "").lowercase()
            val hls = type == "hls" || type == "m3u8"
            val mp4 = type == "mp4"
            val url = s.str("url")
            if (!(hls || mp4) || url == null || !httpUrl.containsMatchIn(url) || !seen.add(url)) continue
            val referer = s.str("referer") ?: s.obj("headers")?.str("Referer") ?: topHeaders?.str("Referer") ?: ""
            val ua = s.obj("headers")?.str("User-Agent") ?: topHeaders?.str("User-Agent") ?: Net.UA
            val headers = LinkedHashMap<String, String>()
            headers["User-Agent"] = ua
            headers["Accept"] = "*/*"
            if (referer.isNotEmpty()) {
                headers["Referer"] = referer
                try {
                    val u = java.net.URI(referer)
                    if (u.scheme != null && u.host != null) headers["Origin"] = "${u.scheme}://${u.host}" + (if (u.port > 0) ":${u.port}" else "")
                } catch (_: Exception) {
                }
            }
            val subArr = s.arr("subtitles")?.takeIf { it.length() > 0 } ?: data.arr("subtitles")
            val subtitles = ArrayList<StreamSubtitle>()
            for (t in subArr.objects()) {
                val tu = t.str("url") ?: t.str("src") ?: t.str("file")
                val blob = "${tu ?: ""} ${t.str("label") ?: ""}"
                if (thumbs.containsMatchIn(blob)) continue
                if (tu == null || !httpUrl.containsMatchIn(tu)) continue
                val r = t.obj("headers")?.str("Referer") ?: referer
                val h = LinkedHashMap<String, String>()
                h["User-Agent"] = ua
                if (r.isNotEmpty()) h["Referer"] = r
                subtitles.add(
                    StreamSubtitle(
                        label = t.str("label") ?: t.str("lang") ?: t.str("language") ?: "Subtitle",
                        lang = t.str("srclang") ?: t.str("lang") ?: t.str("language") ?: "und",
                        src = tu,
                        isDefault = t.bool("default") == true,
                        headers = h,
                    ),
                )
            }
            val subType = s.str("subtitleType")
            // When a provider doesn't say, a sub stream without caption tracks has its subtitles in the picture.
            val soft = if (subType == "hardsub") false else if (subType == "softsub" || subtitles.isNotEmpty()) true else dub
            streams.add(
                StreamSource(
                    id = "$provider-${streams.size}",
                    name = nameFor(provider, s.str("server") ?: provider, taken),
                    soft = soft,
                    hls = hls,
                    src = url,
                    headers = headers,
                    subtitles = subtitles,
                    intro = TimeRange.from(s.opt("intro") ?: data.opt("intro")),
                    outro = TimeRange.from(s.opt("outro") ?: data.opt("outro")),
                ),
            )
        }
        val first = ordered.firstOrNull()
        return ParsedWatch(
            streams,
            TimeRange.from(data.opt("intro") ?: first?.opt("intro")),
            TimeRange.from(data.opt("outro") ?: first?.opt("outro")),
        )
    }

    /* ------------------------------ intro / outro (AniSkip) ------------------------------ */

    suspend fun skip(mal: Int, episode: Int): SkipTimes {
        return try {
            val res = Net.get(
                "https://api.aniskip.com/v2/skip-times/$mal/$episode?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&episodeLength=0",
                timeoutMs = 8_000,
            )
            if (!res.ok) return SkipTimes()
            val results = parseObject(res.body)?.arr("results").objects()
            fun pick(types: List<String>) = results.firstOrNull { it.str("skipType") in types }
            fun range(r: JSONObject?): TimeRange? {
                val i = r?.obj("interval") ?: return null
                val s = i.dbl("startTime")
                val e = i.dbl("endTime")
                return if (s != null && e != null) TimeRange(s, e) else null
            }
            val op = pick(listOf("op", "mixed-op"))
            val ed = pick(listOf("ed", "mixed-ed"))
            val len = op?.dbl("episodeLength") ?: ed?.dbl("episodeLength")
            SkipTimes(range(op), range(ed), if (len != null && len > 0) len else null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SkipTimes()
        }
    }

    /* ------------------------------- avatar decorations ------------------------------- */

    private var decos: List<AvatarDeco>? = null

    suspend fun decorations(): List<AvatarDeco> {
        decos?.let { return it }
        return try {
            val res = Net.get(
                "${Config.supabaseUrl}/rest/v1/avatar_decorations?select=*&active=eq.true&order=sort.asc",
                mapOf("apikey" to Config.supabaseKey, "Authorization" to "Bearer ${Config.supabaseKey}"),
                timeoutMs = 12_000,
            )
            val rows = parseArray(res.body)
            if (!res.ok || rows == null) return emptyList()
            val out = ArrayList<AvatarDeco>()
            for (j in rows.objects()) {
                val sheet = j.str("sheet") ?: continue
                out.add(
                    AvatarDeco(
                        id = j.opt("id").toString(),
                        label = j.str("label") ?: j.opt("id").toString(),
                        sheet = Config.abs(sheet),
                        frames = j.int("frames") ?: 1,
                        cols = j.int("cols") ?: 1,
                        frameSize = j.int("frame_size") ?: 64,
                        fps = j.dbl("fps") ?: 12.0,
                        scale = j.dbl("scale") ?: 1.0,
                    ),
                )
            }
            decos = out
            out
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
}
