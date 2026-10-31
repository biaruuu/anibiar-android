package io.anibiar.app.ui.player

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.anibiar.app.ui.C
import io.anibiar.app.ui.FontChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything the clip editor takes from the player that opened it. */
class ClipSource(
    val url: String,
    val hls: Boolean,
    val headers: Map<String, String>,
    val cues: List<ClipCue>,
    val position: Double,
    val duration: Double,
    val title: String,
    val scale: Float,
    val box: Boolean,
    val fontId: String,
    val sourceHeight: Int,
)

private const val MAX_CLIP = 60.0
private const val MIN_CLIP = 2.0
private const val WINDOW = 120.0

private fun clock(v: Double): String {
    val s = v.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** All the editor's choices, kept together; the preview player lives here too. */
@Stable
private class Editor(ctx: Context, val src: ClipSource) {
    val exo: ExoPlayer = ExoPlayer.Builder(ctx.applicationContext).build()
    val duration: Double = if (src.duration > 0) src.duration else maxOf(src.position + 120.0, 180.0)

    var start by mutableDoubleStateOf(src.position.coerceIn(0.0, (duration - MIN_CLIP).coerceAtLeast(0.0)))
    var end by mutableDoubleStateOf((src.position + 15.0).coerceAtMost(duration))
    var windowStart by mutableDoubleStateOf(0.0)
    var playhead by mutableDoubleStateOf(src.position)
    var playing by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)
    var videoHeight by mutableIntStateOf(src.sourceHeight)

    var tool by mutableStateOf("trim")
    var captionsOn by mutableStateOf(src.cues.isNotEmpty())
    var scale by mutableFloatStateOf(src.scale)
    var box by mutableStateOf(src.box)
    var fontId by mutableStateOf(src.fontId)
    var filter by mutableStateOf(ClipFilter.None)
    var text by mutableStateOf("")
    var textX by mutableFloatStateOf(0.5f)
    var textY by mutableFloatStateOf(0.18f)
    var textSize by mutableFloatStateOf(0.06f)
    var textColor by mutableIntStateOf(0xFFFFFFFF.toInt())
    var resIndex by mutableIntStateOf(2)
    var fpsIndex by mutableIntStateOf(1)
    var rateIndex by mutableIntStateOf(1)

    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var message by mutableStateOf<String?>(null)
    var job: Job? = null

    val heights = listOf(480, 720, 1080)
    val fpsList = listOf(24, 30, 60)
    val rates = listOf("Low" to 3, "Recommended" to 8, "High" to 16)

    init {
        if (end - start < MIN_CLIP) start = (end - MIN_CLIP).coerceAtLeast(0.0)
        centerWindow(start)
        val item = MediaItem.Builder().setUri(src.url).apply { if (src.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        exo.setMediaSource(DefaultMediaSourceFactory(ClipMaker.dataSource(src.headers)).createMediaSource(item), (start * 1000).toLong())
        exo.prepare()
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) {
                    aspect = v.width * v.pixelWidthHeightRatio / v.height
                    videoHeight = v.height
                }
            }
        })
    }

    fun centerWindow(t: Double) {
        windowStart = (t - WINDOW / 2).coerceIn(0.0, (duration - WINDOW).coerceAtLeast(0.0))
    }

    fun seek(t: Double) {
        val v = t.coerceIn(0.0, duration)
        playhead = v
        exo.seekTo((v * 1000).toLong())
    }

    fun moveStart(t: Double) {
        val v = t.coerceIn(0.0, end - MIN_CLIP)
        start = v
        if (end - v > MAX_CLIP) end = v + MAX_CLIP
        seek(v)
    }

    fun moveEnd(t: Double) {
        val v = t.coerceIn(start + MIN_CLIP, minOf(duration, start + MAX_CLIP))
        end = v
        seek((v - 1.0).coerceAtLeast(start))
    }

    /** Heights the picture can have (never above what the stream has). */
    fun heightOptions(): List<Int> = heights.filter { videoHeight <= 0 || it <= videoHeight + 40 }.ifEmpty { listOf(heights.first()) }

    fun request(): ClipRequest {
        val opts = heightOptions()
        return ClipRequest(
            url = src.url, hls = src.hls, headers = src.headers, start = start, length = end - start, cues = src.cues,
            scale = scale, box = box, fontId = fontId, name = src.title, captionsOn = captionsOn && src.cues.isNotEmpty(),
            filter = filter, text = text.trim(), textX = textX, textY = textY, textSize = textSize, textColor = textColor,
            height = opts[resIndex.coerceIn(0, opts.size - 1)], fps = fpsList[fpsIndex], mbps = rates[rateIndex].second,
        )
    }

    fun release() { try { exo.release() } catch (_: Exception) {} }
}

