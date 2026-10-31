package io.anibiar.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.anibiar.app.data.HistoryItem
import io.anibiar.app.data.LatestEpisode
import io.anibiar.app.data.clock
import io.anibiar.app.data.timeAgo
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------------------------
 * Shared building blocks. Every surface is flat Onyx: a solid fill and a hairline border.
 * ------------------------------------------------------------------------------------------- */

enum class Kind { Btn, White, Accent, Danger, Panel, Nav, Track, Bar }

private fun fillOf(k: Kind): Color = when (k) {
    Kind.Btn -> C.raised
    Kind.White -> Color(0xFFF2F3F5)
    Kind.Accent -> C.accent
    Kind.Danger -> C.red
    Kind.Panel -> C.surface
    Kind.Nav -> Color(0xF2111113)
    Kind.Track -> Color(0xFF0E0E10)
    Kind.Bar -> Color(0x8C000000)
}

private fun borderOf(k: Kind): Color = when (k) {
    Kind.White -> Color(0xFFF2F3F5)
    Kind.Accent -> C.accent
    Kind.Danger -> C.red
    Kind.Bar -> Color(0x1FFFFFFF)
    else -> C.line
}

/** Text / icon colour that reads on a surface kind. */
fun inkOf(k: Kind): Color = if (k == Kind.White) Color.Black else Color.White

/** A flat surface with rounded corners, a hairline border and a gentle press animation. */
@Composable
fun Flat(
    modifier: Modifier = Modifier,
    kind: Kind = Kind.Btn,
    radius: Dp = 999.dp,
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) 0.96f else 1f, tween(110), label = "press")
    var m = modifier.scale(scale).clip(shape).background(fillOf(kind), shape).border(BorderStroke(1.dp, borderOf(kind)), shape)
    if (onClick != null) m = m.clickable(interactionSource = source, indication = null, onClick = onClick)
    Box(m, contentAlignment = contentAlignment, content = content)
}

/** Rounded button with an optional icon and label (the website's Button). */
@Composable
fun FlatButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: Kind = Kind.Btn,
    height: Dp = 44.dp,
    radius: Dp = 999.dp,
    fontSize: Int = 14,
    busy: Boolean = false,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp),
    onClick: () -> Unit,
) {
    val ink = inkOf(kind)
    val on = enabled && !busy
    Flat(
        modifier = modifier.height(height).let { if (enabled || busy) it else it.scale(1f) },
        kind = kind,
        radius = radius,
        onClick = if (on) onClick else null,
    ) {
        Row(
            Modifier.padding(padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = ink, strokeWidth = 2.dp)
            else if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = ink)
            if ((busy || icon != null) && label.isNotEmpty()) Spacer(Modifier.width(8.dp))
            if (label.isNotEmpty()) Text(label, color = if (enabled) ink else ink.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round icon button (close, back, menu, share...). */
@Composable
fun FlatIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    kind: Kind = Kind.Btn,
    radius: Dp? = null,
    description: String? = null,
) {
    Flat(modifier.size(size), kind = kind, radius = radius ?: (size / 2), onClick = onClick) {
        Icon(icon, description, Modifier.size(iconSize), tint = inkOf(kind))
    }
}

/** Segmented switch: dark track, white pill that slides to the chosen option. Sized to fit small phones. */
@Composable
fun <T> FlatSwitch(
    value: T,
    options: List<Pair<T, String>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 92.dp,
    height: Dp = 30.dp,
    fontSize: Int = 12,
) {
    val n = options.size.coerceAtLeast(1)
    val index = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    val offset by animateFloatAsState(index.toFloat(), tween(240), label = "switch")
    Box(modifier.width(width).height(height).clip(RoundedCornerShape(999.dp)).background(fillOf(Kind.Track)).border(1.dp, C.line, RoundedCornerShape(999.dp)).padding(3.dp)) {
        val seg = (width - 6.dp) / n
        Box(Modifier.padding(start = seg * offset).width(seg).fillMaxSize().clip(RoundedCornerShape(999.dp)).background(Color(0xFFF2F3F5)))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (v, label) ->
                Box(
                    Modifier.weight(1f).fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(v) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = if (v == value) Color.Black else C.w(0.7f), maxLines = 1)
                }
            }
        }
    }
}

