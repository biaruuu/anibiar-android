package io.anibiar.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anibiar.app.App
import io.anibiar.app.core.Prefs
import io.anibiar.app.core.friendlyError
import io.anibiar.app.data.AniList
import io.anibiar.app.data.ApiException
import io.anibiar.app.data.Collection
import io.anibiar.app.data.Detail
import io.anibiar.app.data.EpisodesPayload
import io.anibiar.app.data.HistoryItem
import io.anibiar.app.data.Library
import io.anibiar.app.data.SkipTimes
import io.anibiar.app.data.StreamSource
import io.anibiar.app.data.Streams
import io.anibiar.app.data.TimeRange
import io.anibiar.app.data.WatchEpisode
import io.anibiar.app.data.WatchPayload
import io.anibiar.app.data.stripHtml
import io.anibiar.app.ui.player.FullscreenEffect
import io.anibiar.app.ui.player.PlayerState
import io.anibiar.app.ui.player.VideoPlayer
import io.anibiar.app.ui.player.rememberPlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

private class SkipData(val intro: TimeRange?, val outro: TimeRange?, val length: Double?)

/** All the state and the loading logic of one watch page. */
private class WatchModel(
    val animeId: Int,
    startEpisode: Int,
    startDub: Boolean,
    val player: PlayerState,
    val scope: CoroutineScope,
) {
    var detail by mutableStateOf<Detail?>(null)
    var eps by mutableStateOf<EpisodesPayload?>(null)
    var epsError by mutableStateOf<String?>(null)
    var watch by mutableStateOf<WatchPayload?>(null)
    var watchError by mutableStateOf<String?>(null)
    var backup by mutableStateOf<SkipTimes?>(null)

    var isDub by mutableStateOf(startDub)
    var ep by mutableIntStateOf(startEpisode)
    var serverName by mutableStateOf<String?>(null)
    var loved by mutableStateOf(false)
    var saved by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var reverse by mutableStateOf(false)
    var synopsis by mutableStateOf(false)

    private var saved0: HistoryItem? = null // history row used to resume
    private val gen = AtomicInteger(0)
    private var lastSave = 0L

    val list: List<WatchEpisode>
        get() {
            val e = eps ?: return emptyList()
            return if (isDub && e.dub.isNotEmpty()) e.dub else e.sub
        }

    val current: WatchEpisode? get() = list.firstOrNull { it.number == ep }
    private val pos: Int get() = list.indexOfFirst { it.number == ep }
    val prev: WatchEpisode? get() = if (pos > 0) list[pos - 1] else null
    val next: WatchEpisode? get() = if (pos >= 0 && pos < list.size - 1) list[pos + 1] else null
    val name: String get() = detail?.anime?.name ?: eps?.title ?: "Anime $animeId"

    /** The stream that is playing: the chosen server by name, otherwise the first one. */
    val stream: StreamSource?
        get() {
            val w = watch ?: return null
            if (w.streams.isEmpty()) return null
            val n = serverName
            if (n != null) w.streams.firstOrNull { it.name == n }?.let { return it }
            return w.streams.first()
        }

    fun start() {
        player.autoplay = Prefs.getBool("watch.autoplay", true)
        player.autoNext = Prefs.getBool("watch.autoNext", true)
        player.autoSkip = Prefs.getBool("watch.autoSkip", true)
        player.onTime = { p, d -> saveProgress(p, d, null) }
        player.onEnded = { ended() }
        player.onFail = { failed() }
        scope.launch {
            try {
                saved0 = Library.loadHistory().firstOrNull { it.animeId == animeId }
                saved = Library.load(Collection.LIST).any { it.animeId == animeId }
                loved = Library.load(Collection.FAVORITES).any { it.animeId == animeId }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        scope.launch {
            try {
                detail = AniList.detail(animeId)
                // the intro / outro backup needs the MAL id, which only the detail has
                if (watch != null) fetchSkip(gen.get(), ep)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        loadEpisodes()
    }

    fun savePrefs() {
        Prefs.putBool("watch.autoplay", player.autoplay)
        Prefs.putBool("watch.autoNext", player.autoNext)
        Prefs.putBool("watch.autoSkip", player.autoSkip)
    }

    /* --------------------------------- episodes --------------------------------- */

    fun loadEpisodes(force: Boolean = false) {
        eps = null
        epsError = null
        scope.launch {
            try {
                val e = Streams.episodes(animeId, force) { more ->
                    // slower providers (or a fresh copy) arrive after the first answer
                    if (eps != null) eps = more
                }
                // Fall back to a sensible audio and episode when the requested ones don't exist.
                if (isDub && e.dub.isEmpty()) isDub = false
                if (!isDub && e.sub.isEmpty() && e.dub.isNotEmpty()) isDub = true
                val l = if (isDub) e.dub else e.sub
                if (l.isNotEmpty() && l.none { it.number == ep }) ep = l.first().number
                eps = e
                loadWatch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                epsError = friendlyError(e)
            }
        }
    }

    /* ---------------------------------- streams ---------------------------------- */

    fun loadWatch() {
        val e = current ?: return
        val g = gen.incrementAndGet()
        watch = null
        watchError = null
        backup = null
        scope.launch {
            try {
                val w = Streams.watch(animeId, isDub, e.number, e.providers.toList()) { more ->
                    // extra servers that answered later; the one that is playing stays selected
                    if (g == gen.get()) watch = more
                }
                if (g != gen.get()) return@launch
                if (w.streams.isEmpty()) throw ApiException("No stream is available for this episode right now.")
                watch = w
                openStream(resume = true)
                fetchSkip(g, e.number)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                if (g == gen.get()) watchError = friendlyError(ex)
            }
        }
    }

    private fun fetchSkip(g: Int, number: Int) {
        val mal = detail?.idMal ?: return
        scope.launch {
            val b = Streams.skip(mal, number)
            if (g == gen.get()) {
                backup = b
                applySkip()
            }
        }
    }

    private fun skipData(): SkipData {
        val s = stream
        val w = watch
        if (s != null && (s.intro != null || s.outro != null)) return SkipData(s.intro, s.outro, null)
        if (w != null && (w.intro != null || w.outro != null)) return SkipData(w.intro, w.outro, null)
        return SkipData(backup?.intro, backup?.outro, backup?.length)
    }

    private fun applySkip() {
        val k = skipData()
        player.intro = k.intro
        player.outro = k.outro
        player.lengthHint = k.length
    }

    fun openStream(resume: Boolean) {
        val s = stream ?: return
        serverName = s.name // the playing server stays selected even when more servers arrive
        val k = skipData()
        var start = 0.0
        if (resume) {
            val h = saved0
            if (h != null && h.episode == ep && h.dub == isDub && h.duration > 0 && h.position / h.duration < 0.92) start = h.position
        } else {
            start = player.position
        }
        player.open(s.src, s.hls, s.headers, if (s.soft) s.subtitles else emptyList(), start, k.intro, k.outro, k.length)
    }

    private fun failed() {
        // Try the next server automatically when this one can't play.
        val all = watch?.streams ?: emptyList()
        val at = all.indexOfFirst { it.id == stream?.id }
        if (at >= 0 && at < all.size - 1) {
            serverName = all[at + 1].name
            openStream(resume = false)
        } else {
            watchError = "This episode could not be played on any server. Try another server or episode."
        }
    }

    /* --------------------------------- progress --------------------------------- */

    fun saveProgress(position: Double, duration: Double, episode: Int?) {
        val now = System.currentTimeMillis()
        // the player reports often: save about every 5 seconds, always when the episode changes
        if (episode == null && now - lastSave < 5_000) return
        if (episode == null && duration <= 0) return
        lastSave = now
        val d = detail?.anime
        val item = HistoryItem(
            animeId = animeId,
            episode = episode ?: ep,
            dub = isDub,
            position = if (episode != null) 0.0 else position,
            duration = if (episode != null) 0.0 else duration,
            title = name,
            image = d?.thumb,
            totalEpisodes = d?.episodes ?: list.lastOrNull()?.number,
            updatedAt = now,
        )
        saved0 = item
        App.scope.launch { Library.saveProgress(item) }
    }

    private fun ended() {
        val n = next
        if (n != null) {
            // Watched to the end: Continue Watching should point at the next episode.
            saveProgress(0.0, 0.0, n.number)
            if (player.autoNext) select(n.number)
        } else {
            saveProgress(1.0, 1.0, null)
        }
    }

    fun flush() {
        saveProgress(player.position, player.duration, null)
        lastSave = 0
    }

    fun select(number: Int) {
        if (number == ep) return
        flush()
        ep = number
        loadWatch()
    }

    fun selectAudio(dub: Boolean) {
        isDub = dub
        val l = list
        if (l.isNotEmpty() && l.none { it.number == ep }) ep = l.first().number
        flush()
        loadWatch()
    }

    fun toggle(c: Collection) {
        scope.launch {
            try {
                val now = Library.toggle(c, animeId, name, detail?.anime?.thumb)
                if (c == Collection.FAVORITES) loved = now else saved = now
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun WatchScreen(id: Int, episode: Int, dub: Boolean, onBack: () -> Unit) {
    val player = rememberPlayerState()
    val scope = rememberCoroutineScope()
    val m = remember(id) { WatchModel(id, episode, dub, player, scope) }
    var fullscreen by remember { mutableStateOf(false) }
    var serverSheet by remember { mutableStateOf(false) }

    LaunchedEffect(m) { m.start() }
    DisposableEffect(m) {
        onDispose {
            m.flush()
            m.savePrefs()
            player.onTime = null
            player.onEnded = null
            player.onFail = null
        }
    }

    FullscreenEffect(fullscreen)
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = serverSheet) { serverSheet = false }

    val ep = m.current
    val title = if (ep != null) "${m.name} · Episode ${ep.number}" else m.name

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                // header: back + name (a normal bar above the player, nothing floats over the video)
                Row(
                    Modifier.fillMaxWidth().background(Color.Black).padding(start = 10.dp, end = 14.dp, top = statusBarHeight() + 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlatIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onBack, size = 40.dp, iconSize = 21.dp, description = "Back")
                    Spacer(Modifier.width(12.dp))
                    Text(m.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                }
            }
            VideoPlayer(
                state = player,
                title = title,
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                overlay = {
                    val err = m.epsError ?: m.watchError
                    if (err != null || m.watch == null) {
                        PlayerStatus(
                            error = err,
                            loadingEpisodes = m.eps == null,
                            poster = m.detail?.anime?.banner ?: m.detail?.anime?.poster,
                            onRetry = { if (m.epsError != null) m.loadEpisodes(force = true) else m.loadWatch() },
                        )
                    }
                },
            )
            if (!fullscreen) {
                WatchBody(m, Modifier.weight(1f), onServers = { serverSheet = true })
            }
        }

        if (serverSheet && !fullscreen) ServerSheet(m, onClose = { serverSheet = false })
    }
}

/** Covers the player while episodes / the stream are loading, or shows why it could not load. */
@Composable
private fun BoxScope.PlayerStatus(error: String?, loadingEpisodes: Boolean, poster: String?, onRetry: () -> Unit) {
    Box(Modifier.matchParentSize().background(C.k(0.88f)).blockTouches()) {
        if (poster != null) Img(poster, Modifier.fillMaxSize().alpha(0.25f), ContentScale.Crop)
        Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(30.dp), tint = C.w(0.85f))
                Spacer(Modifier.height(8.dp))
                Text("Couldn't load this episode", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(error, fontSize = 12.sp, color = C.w(0.7f), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                FlatButton("Try again", icon = Icons.Rounded.Refresh, kind = Kind.White, height = 36.dp, fontSize = 12, onClick = onRetry)
            } else {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                Spacer(Modifier.height(10.dp))
                Text(if (loadingEpisodes) "Loading episodes…" else "Finding the best stream…", fontSize = 12.sp, color = C.w(0.7f))
            }
        }
    }
}

@Composable
private fun WatchBody(m: WatchModel, modifier: Modifier, onServers: () -> Unit) {
    val ep = m.current
    val e = m.eps
    val a = m.detail?.anime
    val streams = m.watch?.streams ?: emptyList()
    val hasDub = e?.dub?.isNotEmpty() ?: false
    val hasSub = e?.sub?.isNotEmpty() ?: false
    val dubSet = remember(e) { e?.dub?.map { it.number }?.toSet() ?: emptySet() }
    val list = if (m.reverse) m.list.asReversed() else m.list
    val cols = ((screenWidth() - 32.dp + 8.dp) / 60.dp).toInt().coerceAtLeast(1)
    val inter = remember { MutableInteractionSource() }

    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp + navBarHeight()),
    ) {
        item(key = "title") {
            Column {
                val extra = if (ep != null && ep.title.isNotEmpty() && ep.title != "Episode ${ep.number}") " · ${ep.title}" else ""
                Text(if (ep == null) m.name else "Episode ${ep.number}$extra", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(m.name, fontSize = 13.sp, color = C.w(0.55f))
                Spacer(Modifier.height(14.dp))
            }
        }
        item(key = "controls") {
            Column {
                // audio + server + prev/next
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatSwitch(
                        value = m.isDub,
                        options = listOf(false to "Sub", true to "Dub"),
                        onChange = { v ->
                            if (v != m.isDub) {
                                if ((v && !hasDub) || (!v && !hasSub)) Toasts.show(if (v) "No English dub for this title yet." else "No subbed version found.")
                                else m.selectAudio(v)
                            }
                        },
                        width = 92.dp,
                        height = 36.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    FlatButton(
                        label = m.stream?.name ?: "Server",
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Dns,
                        height = 36.dp,
                        fontSize = 13,
                        enabled = streams.size >= 2,
                        onClick = onServers,
                    )
                    Spacer(Modifier.width(10.dp))
                    val p = m.prev
                    FlatIconButton(Icons.Rounded.SkipPrevious, { if (p != null) m.select(p.number) }, Modifier.alpha(if (p == null) 0.4f else 1f), size = 36.dp, iconSize = 20.dp, description = "Previous episode")
                    Spacer(Modifier.width(8.dp))
                    val n = m.next
                    FlatIconButton(Icons.Rounded.SkipNext, { if (n != null) m.select(n.number) }, Modifier.alpha(if (n == null) 0.4f else 1f), size = 36.dp, iconSize = 20.dp, description = "Next episode")
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatButton(
                        label = if (m.loved) "Favorited" else "Favorite",
                        modifier = Modifier.weight(1f),
                        icon = if (m.loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        height = 42.dp, radius = 12.dp, fontSize = 13,
                        padding = PaddingValues(horizontal = 8.dp),
                        onClick = { m.toggle(Collection.FAVORITES) },
                    )
                    Spacer(Modifier.width(10.dp))
                    FlatButton(
                        label = if (m.saved) "In watchlist" else "Watchlist",
                        modifier = Modifier.weight(1f),
                        icon = if (m.saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        height = 42.dp, radius = 12.dp, fontSize = 13,
                        padding = PaddingValues(horizontal = 8.dp),
                        onClick = { m.toggle(Collection.LIST) },
                    )
                    Spacer(Modifier.width(10.dp))
                    FlatIconButton(Icons.Outlined.Info, { Nav.openInfo(m.animeId) }, size = 42.dp, radius = 12.dp, description = "Details")
                }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Episodes", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    FlatIconButton(Icons.Rounded.SwapVert, { m.reverse = !m.reverse }, size = 34.dp, iconSize = 18.dp, description = "Reverse order")
                    Spacer(Modifier.width(8.dp))
                    FlatIconButton(if (m.grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, { m.grid = !m.grid }, size = 34.dp, iconSize = 18.dp, description = if (m.grid) "List view" else "Grid view")
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        val err = m.epsError
        if (err != null) {
            item(key = "eps-error") { ErrorBox(err, onRetry = { m.loadEpisodes(force = true) }) }
        } else if (e == null) {
            items(2, key = { "skel-$it" }) {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(cols.coerceAtMost(6)) { Skel(Modifier.size(52.dp, 44.dp), radius = 12.dp) }
                }
            }
        } else if (list.isEmpty()) {
            item(key = "eps-empty") { Text("No episodes are available yet.", color = C.w(0.6f), fontSize = 14.sp) }
        } else if (m.grid) {
            items(list.chunked(cols), key = { "row-${it.first().number}-${m.reverse}" }) { row ->
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { x -> EpisodeCell(x, selected = x.number == m.ep) { m.select(x.number) } }
                }
            }
        } else {
            items(list, key = { "ep-${it.number}" }) { x ->
                EpisodeRow(x, selected = x.number == m.ep, hasDub = dubSet.contains(x.number)) { m.select(x.number) }
            }
        }

        if (a != null) {
            item(key = "synopsis") {
                val desc = ep?.description?.takeIf { it.isNotBlank() }
                Column {
                    Spacer(Modifier.height(22.dp))
                    Panel {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (desc != null && m.synopsis) "Episode synopsis" else "Synopsis", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                if (desc != null) {
                                    Text(
                                        if (m.synopsis) "Show anime" else "Show episode",
                                        Modifier.clickable(interactionSource = inter, indication = null) { m.synopsis = !m.synopsis },
                                        fontSize = 12.sp, color = C.w(0.6f), textDecoration = TextDecoration.Underline,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stripHtml(if (m.synopsis && desc != null) desc else (a.description ?: "")),
                                fontSize = 13.5.sp, lineHeight = 20.sp, color = C.w(0.7f), maxLines = 8, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        val rel = m.detail?.relations ?: emptyList()
        if (rel.isNotEmpty()) {
            item(key = "related") {
                Column {
                    Spacer(Modifier.height(22.dp))
                    Text("Seasons & Related", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rel, key = { it.media.id }) { r ->
                            Column(Modifier.width(104.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Nav.openWatch(r.media.id) }) {
                                Poster(r.media.thumb)
                                Spacer(Modifier.height(6.dp))
                                Text(r.media.name, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCell(x: WatchEpisode, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) Color.White else if (x.filler) Color(0x66FB923C) else C.w(0.15f)
    Box(
        Modifier.size(52.dp, 44.dp).clip(shape).background(if (selected) Color.White else C.w(0.06f), shape).border(1.dp, borderColor, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("${x.number}", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = if (selected) Color.Black else Color.White, maxLines = 1)
    }
}

@Composable
private fun EpisodeRow(x: WatchEpisode, selected: Boolean, hasDub: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(if (selected) C.w(0.12f) else C.w(0.04f), shape)
            .border(1.dp, if (selected) C.w(0.4f) else C.w(0.1f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(C.w(0.06f))) {
            Img(x.image, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${x.number}. ${x.title}", fontSize = 13.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ClosedCaption, null, Modifier.size(15.dp), tint = C.w(0.6f))
                if (hasDub) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Mic, null, Modifier.size(15.dp), tint = C.w(0.6f))
                }
                if (x.filler) {
                    Spacer(Modifier.width(8.dp))
                    Text("Filler", fontSize = 11.sp, color = C.orange, fontWeight = FontWeight.Bold)
                }
                val dur = x.duration
                if (dur != null) {
                    Spacer(Modifier.width(8.dp))
                    Text("${(dur / 60.0).roundToInt()}m", fontSize = 11.sp, color = C.w(0.5f))
                }
            }
        }
    }
}

/** The server picker: a flat sheet that slides over the bottom of the page. */
@Composable
private fun ServerSheet(m: WatchModel, onClose: () -> Unit) {
    val streams = m.watch?.streams ?: emptyList()
    val current = m.stream?.name
    Box(Modifier.fillMaxSize().background(C.k(0.6f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(C.surface).border(1.dp, C.line, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .blockTouches().padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 18.dp + navBarHeight()),
        ) {
            Text("Server", Modifier.padding(start = 4.dp, bottom = 10.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            streams.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onClose()
                        if (s.name != current) {
                            m.serverName = s.name
                            m.openStream(resume = false)
                        }
                    }.padding(horizontal = 6.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (s.name == current) FontWeight.ExtraBold else FontWeight.SemiBold, color = Color.White)
                    Text(
                        if (s.soft) "Soft subs" else "Hard subs",
                        Modifier.clip(RoundedCornerShape(999.dp)).background(C.w(0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp, color = C.w(0.7f),
                    )
                    if (s.name == current) {
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}
