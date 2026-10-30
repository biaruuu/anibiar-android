package io.anibiar.app.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.anibiar.app.data.AvatarDeco
import io.anibiar.app.data.Streams
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Profile picture, or a grey disc when there is none. Pass a decoration id to draw the animated decoration around it. */
@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier, decoration: String? = null) {
    val pic: @Composable () -> Unit = { AvatarPicture(url, Modifier.fillMaxSize(), size) }
    if (decoration == null) {
        Box(modifier.size(size).clip(CircleShape)) { pic() }
    } else {
        val deco = findDeco(decoration, rememberDecos())
        DecoratedAvatar(size = size, deco = deco, modifier = modifier, content = pic)
    }
}

/** The picture alone, filling its parent (no ring, no decoration). */
@Composable
fun AvatarPicture(url: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    if (url.isNullOrEmpty()) {
        Box(modifier.background(C.input), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Person, null, Modifier.size(size * 0.55f), tint = C.w(0.5f))
        }
    } else {
        Box(modifier.background(C.input)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(120).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/* ------------------------------------------------ decorations ------------------------------------------------ */

private val sheets = ConcurrentHashMap<String, ImageBitmap>()

@Composable
private fun rememberSheet(url: String): State<ImageBitmap?> {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = sheets[url], url) {
        if (value != null) return@produceState
        try {
            val res = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).build())
            val d = (res as? SuccessResult)?.drawable
            val bmp: Bitmap? = (d as? BitmapDrawable)?.bitmap
            if (bmp != null) {
                val img = bmp.asImageBitmap()
                sheets[url] = img
                value = img
            }
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun rememberSpriteFrame(frames: Int, fps: Double, still: Boolean): Int {
    if (still || frames <= 1) return 0
    val ms = ((frames / fps.coerceAtLeast(1.0)) * 1000).roundToInt().coerceAtLeast(100)
    val t by rememberInfiniteTransition(label = "deco").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart),
        label = "decoFrame",
    )
    return (t * frames).toInt().coerceIn(0, frames - 1)
}

/**
 * The animated decoration. The website uses animated PNGs; here the sprite sheet of the same decoration is played
 * frame by frame (the list comes from the same Supabase table).
 */
@Composable
fun DecoLayer(deco: AvatarDeco, modifier: Modifier = Modifier, still: Boolean = false) {
    val sheet by rememberSheet(deco.sheet)
    val img = sheet ?: return
    val frame = rememberSpriteFrame(deco.frames, deco.fps, still)
    Canvas(modifier) {
        val fs = deco.frameSize
        val cols = deco.cols.coerceAtLeast(1)
        val col = frame % cols
        val row = frame / cols
        drawImage(
            image = img,
            srcOffset = IntOffset(col * fs, row * fs),
            srcSize = IntSize(fs, fs),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * Avatar with a thin black ring and an animated decoration on top, fitted the way Discord does it:
 * the decoration is drawn `scale` times the avatar picture, centred on it.
 */
@Composable
fun DecoratedAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    deco: AvatarDeco? = null,
    still: Boolean = false,
    content: @Composable () -> Unit,
) {
    val pic = size - 8.dp
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black))
        Box(Modifier.fillMaxSize().padding(4.dp).clip(CircleShape)) { content() }
        if (deco != null) {
            // the decoration is larger than the picture, so it may reach outside the box
            DecoLayer(deco, Modifier.requiredSize(pic * deco.scale.toFloat()), still)
        }
    }
}

fun findDeco(id: String?, all: List<AvatarDeco>): AvatarDeco? = if (id == null) null else all.firstOrNull { it.id == id }

/** The decoration list, loaded once; empty until it arrives (or when it can't be loaded). */
@Composable
fun rememberDecos(): List<AvatarDeco> {
    val state = produceState(initialValue = emptyList<AvatarDeco>()) { value = Streams.decorations() }
    return state.value
}

/** Grid of None + every decoration (the website's DecorationPicker). */
@Composable
fun DecorationPicker(value: String?, onChange: (String?) -> Unit, modifier: Modifier = Modifier) {
    val list = rememberDecos()
    val items: List<Triple<String?, String, AvatarDeco?>> = listOf(Triple(null, "None", null)) + list.map { Triple(it.id, it.label, it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(4).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (id, label, deco) ->
                    val on = value == id
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier.weight(1f).clip(shape)
                            .background(if (on) Color(0x1A5865F2) else Color.Transparent, shape)
                            .border(1.dp, if (on) C.accent else C.line, shape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(id) }
                            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DecoratedAvatar(size = 48.dp, deco = deco, still = true) { Box(Modifier.fillMaxSize().background(C.input)) }
                        Spacer(Modifier.height(8.dp))
                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