@Composable
fun ClipEditor(state: PlayerState) {
    val src = remember { state.clipSource() } ?: return
    val ctx = LocalContext.current
    val ed = remember { Editor(ctx, src) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { ed.release() } }

    // keeps the playhead moving and loops inside the chosen part
    LaunchedEffect(ed) {
        while (true) {
            val p = ed.exo.currentPosition / 1000.0
            if (!ed.busy && ed.exo.isPlaying && p >= ed.end) ed.exo.seekTo((ed.start * 1000).toLong()) else ed.playhead = p
            delay(80)
        }
    }
    LaunchedEffect(ed.filter) {
        try { ed.exo.setVideoEffects(filterEffects(ed.filter)) } catch (_: Throwable) {}
    }

    fun export() {
        if (ed.busy) return
        ed.exo.pause()
        ed.busy = true
        ed.progress = 0f
        ed.message = null
        ed.job = scope.launch {
            try {
                ClipMaker.make(ctx.applicationContext, ed.request()) { ed.progress = it }
                ed.message = "Saved to your gallery (Movies/AniBiar)."
                android.widget.Toast.makeText(ctx.applicationContext, "Clip saved to your gallery", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                ed.message = "Cancelled."
                throw e
            } catch (e: Throwable) {
                ed.message = "Could not save the clip: ${(e.message ?: e.javaClass.simpleName).take(140)}"
            } finally {
                ed.busy = false
            }
        }
    }

    fun close() {
        ed.job?.cancel()
        state.closeEditor()
    }

    Dialog(onDismissRequest = { if (!ed.busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0C)).statusBarsPadding().navigationBarsPadding()) {
            TopBar(ed, onClose = { close() }, onExport = { export() })
            // the picture
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Preview(ed) }
            Transport(ed)
            Timeline(ed)
            Tools(ed)
            ToolPanel(ed)
        }
    }
    if (ed.busy || ed.message != null) Status(ed, onDone = { ed.message = null }, onCancel = { ed.job?.cancel() })
}

@Composable
private fun TopBar(ed: Editor, onClose: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("✕", onClick = onClose)
        Spacer(Modifier.weight(1f))
        val opts = ed.heightOptions()
        Pill("${opts[ed.resIndex.coerceIn(0, opts.size - 1)]}P ▾", onClick = { ed.tool = "export" })
        Spacer(Modifier.width(10.dp))
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White).tapBox(onExport).padding(horizontal = 18.dp, vertical = 9.dp)) {
            Text("Export", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFF2A2A30)).tapBox(onClick).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

private fun Modifier.tapBox(onClick: () -> Unit): Modifier = this.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

