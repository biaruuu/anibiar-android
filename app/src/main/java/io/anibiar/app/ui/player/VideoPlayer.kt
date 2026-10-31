@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.anibiar.app.ui.player

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.anibiar.app.data.clock
import io.anibiar.app.ui.C
import io.anibiar.app.ui.Flat
import io.anibiar.app.ui.Kind
import io.anibiar.app.ui.VIcon

private fun cl(min: Float, v: Float, max: Float): Float = if (v < min) min else if (v > max) max else v

/** Creates the player, releases it when the screen closes, keeps the screen awake while playing and pauses in the background. */
@Composable
fun rememberPlayerState(): PlayerState {
    val ctx = LocalContext.current
    val state = remember { PlayerState(ctx) }
    DisposableEffect(state) { onDispose { state.release() } }

    val view = LocalView.current
    val playing = state.playing
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) state.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
private fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

private fun firstTrack(state: PlayerState): Int {
    for (i in state.tracks.indices) {
        val l = "${state.tracks[i].label} ${state.tracks[i].lang}".lowercase()
        if (l.contains("english") || l.startsWith("en")) return i
    }
    return 0
}

/**
 * The player: video, captions and every control, drawn from [state]. It fills the box it is given (the watch screen
 * makes it 16:9 inline or full screen). [overlay] sits above the video and below the controls.
 */