/** Small flat pill used on posters (episode, format, year). */
@Composable
fun Chip2(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, glass: Boolean = false) {
    val shape = RoundedCornerShape(if (glass) 999.dp else 6.dp)
    Row(
        modifier.clip(shape).background(if (glass) C.raised else C.w(0.07f), shape).let { if (glass) it.border(1.dp, C.line, shape) else it }
            .padding(horizontal = 6.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(10.5.dp), tint = C.w(0.75f))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, color = C.w(0.78f), maxLines = 1)
    }
}

/** A row of chips that always stays on one line: it shrinks to fit narrow cards instead of wrapping. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, gap: Dp = 4.dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val g = gap.roundToPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        val total = placeables.sumOf { it.width } + g * (placeables.size - 1).coerceAtLeast(0)
        val h = placeables.maxOfOrNull { it.height } ?: 0
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else total
        val s = if (total > maxW && total > 0) maxW.toFloat() / total else 1f
        layout(maxW, (h * s).roundToInt()) {
            var x = 0
            for (p in placeables) {
                p.placeWithLayer((x * s).roundToInt(), 0) {
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                x += p.width + g
            }
        }
    }
}

/** Placeholder block while data loads. */
@Composable
fun Skel(modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(C.w(0.05f)))
}

/** Section heading with an optional trailing widget. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
    }
}

/** Bordered panel like the website's rounded boxes. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(14.dp), radius: Dp = 18.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.fillMaxWidth().clip(shape).background(C.surface, shape).border(1.dp, C.line, shape).padding(padding)) { content() }
}

/** Error box with a Try again button. */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, padding = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(30.dp), tint = Color(0xFFF87171))
            Spacer(Modifier.height(10.dp))
            Text(message, color = C.w(0.8f), fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            FlatButton("Try again", height = 38.dp, fontSize = 13, onClick = onRetry)
        }
    }
}

/** The Vidstack media icons (MIT) the website's player uses, by file name without extension, e.g. "play" or "seek_forward_10". */
@Composable
fun VIcon(name: String, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = Color.White) {
    val ctx = LocalContext.current
    val id = remember(name) { ctx.resources.getIdentifier("vi_$name", "drawable", ctx.packageName) }
    if (id != 0) Icon(painterResource(id), null, modifier.size(size), tint = tint)
}

/* ------------------------------------------------ posters and cards ------------------------------------------------ */

/** Image with a rounded frame and the faint ring the website uses. */
@Composable
fun Poster(
    url: String?,
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    aspect: Float = 2f / 3f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.aspectRatio(aspect).clip(shape).background(C.w(0.05f), shape)) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(120).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        content()
        Box(Modifier.fillMaxSize().border(1.dp, C.w(0.12f), shape))
    }
}

/** An image that fills its box, no frame (banners, logos). */
@Composable
fun Img(url: String?, modifier: Modifier = Modifier, fit: ContentScale = ContentScale.Crop) {
    if (url.isNullOrEmpty()) return
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(150).build(),
        contentDescription = null,
        contentScale = fit,
        modifier = modifier,
    )
}

/** A small translucent chip with an optional icon, like the badges under Miruro's cards. They wrap onto a second line when needed. */
@Composable
fun MiniChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, vicon: String? = null) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier.clip(shape).background(C.w(0.08f), shape).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(11.dp), tint = C.w(0.7f))
            Spacer(Modifier.width(3.dp))
        } else if (vicon != null) {
            VIcon(vicon, size = 12.dp, tint = C.w(0.7f))
            Spacer(Modifier.width(2.dp))
        }
        Text(text, fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = C.w(0.78f), maxLines = 1)
    }
}

/** The glass bubble used on the hero (episodes, year, genres): translucent white with a light edge. */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier, fontSize: Int = 13) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        modifier.clip(shape).background(C.w(0.10f), shape).border(1.dp, C.w(0.18f), shape).padding(horizontal = 13.dp, vertical = 6.dp),
        fontSize = fontSize.sp, lineHeight = (fontSize + 3).sp, color = C.w(0.92f), maxLines = 1,
    )
}

