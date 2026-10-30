package io.anibiar.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.anibiar.app.R
import io.anibiar.app.core.friendlyError
import io.anibiar.app.data.AniList
import io.anibiar.app.data.Anime
import io.anibiar.app.data.Auth
import io.anibiar.app.data.Collection
import io.anibiar.app.data.HistoryItem
import io.anibiar.app.data.LatestEpisode
import io.anibiar.app.data.Library
import io.anibiar.app.data.Streams
import io.anibiar.app.data.Tmdb
import io.anibiar.app.data.stripHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
private fun Modifier.noRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/* ------------------------------------------------------------------------------------------ */

@Composable
fun HomeScreen(onSearch: () -> Unit) {
    val top = statusBarHeight()
    var trending by remember { mutableStateOf<List<Anime>?>(null) }
    var trendingError by remember { mutableStateOf<String?>(null) }
    var trendingTry by remember { mutableIntStateOf(0) }
    var hero by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var historyTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(trendingTry) {
        trendingError = null
        try {
            trending = AniList.trending()
            hero = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trendingError = friendlyError(e)
            trending = emptyList()
        }
    }

    // reload the history when the account changes, after a watch screen closes, or after a remove
    val userId = Auth.user?.id
    val stackSize = Nav.stack.size
    LaunchedEffect(userId, stackSize, historyTick) {
        history = Library.localHistory()
        history = Library.loadHistory()
    }

    // rotate the hero every 8 seconds
    val count = trending?.size ?: 0
    LaunchedEffect(count, hero) {
        if (count > 1) {
            kotlinx.coroutines.delay(8000)
            hero = (hero + 1) % count
        }
    }

    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val heroHeight = (screenH * 0.64f).coerceIn(400.dp, 560.dp)

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        Box {
            Hero(
                items = trending,
                error = trendingError,
                index = hero,
                height = heroHeight,
                onSelect = { hero = it },
                onRetry = { trending = null; trendingTry++ },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = top + 6.dp, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(painterResource(R.drawable.logo), "AniBiar TV", Modifier.height(30.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.weight(1f))
                FlatIconButton(Icons.Rounded.Search, onSearch, size = 40.dp, iconSize = 21.dp, description = "Search")
            }
        }
        ContinueWatching(history) { historyTick++ }
        Latest()
        RankedPanel("Top Airing", C.green, top = true)
        RankedPanel("Upcoming", C.orange, top = false)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
            HorizontalDivider(color = C.w(0.12f))
            Spacer(Modifier.height(14.dp))
            Text(
                "AniBiar does not host any video files. Streams are provided by third-party servers.",
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 18.sp, color = C.w(0.45f),
            )
        }
        Spacer(Modifier.height(96.dp + navBarHeight()))
    }
}

/* ------------------------------------------- hero ------------------------------------------- */

@Composable
private fun Hero(items: List<Anime>?, error: String?, index: Int, height: Dp, onSelect: (Int) -> Unit, onRetry: () -> Unit) {
    if (items == null) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = C.w(0.55f), strokeWidth = 2.5.dp)
        }
        return
    }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 110.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error ?: "Couldn't load featured anime.", color = C.w(0.7f), textAlign = TextAlign.Center, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            FlatButton("Try again", height = 40.dp, onClick = onRetry)
        }
        return
    }
    val a = items[index.coerceIn(0, items.size - 1)]
    val desc = stripHtml(a.description ?: "")
    val chips = buildList {
        if (a.episodes != null) add("${a.episodes} Episodes")
        if (a.seasonYear != null) add("${a.seasonYear}")
        addAll(a.genres.take(2))
    }
    val w = screenWidth()
    val narrow = w < 380.dp
    val pad = if (narrow) 14.dp else 18.dp
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).background(C.surface)) {
        // artwork under one flat dark layer so the text always reads
        Crossfade(targetState = a.id, animationSpec = tween(500), label = "hero") { id ->
            val m = items.firstOrNull { it.id == id } ?: a
            Img(m.cover ?: m.banner, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(C.k(0.55f)))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = pad, end = pad, bottom = 18.dp)) {
            TitleLogo(a)
            Spacer(Modifier.height(10.dp))
            ChipRow(gap = 8.dp) {
                chips.forEach { c ->
                    Box(Modifier.clip(RoundedCornerShape(99.dp)).background(C.k(0.45f)).border(1.dp, C.line, RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(c, fontSize = if (narrow) 11.sp else 12.sp, color = C.w(0.9f), maxLines = 1)
                    }
                }
            }
            if (desc.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(desc, fontSize = if (narrow) 13.sp else 14.sp, lineHeight = if (narrow) 19.sp else 20.sp, color = C.w(0.78f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatButton("Watch Now", Modifier.weight(5f), icon = Icons.Rounded.PlayArrow, kind = Kind.White, height = 42.dp, radius = 10.dp, padding = PaddingValues(horizontal = 10.dp)) { Nav.openWatch(a.id, 1, false) }
                Spacer(Modifier.width(8.dp))
                FlatButton("Details", Modifier.weight(4f), icon = Icons.Outlined.Info, height = 42.dp, radius = 10.dp, padding = PaddingValues(horizontal = 10.dp)) { Nav.openInfo(a.id) }
                Spacer(Modifier.width(8.dp))
                AddButton(a)
            }
            if (items.size > 1) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    items.forEachIndexed { i, _ ->
                        Box(
                            Modifier.padding(vertical = 6.dp).height(5.dp).width(if (i == index) 22.dp else 5.dp)
                                .clip(RoundedCornerShape(99.dp)).background(if (i == index) Color.White else C.w(0.35f)).noRipple { onSelect(i) },
                        )
                    }
                }
            }
        }
    }
}