@Composable
fun VideoPlayer(
    state: PlayerState,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.background(Color.Black)) {
        val w = maxWidth.value
        val h = maxHeight.value
        val widthPx = constraints.maxWidth.toFloat()
        val pad = cl(8f, w * 0.022f, 20f)
        val btn = cl(36f, w * 0.045f, 46f)
        val icon = cl(20f, w * 0.034f, 28f)
        val side = cl(40f, w * 0.06f, 64f)
        val center = cl(48f, w * 0.07f, 74f)
        val hidden = !state.controlsVisible && !state.menuOpen
        val topBar = btn + cl(4f, w * 0.006f, 8f) + pad

        // video
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    subtitleView?.visibility = View.GONE
                    player = state.exo
                }
            },
            update = { v ->
                if (v.player !== state.exo) v.player = state.exo
            },
        )

        Captions(state, w, controls = state.controlsVisible)

        // taps: show/hide controls; double tap: left third back, right third forward, middle fullscreen
        Box(
            Modifier.fillMaxSize().pointerInput(fullscreen, widthPx) {
                detectTapGestures(
                    onTap = { if (state.menuOpen) state.closeMenu() else state.toggleControls() },
                    onDoubleTap = { p ->
                        when {
                            p.x < widthPx / 3f -> {
                                state.seekBy(-10.0)
                                state.flash(-1)
                            }
                            p.x > widthPx * 2f / 3f -> {
                                state.seekBy(10.0)
                                state.flash(1)
                            }
                            else -> onToggleFullscreen()
                        }
                    },
                )
            },
        )

        overlay()

        // brief circle for the double-tap seek
        val flash = state.seekFlash
        if (flash != null) {
            Box(
                Modifier.align(if (flash == -1) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(start = if (flash == -1) (w * 0.12f).dp else 0.dp, end = if (flash == 1) (w * 0.12f).dp else 0.dp)
                    .size(side.dp).clip(CircleShape).background(C.k(0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                VIcon(if (flash == -1) "seek_backward_10" else "seek_forward_10", size = (side * 0.74f).dp)
            }
        }

        if (state.loading || (state.buffering && state.ready && hidden)) {
            val d = cl(30f, w * 0.06f, 52f).dp
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(d), color = Color.White, strokeWidth = 3.dp)
        }

        SkipLayer(state, w, controls = !hidden, modifier = Modifier.align(Alignment.BottomEnd))

        // soft shade behind the controls so they read on bright frames
        AnimatedVisibility(visible = !hidden, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            Box(Modifier.fillMaxSize().background(Color(0x59000000)))
        }

        // ---------------- top bar: volume on the left, tools on the right ----------------
        AnimatedVisibility(
            visible = !hidden,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { -it / 3 },
            exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 3 },
        ) {
            Row(Modifier.fillMaxWidth().padding(pad.dp), verticalAlignment = Alignment.Top) {
                Pill {
                    IconBtn(if (state.muted) "mute" else "volume_high", btn.dp, icon.dp) { state.toggleMute() }
                }
                if (fullscreen) {
                    Text(
                        title,
                        Modifier.weight(1f).padding(horizontal = pad.dp).padding(top = (btn / 2f - 8f).dp),
                        color = Color.White, fontSize = cl(12f, w * 0.016f, 16f).sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Pill {
                    if (state.tracks.isNotEmpty()) {
                        IconBtn(if (state.track >= 0) "closed_captions_on" else "closed_captions", btn.dp, icon.dp) {
                            state.setTrack(if (state.track >= 0) -1 else firstTrack(state))
                            state.touch()
                        }
                    }
                    val turn by animateFloatAsState(if (state.menuOpen) 45f else 0f, tween(250), label = "gear")
                    IconBtn("settings", btn.dp, icon.dp, rotate = turn) { if (state.menuOpen) state.closeMenu() else state.openMenu() }
                }
            }
        }

        // ---------------- centre: 10 s buttons well to either side of play/pause ----------------
        AnimatedVisibility(
            visible = !hidden && !state.failed,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
        ) {
            Box(Modifier.fillMaxWidth().height(center.dp), contentAlignment = Alignment.Center) {
                Round(side.dp, "seek_backward_10", Modifier.offset(x = (-(w * 0.25f)).dp)) {
                    state.seekBy(-10.0)
                    state.touch()
                }
                Round(
                    center.dp, if (state.playing) "pause" else "play", Modifier,
                    spinner = state.buffering && state.playing,
                ) { state.togglePlay() }
                Round(side.dp, "seek_forward_10", Modifier.offset(x = (w * 0.25f).dp)) {
                    state.seekBy(10.0)
                    state.touch()
                }
            }
        }

        // ---------------- bottom: time + fullscreen above a thin seek bar ----------------
        AnimatedVisibility(
            visible = !hidden,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 5 },
            exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { it / 5 },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = cl(12f, w * 0.035f, 48f).dp).padding(bottom = cl(6f, w * 0.02f, 28f).dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    TimeText(state, title, w, Modifier.weight(1f))
                    Flat(
                        Modifier.width(cl(38f, w * 0.056f, 54f).dp).height(cl(29f, w * 0.041f, 42f).dp),
                        kind = Kind.Bar,
                        radius = cl(12f, w * 0.024f, 20f).dp,
                        onClick = onToggleFullscreen,
                    ) {
                        VIcon(if (fullscreen) "fullscreen_exit" else "fullscreen", size = cl(20f, w * 0.034f, 28f).dp)
                    }
                }
                Spacer(Modifier.height(2.dp))
                SeekBar(state)
            }
        }

        // ---------------- settings menu ----------------
        if (state.menuOpen) {
            Box(Modifier.fillMaxSize().tap { state.closeMenu() })
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = (topBar + 4f).dp, end = pad.dp).width(cl(224f, w * 0.4f, 320f).dp),
            ) {
                Menu(state, w, maxHeight = (h - topBar - pad - 8f).coerceIn(120f, 600f))
            }
        }
    }
}

