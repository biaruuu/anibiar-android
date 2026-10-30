package io.anibiar.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anibiar.app.core.friendlyError
import io.anibiar.app.data.AniList
import io.anibiar.app.data.Anime
import io.anibiar.app.data.Artwork
import io.anibiar.app.data.Collection
import io.anibiar.app.data.Detail
import io.anibiar.app.data.HistoryItem
import io.anibiar.app.data.Library
import io.anibiar.app.data.Tmdb
import io.anibiar.app.data.formatName
import io.anibiar.app.data.statusName
import io.anibiar.app.data.stripHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun InfoScreen(id: Int, onBack: () -> Unit) {
    val top = statusBarHeight()
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<Detail?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var retry by remember(id) { mutableIntStateOf(0) }
    var loved by remember(id) { mutableStateOf(Library.has(Collection.FAVORITES, id)) }
    var saved by remember(id) { mutableStateOf(Library.has(Collection.LIST, id)) }
    var resume by remember(id) { mutableStateOf<HistoryItem?>(null) }

    LaunchedEffect(id, retry) {
        error = null
        try {
            val d = AniList.detail(id)
            if (d == null) error = "Anime not found." else detail = d
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }

    // the library flags and the resume point (refreshed when a watch screen closes)
    val stackSize = Nav.stack.size
    LaunchedEffect(id, stackSize) {
        resume = Library.localHistory().firstOrNull { it.animeId == id && !it.finished }
        saved = Library.has(Collection.LIST, id)
        loved = Library.has(Collection.FAVORITES, id)
        try {
            saved = Library.load(Collection.LIST).any { it.animeId == id }
            loved = Library.load(Collection.FAVORITES).any { it.animeId == id }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        val d = detail
        if (d == null) {
            val err = error
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (err != null) Box(Modifier.padding(24.dp)) { ErrorBox(err, onRetry = { retry++ }) }
                else CircularProgressIndicator(Modifier.size(28.dp), color = C.w(0.55f), strokeWidth = 2.5.dp)
            }
        } else {
            InfoContent(
                d, top, loved, saved, resume,
                onToggle = { c ->
                    val a = d.anime
                    scope.launch {
                        val now = Library.toggle(c, a.id, a.name, a.thumb)
                        if (c == Collection.FAVORITES) loved = now else saved = now
                        Toasts.show(
                            if (c == Collection.FAVORITES) (if (now) "Added to favorites" else "Removed from favorites")
                            else (if (now) "Added to your watchlist" else "Removed from your watchlist"),
                        )
                    }
                },
            )
        }
        FlatIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onBack, Modifier.padding(top = top + 8.dp, start = 12.dp), size = 42.dp, description = "Back")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoContent(d: Detail, top: androidx.compose.ui.unit.Dp, loved: Boolean, saved: Boolean, resume: HistoryItem?, onToggle: (Collection) -> Unit) {
    val a = d.anime
    val ctx = LocalContext.current
    var expanded by remember(a.id) { mutableStateOf(false) }
    val desc = stripHtml(a.description ?: "")
    val status = statusName(a.status)
    val rows = buildList {
        add("Format" to formatName(a.format))
        add("Status" to status)
        add("Episodes" to (a.episodes?.toString() ?: (if (a.nextEpisode != null) "${a.nextEpisode - 1}+" else "?")))
        if (d.duration != null) add("Duration" to "${d.duration} min")
        if (d.season != null && a.seasonYear != null) add("Season" to (d.season.substring(0, 1) + d.season.substring(1).lowercase() + " ${a.seasonYear}"))
        if (!d.start.empty) add("Aired" to d.start.toString())
        if (!d.end.empty) add("Ended" to d.end.toString())
        if (d.studio != null) add("Studio" to d.studio)
        if (a.averageScore != null) add("Score" to "${a.averageScore}%")
    }
    val posterW = (screenWidth() * 0.3f).coerceIn(96.dp, 140.dp)
    val bannerH = 230.dp + top

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth()) {
            // banner under one flat dark layer
            Box(Modifier.fillMaxWidth().height(bannerH).background(C.surface)) {
                Img(a.banner ?: a.poster, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(C.k(0.5f)))
            }
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(166.dp + top))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Poster(a.poster, Modifier.width(posterW), radius = 14.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                            Text(a.name, fontSize = 21.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            val native = a.title.native
                            if (native != null) Text(native, Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = C.w(0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chips = listOfNotNull(formatName(a.format), status, a.seasonYear?.toString(), a.averageScore?.let { "★ $it" })
                        chips.forEach { c ->
                            Box(Modifier.clip(RoundedCornerShape(99.dp)).background(C.raised).border(1.dp, C.line, RoundedCornerShape(99.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                                Text(c, fontSize = 12.sp, color = C.w(0.9f), maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlatButton(
                            if (resume != null) "Continue EP ${resume.episode}" else "Watch Now",
                            Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, kind = Kind.White, height = 46.dp, radius = 12.dp,
                        ) { Nav.openWatch(a.id, resume?.episode ?: 1, resume?.dub ?: false) }
                        Spacer(Modifier.width(10.dp))
                        FlatIconButton(if (loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, { onToggle(Collection.FAVORITES) }, size = 46.dp, radius = 12.dp, description = if (loved) "Remove from favorites" else "Add to favorites")
                        Spacer(Modifier.width(10.dp))
                        FlatIconButton(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, { onToggle(Collection.LIST) }, size = 46.dp, radius = 12.dp, description = if (saved) "Remove from watchlist" else "Add to watchlist")
                    }
                    if (a.genres.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            a.genres.forEach { Chip2(it, glass = true) }
                        }
                    }
                    if (desc.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Text("Synopsis", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            desc,
                            Modifier.fillMaxWidth().animateContentSize().tap { expanded = !expanded },
                            fontSize = 14.5.sp, lineHeight = 22.sp, color = C.w(0.75f),
                            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                        )
                        if (desc.length > 260) {
                            Text(if (expanded) "Show less" else "Show more", Modifier.padding(top = 6.dp).tap { expanded = !expanded }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Details", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Panel(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            rows.forEachIndexed { i, (k, v) ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.w(0.08f)))
                                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
                                    Text(k, Modifier.width(92.dp), fontSize = 13.5.sp, color = C.w(0.5f))
                                    Text(v, Modifier.weight(1f), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    }
                    if (d.trailerId != null && d.trailerSite == "youtube") {
                        Spacer(Modifier.height(14.dp))
                        FlatButton("Watch trailer", Modifier.fillMaxWidth(), icon = Icons.Rounded.SmartDisplay, height = 46.dp, radius = 12.dp) {
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${d.trailerId}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {
                                Toasts.show("Couldn't open the trailer.")
                            }
                        }
                    }
                    Gallery(a)
                    if (d.relations.isNotEmpty()) {
                        Spacer(Modifier.height(26.dp))
                        SectionTitle("Seasons & Related")
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (d.relations.isNotEmpty()) Rail(d.relations.map { it.media to it.type })
                if (d.recommendations.isNotEmpty()) {
                    SectionTitle("You might also like", Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp))
                    Rail(d.recommendations.map { it to null })
                }
            }
        }
        Spacer(Modifier.height(40.dp + navBarHeight()))
    }
}

/* ------------------------------------------- gallery ------------------------------------------- */

/** "Artwork & Posters": the banner and poster from AniList plus backgrounds, posters and logos from TMDB. */
@Composable
private fun Gallery(a: Anime) {
    val extra by produceState<List<Artwork>>(emptyList(), a.id) {
        value = try { Tmdb.artwork(a.title.english ?: a.title.romaji ?: a.name, a.format == "MOVIE") } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    var filter by remember(a.id) { mutableStateOf("All") }
    var open by remember(a.id) { mutableStateOf(false) }
    val images = buildList {
        if (a.banner != null) add(Artwork("Banners", a.banner))
        if (a.poster != null) add(Artwork("Posters", a.poster!!))
        addAll(extra)
    }
    if (images.size < 2) return
    val order = listOf("Backgrounds", "Banners", "Posters", "Logos")
    val kinds = order.filter { k -> images.any { it.kind == k } }
    val active = if (filter == "All" || filter in kinds) filter else "All"
    val all = if (active == "All") images else images.filter { it.kind == active }
    val list = all.take(80)
    val width = screenWidth() - 32.dp

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(26.dp))
        SectionTitle("Artwork & Posters")
        Spacer(Modifier.height(4.dp))
        Text("${all.size} images", fontSize = 12.5.sp, color = C.w(0.55f))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("All") + kinds).forEach { k ->
                val on = active == k
                Flat(kind = if (on) Kind.White else Kind.Btn, onClick = { filter = k }) {
                    Text(k, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (on) Color.Black else Color.White, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { i ->
                            Box(Modifier.weight(1f).aspectRatio(1.45f).clip(RoundedCornerShape(12.dp)).background(C.w(0.05f))) {
                                Img(i.url, Modifier.fillMaxSize(), if (i.kind == "Logos") ContentScale.Fit else ContentScale.Crop)
                            }
                        }
                        if (pair.size < 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else if (list.isNotEmpty()) {
            Box(Modifier.width(width).height(width * 0.56f).clip(RoundedCornerShape(12.dp)).background(C.w(0.05f))) {
                Img(list.first().url, Modifier.fillMaxSize(), if (list.first().kind == "Logos") ContentScale.Fit else ContentScale.Crop)
            }
        }
        if (list.size > 1) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (open) "Show less" else "View all ${all.size} images",
                    Modifier.tap { open = !open }.padding(vertical = 10.dp, horizontal = 12.dp),
                    fontSize = 13.sp, color = C.w(0.65f),
                )
            }
        }
    }
}

/* -------------------------------------------- rails -------------------------------------------- */

@Composable
private fun Rail(items: List<Pair<Anime, String?>>) {
    LazyRow(
        Modifier.fillMaxWidth().height(214.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items, key = { i, p -> "${p.first.id}-$i" }) { _, (a, tag) ->
            Column(Modifier.width(104.dp).tap { Nav.openInfo(a.id) }) {
                Poster(a.thumb) {
                    if (!tag.isNullOrEmpty()) {
                        Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                            Chip2(tag.substring(0, 1) + tag.substring(1).lowercase(), glass = true)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(a.name, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