/** The video with its captions and own text drawn over it (dragging the text moves it). */
@Composable
private fun Preview(ed: Editor) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxAspect = maxWidth / maxHeight
        val m = if (ed.aspect > boxAspect) Modifier.fillMaxWidth().aspectRatio(ed.aspect) else Modifier.fillMaxSize().aspectRatio(ed.aspect)
        BoxWithConstraints(m.background(Color.Black)) {
            val cw = maxWidth
            val density = LocalDensity.current
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = ed.exo
                    }
                },
            )
            // captions in the chosen style
            val cap = if (ed.captionsOn) ed.src.cues.firstOrNull { ed.playhead >= it.start && ed.playhead <= it.end }?.text?.replace(Regex("<[^>]*>"), "")?.replace(Regex("\\{[^}]*\\}"), "")?.trim() ?: "" else ""
            if (cap.isNotEmpty()) {
                val size = with(density) { (cw * 0.034f * (ed.scale / 0.88f)).toSp() }
                val style = TextStyle(fontFamily = FontChoice.family(ed.fontId), fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.2f, textAlign = TextAlign.Center)
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = cw * 0.04f, start = cw * 0.06f, end = cw * 0.06f)) {
                    if (ed.box) {
                        Text(cap, Modifier.background(Color(0xBF000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp), style = style.copy(color = Color.White))
                    } else {
                        Text(cap, style = style.copy(color = Color.Black, drawStyle = Stroke(width = with(density) { (cw * 0.0085f).toPx() }, join = StrokeJoin.Round)))
                        Text(cap, style = style.copy(color = Color.White))
                    }
                }
            }
            if (ed.text.isNotBlank()) {
                val fs = with(density) { (cw * ed.textSize).toSp() }
                BoxWithConstraints(
                    Modifier.fillMaxSize().pointerInput(ed) {
                        val pw = size.width.toFloat().coerceAtLeast(1f)
                        val ph = size.height.toFloat().coerceAtLeast(1f)
                        detectDragGestures { change, drag ->
                            change.consume()
                            ed.textX = (ed.textX + drag.x / pw).coerceIn(0.05f, 0.95f)
                            ed.textY = (ed.textY + drag.y / ph).coerceIn(0.05f, 0.95f)
                        }
                    },
                ) {
                    val style = TextStyle(fontFamily = FontChoice.family(ed.fontId), fontWeight = FontWeight.Bold, fontSize = fs, textAlign = TextAlign.Center)
                    Box(
                        Modifier.layout { measurable, constraints ->
                            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth * 0.9f).toInt()))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                p.place((ed.textX * constraints.maxWidth - p.width / 2f).toInt(), (ed.textY * constraints.maxHeight - p.height / 2f).toInt())
                            }
                        },
                    ) {
                        Text(ed.text, style = style.copy(color = Color.Black, drawStyle = Stroke(width = with(density) { (cw * ed.textSize * 0.2f).toPx() }, join = StrokeJoin.Round)))
                        Text(ed.text, style = style.copy(color = Color(ed.textColor)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Transport(ed: Editor) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${clock(ed.playhead)} / ${clock(ed.duration)}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White).tapBox {
            if (ed.exo.isPlaying) ed.exo.pause() else {
                if (ed.playhead >= ed.end || ed.playhead < ed.start) ed.seek(ed.start)
                ed.exo.play()
            }
        }, contentAlignment = Alignment.Center) {
            Text(if (ed.playing) "❚❚" else "▶", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Text("${clock(ed.end - ed.start)} clip", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A whole-episode bar to jump around, and a zoomed strip with two handles to cut the part (up to a minute). */
@Composable
private fun Timeline(ed: Editor) {
    val accent = Color(0xFF7C8CFF)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        // overview
        Canvas(
            Modifier.fillMaxWidth().height(26.dp)
                .pointerInput(ed) { detectTapGestures { o -> val t = o.x / size.width * ed.duration; ed.centerWindow(t); ed.seek(t) } }
                .pointerInput(ed) { detectDragGestures { c, _ -> c.consume(); val t = c.position.x.coerceIn(0f, size.width.toFloat()) / size.width * ed.duration; ed.centerWindow(t); ed.seek(t) } },
        ) {
            val y = size.height / 2
            drawRoundRect(Color(0x33FFFFFF), Offset(0f, y - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            val a = (ed.start / ed.duration * size.width).toFloat()
            val b = (ed.end / ed.duration * size.width).toFloat()
            drawRoundRect(accent, Offset(a, y - 3.dp.toPx()), Size((b - a).coerceAtLeast(3.dp.toPx()), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            val ws = (ed.windowStart / ed.duration * size.width).toFloat()
            val we = ((ed.windowStart + WINDOW) / ed.duration * size.width).toFloat().coerceAtMost(size.width)
            drawRoundRect(Color.White, Offset(ws, y - 10.dp.toPx()), Size((we - ws).coerceAtLeast(6.dp.toPx()), 20.dp.toPx()), CornerRadius(5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        Spacer(Modifier.height(6.dp))
        // trim strip
        var grab by remember { mutableIntStateOf(0) } // 1 = start handle, 2 = end handle, 3 = playhead
        Canvas(
            Modifier.fillMaxWidth().height(74.dp)
                .pointerInput(ed) {
                    detectTapGestures { o -> ed.seek(ed.windowStart + o.x / size.width * WINDOW) }
                }
                .pointerInput(ed) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val xs = ((ed.start - ed.windowStart) / WINDOW * size.width).toFloat()
                            val xe = ((ed.end - ed.windowStart) / WINDOW * size.width).toFloat()
                            val reach = 30.dp.toPx()
                            val ds = kotlin.math.abs(o.x - xs)
                            val de = kotlin.math.abs(o.x - xe)
                            grab = if (ds <= reach && ds <= de) 1 else if (de <= reach) 2 else 3
                        },
                        onDragEnd = { grab = 0 },
                        onDragCancel = { grab = 0 },
                    ) { change, _ ->
                        change.consume()
                        val t = ed.windowStart + change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * WINDOW
                        when (grab) { 1 -> ed.moveStart(t); 2 -> ed.moveEnd(t); else -> ed.seek(t) }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            fun x(t: Double) = ((t - ed.windowStart) / WINDOW * w).toFloat()
            drawRoundRect(Color(0xFF1B1B20), Offset.Zero, Size(w, h), CornerRadius(10.dp.toPx()))
            // ticks every 5 s
            var t = Math.ceil(ed.windowStart / 5.0) * 5.0
            while (t <= ed.windowStart + WINDOW) {
                val major = (t.toInt() % 30) == 0
                drawLine(Color(if (major) 0x66FFFFFF else 0x22FFFFFF), Offset(x(t), if (major) h * 0.12f else h * 0.3f), Offset(x(t), h * 0.88f), 1.dp.toPx())
                t += 5.0
            }
            val a = x(ed.start).coerceIn(0f, w)
            val b = x(ed.end).coerceIn(0f, w)
            drawRect(accent.copy(alpha = 0.28f), Offset(a, 0f), Size((b - a).coerceAtLeast(0f), h))
            drawRoundRect(Color.White, Offset(a, 0f), Size((b - a).coerceAtLeast(0f), h), CornerRadius(8.dp.toPx()), style = Stroke(2.5.dp.toPx()))
            val hw = 12.dp.toPx()
            drawRoundRect(Color.White, Offset(a - hw / 2, -2.dp.toPx()), Size(hw, h + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
            drawRoundRect(Color.White, Offset(b - hw / 2, -2.dp.toPx()), Size(hw, h + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
            drawLine(Color(0xFF111111), Offset(a, h * 0.3f), Offset(a, h * 0.7f), 2.dp.toPx())
            drawLine(Color(0xFF111111), Offset(b, h * 0.3f), Offset(b, h * 0.7f), 2.dp.toPx())
            val px = x(ed.playhead)
            if (px in 0f..w) {
                drawLine(Color(0xFFFF5A5A), Offset(px, -6.dp.toPx()), Offset(px, h + 6.dp.toPx()), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("start ${clock(ed.start)}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("drag the white handles · max 1:00", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("end ${clock(ed.end)}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun Tools(ed: Editor) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("trim" to "Trim", "captions" to "Captions", "text" to "Text", "filters" to "Filters", "export" to "Quality").forEach { (id, label) ->
            val on = ed.tool == id
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color.White else Color(0xFF222228)).tapBox { ed.tool = id }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFF7C8CFF) else Color(0xFF26262C)).tapBox(onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

@Composable
private fun StopSlider(options: List<String>, index: Int, onChange: (Int) -> Unit) {
    Column {
        Slider(
            value = index.toFloat(), onValueChange = { onChange(Math.round(it).coerceIn(0, options.size - 1)) },
            valueRange = 0f..(options.size - 1).coerceAtLeast(1).toFloat(), steps = (options.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF7C8CFF), inactiveTrackColor = Color(0x33FFFFFF), activeTickColor = Color.Transparent, inactiveTickColor = Color.White.copy(alpha = 0.4f)),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                Text(o, Modifier.weight(1f), color = if (i == index) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 12.sp, textAlign = if (i == 0) TextAlign.Start else if (i == options.size - 1) TextAlign.End else TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ToolPanel(ed: Editor) {
    Column(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        when (ed.tool) {
            "captions" -> {
                if (ed.src.cues.isEmpty()) {
                    Text("This video has its subtitles built into the picture, so there are no caption tracks to style.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Subtitles on", ed.captionsOn) { ed.captionsOn = true }
                        Chip("Off", !ed.captionsOn) { ed.captionsOn = false }
                    }
                    Label("FONT")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FontChoice.caption.forEach { (id, name) -> Chip(name, ed.fontId == id) { ed.fontId = id } }
                    }
                    Label("SIZE")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Small" to 0.88f, "Medium" to 1.1f, "Large" to 1.4f).forEach { (n, v) -> Chip(n, kotlin.math.abs(ed.scale - v) < 0.01f) { ed.scale = v } }
                    }
                    Label("STYLE")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Outline", !ed.box) { ed.box = false }
                        Chip("Dark box", ed.box) { ed.box = true }
                    }
                }
            }
            "text" -> {
                BasicTextField(
                    value = ed.text, onValueChange = { ed.text = it.take(80) },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF222228)).padding(14.dp),
                    decorationBox = { inner -> if (ed.text.isEmpty()) Text("Type text to put on the video", color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp); inner() },
                )
                Label("COLOUR")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0xFFFFFFFF, 0xFFFFE14D, 0xFFFF7AB8, 0xFF5CE1FF, 0xFFBEF264, 0xFFFF5A5A).forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(c)).tapBox { ed.textColor = c.toInt() },
                            contentAlignment = Alignment.Center,
                        ) { if (ed.textColor == c.toInt()) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Black)) }
                    }
                }
                Label("SIZE")
                Slider(
                    value = ed.textSize, onValueChange = { ed.textSize = it }, valueRange = 0.03f..0.12f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF7C8CFF), inactiveTrackColor = Color(0x33FFFFFF)),
                )
                Text("Drag the text on the video to move it.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            "filters" -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClipFilter.values().forEach { f -> Chip(f.label, ed.filter == f) { ed.filter = f } }
                }
                Spacer(Modifier.height(8.dp))
                Text("The preview and the saved clip use the same filter.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            "export" -> {
                val hs = ed.heightOptions()
                Row { Text("Resolution", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text("Higher is sharper, the file gets bigger", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp) }
                StopSlider(hs.map { "${it}p" }, ed.resIndex.coerceIn(0, hs.size - 1)) { ed.resIndex = it }
                Text("Frame rate", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                StopSlider(ed.fpsList.map { "$it" }, ed.fpsIndex) { ed.fpsIndex = it }
                Text("Bitrate", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                StopSlider(ed.rates.map { it.first }, ed.rateIndex) { ed.rateIndex = it }
            }
            else -> {
                Text("Trim", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Drag the left and right white handles to pick the part you want (up to 1 minute). Drag the grey bar at the top to jump to another scene, or tap the strip to move the red playhead.", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Start here", false) { ed.moveStart(ed.playhead) }
                    Chip("End here", false) { ed.moveEnd(ed.playhead) }
                    Chip("Play clip", false) { ed.seek(ed.start); ed.exo.play() }
                }
            }
        }
    }
}

@Composable
private fun Status(ed: Editor, onDone: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = { if (!ed.busy) onDone() }, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF17171B)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ed.busy) {
                Text("Saving your clip…", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(ed.progress.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(Color(0xFF7C8CFF)))
                }
                Spacer(Modifier.height(8.dp))
                Text("${(ed.progress * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Chip("Cancel", false, onCancel)
            } else {
                Text(ed.message ?: "", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Chip("OK", true, onDone)
            }
        }
    }
}