/* ----------------------------------- pieces ----------------------------------- */

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Flat(kind = Kind.Bar, radius = 999.dp) {
        Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun IconBtn(name: String, size: Dp, iconSize: Dp, rotate: Float = 0f, onClick: () -> Unit) {
    Box(Modifier.size(size).tap(onClick), contentAlignment = Alignment.Center) {
        VIcon(name, Modifier.rotate(rotate), size = iconSize)
    }
}

@Composable
private fun Round(size: Dp, icon: String, modifier: Modifier, spinner: Boolean = false, onClick: () -> Unit) {
    Flat(modifier.size(size), kind = Kind.Bar, radius = size / 2, onClick = onClick) {
        if (spinner) CircularProgressIndicator(Modifier.size(size * 0.42f), color = Color.White, strokeWidth = 2.5.dp)
        else VIcon(icon, size = size * 0.74f)
    }
}

@Composable
private fun TimeText(state: PlayerState, title: String, w: Float, modifier: Modifier) {
    val dim = C.w(0.75f)
    Text(
        buildAnnotatedString {
            append(clock(state.position))
            withStyle(SpanStyle(color = dim)) {
                append(" / ${clock(state.duration)}")
                if (w > 560f) append("  •  $title")
            }
        },
        modifier,
        color = Color.White,
        fontSize = cl(12f, w * 0.018f, 16f).sp,
        fontWeight = FontWeight.Medium,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Thin progress bar: grows a little while held, white thumb. */
@Composable
private fun SeekBar(state: PlayerState) {
    var widthPx by remember { mutableIntStateOf(1) }
    var drag by remember { mutableStateOf<Double?>(null) }
    val dur = if (state.duration <= 0) 1.0 else state.duration
    val value = ((drag ?: state.position) / dur).coerceIn(0.0, 1.0).toFloat()
    val buffered = state.bufferedFraction.coerceIn(0f, 1f)
    val barH by animateDpAsState(if (drag != null) 6.dp else 4.dp, tween(120), label = "seekH")
    val density = LocalDensity.current
    val widthDp = with(density) { widthPx.toDp() }

    fun at(x: Float): Double = (x / widthPx.coerceAtLeast(1)).coerceIn(0f, 1f).toDouble()

    Box(
        Modifier.fillMaxWidth().height(26.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(widthPx, dur) {
                detectTapGestures(onTap = { p -> state.seekTo(at(p.x) * dur); state.touch() })
            }
            .pointerInput(widthPx, dur) {
                detectHorizontalDragGestures(
                    onDragStart = { p ->
                        state.seeking = true
                        drag = at(p.x) * dur
                    },
                    onDragEnd = {
                        drag?.let { state.seekTo(it) }
                        state.seeking = false
                        drag = null
                        state.touch()
                    },
                    onDragCancel = {
                        state.seeking = false
                        drag = null
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag = at(change.position.x) * dur
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(barH).clip(CircleShape).background(C.w(0.3f)))
        Box(Modifier.fillMaxWidth(buffered).height(barH).clip(CircleShape).background(C.w(0.5f)))
        Box(Modifier.fillMaxWidth(value).height(barH).clip(CircleShape).background(Color.White))
        if (drag != null) {
            val x = (widthDp * value - 6.dp).coerceIn(0.dp, (widthDp - 12.dp).coerceAtLeast(0.dp))
            Box(Modifier.offset(x = x).size(12.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** Captions drawn by us in the Crunchyroll look: bold white Trebuchet with a thick black outline (or a dark box). */
@Composable
private fun BoxScope.Captions(state: PlayerState, w: Float, controls: Boolean) {
    val raw = state.caption
    if (raw.isEmpty() || state.track < 0) return
    val text = raw.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
    if (text.isEmpty()) return
    // same sizing as the website: clamp(0.85rem, 2.6cqw, 2.5rem) times the chosen size
    val size = cl(15f, w * 0.03f, 34f) * state.captionScale
    // sits low on the picture; lifts a little only while the control bar is showing
    val lift = if (controls) cl(46f, w * 0.09f, 90f) else cl(3f, w * 0.008f, 14f)
    val base = TextStyle(
        fontFamily = io.anibiar.app.ui.FontChoice.family(io.anibiar.app.ui.FontChoice.captionId),
        fontWeight = FontWeight.Bold,
        fontSize = size.sp,
        lineHeight = (size * 1.22f).sp,
        letterSpacing = 0.sp,
        shadow = androidx.compose.ui.graphics.Shadow(Color(0xB3000000), androidx.compose.ui.geometry.Offset(0f, 2f), 4f),
        textAlign = TextAlign.Center,
    )
    Box(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = (w * 0.05f).dp).padding(bottom = lift.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (state.captionBox) {
            Text(
                text,
                Modifier.widthIn(max = (size * 52f).dp).background(C.k(0.75f), RoundedCornerShape((size * 0.35f).dp))
                    .padding(horizontal = (size * 0.6f).dp, vertical = (size * 0.12f).dp),
                style = base.copy(color = Color.White),
            )
        } else {
            val strokePx = with(LocalDensity.current) { (size * 0.26f).sp.toPx() }
            Box(Modifier.widthIn(max = (size * 52f).dp)) {
                Text(text, style = base.copy(color = Color.Black, shadow = null, drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round)))
                Text(text, style = base.copy(color = Color.White))
            }
        }
    }
}

/** "Skip Intro" / "Skip Outro" button, or the "Intro skipped · Undo" note after an automatic skip. */
@Composable
private fun SkipLayer(state: PlayerState, w: Float, controls: Boolean, modifier: Modifier) {
    val toast = state.skipToast
    val target = state.skipTarget
    if (toast == null && target == null) return
    val fs = cl(12f, w * 0.02f, 16f)
    val end = cl(12f, w * 0.035f, 48f).dp
    val bottom = if (controls) cl(46f, w * 0.1f, 96f).dp else cl(14f, w * 0.03f, 40f).dp
    Box(modifier.padding(end = end, bottom = bottom)) {
        if (toast != null) {
            Flat(kind = Kind.Panel, radius = 14.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (toast.kind == SkipKind.INTRO) "Intro skipped" else "Outro skipped", color = Color.White, fontSize = fs.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Undo",
                        Modifier.tap { state.undoSkip() },
                        color = Color.White, fontSize = fs.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline,
                    )
                }
            }
        } else if (target != null) {
            Flat(kind = Kind.White, radius = 999.dp, onClick = { state.skipTo(target, automatic = false) }) {
                Row(
                    Modifier.padding(horizontal = (fs * 1.1f).dp, vertical = (fs * 0.6f).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (target == SkipKind.INTRO) "Skip Intro" else "Skip Outro", color = Color.Black, fontSize = fs.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width((fs * 0.4f).dp))
                    VIcon("seek_forward_10", size = (fs * 1.5f).dp, tint = Color.Black)
                }
            }
        }
    }
}

/* ------------------------------------ menu ------------------------------------- */

@Composable
private fun Menu(state: PlayerState, w: Float, maxHeight: Float) {
    val fs = cl(12.5f, w * 0.022f, 15f)
    val view = state.menuView
    var title = "Settings"
    Flat(kind = Kind.Panel, radius = 18.dp, contentAlignment = Alignment.TopStart) {
        Column(Modifier.heightIn(max = maxHeight.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            when (view) {
                "captions" -> title = "Captions"
                "style" -> title = "Caption style"
                "speed" -> title = "Speed"
                "quality" -> title = "Quality"
            }
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (view != "main") {
                    Box(Modifier.padding(end = 8.dp).tap { state.setMenuView("main") }) { VIcon("chevron_left", size = (fs + 10f).dp) }
                }
                Text(title, Modifier.weight(1f), color = Color.White, fontSize = (fs + 1.5f).sp, fontWeight = FontWeight.SemiBold)
            }
            when (view) {
                "captions" -> {
                    MenuOpt("Off", state.track < 0, fs) {
                        state.setTrack(-1)
                        state.setMenuView("main")
                    }
                    state.tracks.forEachIndexed { i, t ->
                        MenuOpt(t.label, state.track == i, fs) {
                            state.setTrack(i)
                            state.setMenuView("main")
                        }
                    }
                }
                "style" -> {
                    MenuHead("Size", fs)
                    listOf("Small" to 0.88f, "Medium" to 1.1f, "Large" to 1.4f).forEach { (label, s) ->
                        MenuOpt(label, kotlin.math.abs(state.captionScale - s) < 0.01f, fs) { state.setCaptionStyle(scale = s) }
                    }
                    MenuHead("Font", fs)
                    io.anibiar.app.ui.FontChoice.caption.forEach { (id, label) ->
                        MenuOpt(label, io.anibiar.app.ui.FontChoice.captionId == id, fs) { io.anibiar.app.ui.FontChoice.chooseCaption(id) }
                    }
                    MenuHead("Style", fs)
                    MenuOpt("Outline", !state.captionBox, fs) { state.setCaptionStyle(box = false) }
                    MenuOpt("Dark box", state.captionBox, fs) { state.setCaptionStyle(box = true) }
                }
                "speed" -> {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        MenuOpt(if (s == 1f) "Normal" else "${s}x", state.speed == s, fs) {
                            state.setSpeed(s)
                            state.setMenuView("main")
                        }
                    }
                }
                "quality" -> {
                    MenuOpt("Auto", state.quality < 0, fs, hint = "Picks the best quality your connection can handle.") {
                        state.setQuality(-1)
                        state.closeMenu()
                    }
                    state.levels.forEachIndexed { i, l ->
                        MenuOpt("${l.height}p", state.quality == i, fs) {
                            state.setQuality(i)
                            state.closeMenu()
                        }
                    }
                }
                else -> {
                    val q = if (state.quality < 0 || state.quality >= state.levels.size) "Auto" else "${state.levels[state.quality].height}p"
                    if (state.levels.isNotEmpty()) MenuRow("Quality", q, fs, chevron = true) { state.setMenuView("quality") }
                    MenuRow(
                        "Captions",
                        if (state.tracks.isEmpty()) "Built in" else if (state.track < 0) "Off" else state.tracks.getOrNull(state.track)?.label ?: "Off",
                        fs, chevron = true,
                        onClick = if (state.tracks.isEmpty()) null else ({ state.setMenuView("captions") }),
                    )
                    if (state.tracks.isNotEmpty()) MenuRow("Caption style", "", fs, chevron = true) { state.setMenuView("style") }
                    MenuRow("Speed", if (state.speed == 1f) "Normal" else "${state.speed}x", fs, chevron = true) { state.setMenuView("speed") }
                    MenuRow("Autoplay", if (state.autoplay) "On" else "Off", fs, chevron = false) { state.setAutoplay(!state.autoplay) }
                    MenuRow(
                        "Auto skip intro/outro",
                        if (state.intro == null && state.outro == null) "No data" else if (state.autoSkip) "On" else "Off",
                        fs, chevron = false,
                    ) { state.setAutoSkip(!state.autoSkip) }
                    MenuRow("Auto next episode", if (state.autoNext) "On" else "Off", fs, chevron = false) { state.setAutoNext(!state.autoNext) }
                }
            }
        }
    }
}

@Composable
private fun MenuHead(text: String, fs: Float) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 4.dp),
        color = C.w(0.5f), fontSize = (fs - 2f).sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun MenuRow(label: String, value: String, fs: Float, chevron: Boolean, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        var m = Modifier.fillMaxWidth().clip(shape).background(C.w(0.07f), shape)
        if (onClick != null) m = m.tap(onClick)
        Row(m.padding(horizontal = 12.dp, vertical = (fs * 0.75f).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, Modifier.weight(1f), color = Color.White.copy(alpha = if (onClick == null) 0.5f else 1f),
                fontSize = fs.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (value.isNotEmpty()) {
                Text(
                    value, Modifier.padding(start = 8.dp), color = C.w(0.65f), fontSize = (fs - 1f).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (chevron) VIcon("chevron_right", size = (fs + 6f).dp, tint = C.w(0.45f))
        }
    }
}

@Composable
private fun MenuOpt(label: String, selected: Boolean, fs: Float, hint: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(if (selected) C.w(0.16f) else C.w(0.06f), shape).tap(onClick)
                .padding(horizontal = 12.dp, vertical = (fs * 0.7f).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, Modifier.weight(1f), color = Color.White, fontSize = fs.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (selected) VIcon("check", size = (fs + 6f).dp)
            }
            if (hint != null) {
                Text(hint, Modifier.padding(top = 3.dp), color = C.w(0.5f), fontSize = (fs - 2.5f).sp)
            }
        }
    }
}