/** The stylised title logo from TMDB (like the website's hero); the plain title until it arrives or when there is none. */
@Composable
private fun TitleLogo(a: Anime) {
    val w = screenWidth()
    val logo by produceState<String?>(null, a.id) {
        value = null
        value = try { Tmdb.logo(a.title.english ?: a.title.romaji ?: a.name, a.format == "MOVIE") } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val url = logo
    if (url == null) {
        Text(
            a.name, fontSize = if (w < 380.dp) 24.sp else 28.sp, lineHeight = if (w < 380.dp) 27.sp else 31.sp,
            fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    } else {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(250).build(),
            contentDescription = a.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.height(if (w < 380.dp) 72.dp else 92.dp).fillMaxWidth(0.8f),
        )
    }
}

@Composable
private fun AddButton(a: Anime) {
    val scope = rememberCoroutineScope()
    var inList by remember(a.id) { mutableStateOf(Library.has(Collection.LIST, a.id)) }
    FlatIconButton(
        if (inList) Icons.Rounded.Check else Icons.Rounded.Add,
        onClick = {
            scope.launch {
                val now = Library.toggle(Collection.LIST, a.id, a.name, a.thumb)
                inList = now
                Toasts.show(if (now) "Added to your watchlist" else "Removed from your watchlist")
            }
        },
        size = 42.dp, radius = 10.dp, description = "Add to list",
    )
}

/* ------------------------------------- continue watching ------------------------------------- */

@Composable
private fun ContinueWatching(items: List<HistoryItem>?, onChanged: () -> Unit) {
    val list = (items ?: emptyList()).filter { !it.finished }.take(12)
    if (list.isEmpty()) return
    val scope = rememberCoroutineScope()
    val cardW = (screenWidth() * 0.74f).coerceAtMost(420.dp)
    Column(Modifier.padding(top = 22.dp)) {
        SectionTitle("Continue Watching", Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(list, key = { _, h -> h.animeId }) { _, h ->
                HistoryCard(
                    h,
                    onClick = { Nav.openWatch(h.animeId, h.episode, h.dub) },
                    onRemove = { scope.launch { Library.removeHistory(h.animeId); onChanged() } },
                    modifier = Modifier.width(cardW),
                )
            }
        }
    }
}

/* ------------------------------------- latest episodes ------------------------------------- */

private val dubCache = HashMap<String, Boolean>()

@Composable
private fun Latest() {
    val maxPages = 5
    var page by remember { mutableIntStateOf(1) }
    var last by remember { mutableIntStateOf(maxPages) }
    var items by remember { mutableStateOf<List<LatestEpisode>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var dub by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(0) }
    var dubTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(page, retry) {
        items = null
        error = null
        try {
            val r = AniList.latestEpisodes(page)
            items = r.items
            last = r.lastPage.coerceIn(1, maxPages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }

    // Dub is checked against the streaming servers, three titles at a time.
    LaunchedEffect(items, dub) {
        val list = items
        if (!dub || list == null) return@LaunchedEffect
        val todo = list.filter { !dubCache.containsKey("${it.media.id}:${it.episode}") }
        if (todo.isEmpty()) return@LaunchedEffect
        pending = todo.size
        val gate = Semaphore(3)
        var left = todo.size
        coroutineScope {
            todo.map { it0 ->
                async {
                    gate.withPermit {
                        val ok = try {
                            Streams.episodes(it0.media.id).dub.any { e -> e.number == it0.episode }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        synchronized(dubCache) { dubCache["${it0.media.id}:${it0.episode}"] = ok }
                        left--
                        pending = left.coerceAtLeast(0)
                        dubTick++
                    }
                }
            }.awaitAll()
        }
        pending = 0
    }

    val tick = dubTick // re-read the dub results whenever one more arrives
    val shown = items?.let { l -> if (dub && tick >= 0) l.filter { dubCache["${it.media.id}:${it.episode}"] == true } else l }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 380.dp
            val controls: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatSwitch(dub, listOf(false to "Sub", true to "Dub"), onChange = { dub = it }, width = 92.dp, height = 30.dp, fontSize = 12)
                    Spacer(Modifier.width(8.dp))
                    Pager(page, last) { page = it }
                }
            }
            if (narrow) {
                Column {
                    SectionTitle("Latest Episodes")
                    Spacer(Modifier.height(10.dp))
                    controls()
                }
            } else {
                SectionTitle("Latest Episodes", trailing = controls)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (dub && items != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pending > 0) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = C.w(0.7f), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (pending > 0) "Checking which of these episodes have an English dub…"
                    else if (!shown.isNullOrEmpty()) "Showing only episodes that have an English dub on the servers."
                    else "None of the latest episodes on this page have a dub yet. Try another page or switch back to Sub.",
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = C.w(0.7f),
                )
            }
        }
        val err = error
        if (err != null) {
            ErrorBox(err, onRetry = { retry++ })
        } else if (shown == null) {
            PosterGridSkeleton(12)
        } else {
            PosterGrid(shown, sidePadding = 32.dp) { it0 ->
                EpisodeCard(it0, onClick = { if (dub) Nav.openWatch(it0.media.id, it0.episode, true) else Nav.openInfo(it0.media.id) })
            }
        }
    }
}