/** A card in the "Latest Episodes" grid: poster with "EP 5 · 5h ago" on the picture, badges underneath that wrap. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EpisodeCard(item: LatestEpisode, onClick: () -> Unit, modifier: Modifier = Modifier, dubCount: Int? = null) {
    val a = item.media
    val shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), androidx.compose.ui.geometry.Offset(0f, 1f), 4f)
    Column(modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Poster(a.thumb) {
            // only a soft shade at the very bottom so the text reads on bright posters; no solid bar
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(46.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("EP ${item.episode}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, style = androidx.compose.ui.text.TextStyle(shadow = shadow))
                Text(timeAgo(item.airingAt), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, style = androidx.compose.ui.text.TextStyle(shadow = shadow))
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MiniChip("${item.episode}", vicon = "closed_captions")
            if (dubCount != null && dubCount > 0) MiniChip("$dubCount", icon = Icons.Rounded.Mic)
            MiniChip(a.formatLabel)
            if (a.seasonYear != null) MiniChip("${a.seasonYear}")
        }
        Spacer(Modifier.height(6.dp))
        Text(a.name, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Poster + title (search results, watchlist, favorites). */
@Composable
fun PosterTile(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chips: (@Composable () -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Column(modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Poster(image) {
            if (onRemove != null) {
                FlatIconButton(Icons.Rounded.Close, onRemove, Modifier.align(Alignment.TopEnd).padding(6.dp), size = 28.dp, iconSize = 16.dp, kind = Kind.Bar)
            }
        }
        if (chips != null) {
            Spacer(Modifier.height(8.dp))
            ChipRow { chips() }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A Continue Watching card: 16:9 picture, time left, red progress bar. */
@Composable
fun HistoryCard(h: HistoryItem, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val pct = if (h.duration > 0) (h.position / h.duration).coerceIn(0.02, 1.0).toFloat() else 0.02f
    Column(modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Poster(h.image, aspect = 16f / 9f) {
            if (h.duration > 0) {
                Text(
                    "${clock(h.duration - h.position)} left",
                    Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 12.dp).background(C.k(0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(C.w(0.25f))) {
                Box(Modifier.fillMaxWidth(pct).fillMaxSize().background(C.red))
            }
            FlatIconButton(Icons.Rounded.Close, onRemove, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 30.dp, iconSize = 16.dp, kind = Kind.Bar)
        }
        Spacer(Modifier.height(8.dp))
        Text(h.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "Episode ${h.episode}${if (h.totalEpisodes != null) " / ${h.totalEpisodes}" else ""}${if (h.dub) " · Dub" else ""}",
            fontSize = 12.sp, color = C.w(0.6f), maxLines = 1,
        )
    }
}

/**
 * A poster grid that adapts to the screen: 3 columns on phones, more on wider screens. It lays its items out in rows,
 * so it can sit inside a scrolling column without nesting scrollers.
 */
@Composable
fun <T> PosterGrid(items: List<T>, modifier: Modifier = Modifier, sidePadding: Dp = 16.dp, content: @Composable (T) -> Unit) {
    val w = screenWidth()
    val cols = when {
        w < 520.dp -> 3
        w < 780.dp -> 4
        w < 1040.dp -> 5
        else -> 6
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(cols).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                rowItems.forEach { Box(Modifier.weight(1f)) { content(it) } }
                repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Skeleton for a poster grid while it loads. */
@Composable
fun PosterGridSkeleton(count: Int = 12, modifier: Modifier = Modifier) {
    PosterGrid((0 until count).toList(), modifier) {
        Column {
            Skel(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Spacer(Modifier.height(10.dp))
            Skel(Modifier.width(70.dp).height(12.dp), radius = 4.dp)
            Spacer(Modifier.height(6.dp))
            Skel(Modifier.fillMaxWidth().height(10.dp), radius = 4.dp)
        }
    }
}

/** Swallows touches so a full-screen layer doesn't pass them to the one underneath. */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) { detectTapGestures { } }