@Composable
private fun Pager(page: Int, last: Int, onPage: (Int) -> Unit) {
    @Composable
    fun Btn(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, f: () -> Unit) {
        Box(Modifier.size(26.dp).then(if (on) Modifier.noRipple(f) else Modifier), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(17.dp), tint = if (on) C.w(0.85f) else C.w(0.3f))
        }
    }
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(99.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(99.dp)).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Btn(Icons.Rounded.ChevronLeft, page > 1) { onPage(page - 1) }
        Text("$page/$last", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        Btn(Icons.Rounded.ChevronRight, page < last) { onPage(page + 1) }
    }
}

/* ---------------------------------- top airing / upcoming ---------------------------------- */

@Composable
private fun RankedPanel(title: String, dot: Color, top: Boolean) {
    val step = 5
    var items by remember { mutableStateOf<List<Anime>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(step) }

    LaunchedEffect(retry) {
        failed = false
        try {
            items = if (top) AniList.topAiring() else AniList.upcoming()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Panel(padding = PaddingValues(12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text(title.uppercase(), fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = Color.White)
                }
                val list = items
                if (failed && list == null) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't load this list.", Modifier.weight(1f), color = C.w(0.6f), fontSize = 14.sp)
                        FlatButton("Try again", height = 34.dp, fontSize = 12) { retry++ }
                    }
                } else if (list == null) {
                    repeat(step) { Skel(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(96.dp)) }
                } else {
                    list.take(shown).forEach { a ->
                        RankedRow(a, dot)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (list.size > step) {
                        Box(
                            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(C.w(0.05f))
                                .noRipple { shown = if (shown >= list.size) step else list.size },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (shown >= list.size) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = C.w(0.8f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankedRow(a: Anime, dot: Color) {
    val eps = if (a.nextEpisode != null) a.nextEpisode - 1 else a.episodes
    Row(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp)).background(C.raised).noRipple { Nav.openInfo(a.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).fillMaxHeight().background(C.w(0.1f))) { Img(a.thumb, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp, end = 8.dp).size(8.dp).clip(RoundedCornerShape(99.dp)).background(dot))
                Text(a.name, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            ChipRow(Modifier.padding(start = 16.dp)) {
                if (a.format != null) Chip2(a.format.replace('_', ' '))
                if (a.seasonYear != null) Chip2("${a.seasonYear}", icon = Icons.Rounded.CalendarToday)
                if (eps != null && eps > 0) Chip2("$eps", icon = Icons.Rounded.ClosedCaption)
                if (a.averageScore != null) Chip2("${a.averageScore}", icon = Icons.Rounded.Star)
            }
        }
    }
}
