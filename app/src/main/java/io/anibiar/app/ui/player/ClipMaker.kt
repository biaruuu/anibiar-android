package io.anibiar.app.ui.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Size
import androidx.media3.effect.TextureOverlay
import androidx.media3.datasource.DataSource
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import io.anibiar.app.R
import io.anibiar.app.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ClipCue(val start: Double, val end: Double, val text: String)

enum class ClipFilter(val label: String) { None("Original"), Vivid("Vivid"), Warm("Warm"), Cool("Cool"), Mono("B&W"), Soft("Soft"), Cinema("Cinema") }

/** The picture adjustments of a filter. The editor preview and the exported file use exactly the same effects. */
fun filterEffects(f: ClipFilter): List<Effect> = when (f) {
    ClipFilter.None -> emptyList()
    ClipFilter.Vivid -> listOf(HslAdjustment.Builder().adjustSaturation(35f).build(), Contrast(0.12f))
    ClipFilter.Warm -> listOf(RgbAdjustment.Builder().setRedScale(1.1f).setBlueScale(0.9f).build())
    ClipFilter.Cool -> listOf(RgbAdjustment.Builder().setRedScale(0.92f).setBlueScale(1.1f).build())
    ClipFilter.Mono -> listOf(HslAdjustment.Builder().adjustSaturation(-100f).build())
    ClipFilter.Soft -> listOf(Contrast(-0.12f), Brightness(0.04f))
    ClipFilter.Cinema -> listOf(Contrast(0.2f), HslAdjustment.Builder().adjustSaturation(-15f).build(), RgbAdjustment.Builder().setRedScale(1.04f).setBlueScale(0.96f).build())
}

class ClipRequest(
    val url: String,
    val hls: Boolean,
    val headers: Map<String, String>,
    val start: Double,
    val length: Double,
    val cues: List<ClipCue>,
    /** Caption look, the same one the player shows. */
    val scale: Float,
    val box: Boolean,
    val fontId: String,
    val name: String,
    val captionsOn: Boolean = true,
    val filter: ClipFilter = ClipFilter.None,
    /** Own text on the picture: where its centre is (0..1 of the frame), how big (share of the frame width) and its colour. */
    val text: String = "",
    val textX: Float = 0.5f,
    val textY: Float = 0.2f,
    val textSize: Float = 0.06f,
    val textColor: Int = Color.WHITE,
    /** Output height in pixels (0 = as the stream has it), frame rate (0 = as is) and video bitrate in Mbps. */
    val height: Int = 0,
    val fps: Int = 0,
    val mbps: Int = 8,
    /** 60 or 120 = compute in-between frames (slow, done after the clip is cut); 0 = off. */
    val smooth: Int = 0,
)

/**
 * Saves up to one minute of the episode to the phone's gallery (Movies/AniBiar), in the best picture the stream has,
 * with the subtitles drawn into the video in the font and style the viewer picked.
 */
object ClipMaker {
    /** Frame interpolation needs FFmpeg's native library; on phones that can't load it the option is simply not offered. */
    val smoothAvailable: Boolean by lazy {
        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            Class.forName("com.arthenica.smartexception.java.Exceptions")
            android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Adds in-between frames to [input] (motion-compensated, with scene-change detection so cuts aren't blended) and
     * writes [output] at [fps]. Tries the phone's H.264 encoder first, then a software one. [onProgress] gets 0..1.
     */
    private suspend fun smooth(input: File, output: File, fps: Int, length: Double, mbps: Int, onProgress: (Float) -> Unit) {
        val filter = "minterpolate=fps=$fps:mi_mode=mci:mc_mode=obmc:me_mode=bidir:vsbmc=0:scd=fdiff"
        val rate = mbps.coerceIn(4, 20)
        val tries = listOf(
            "-y -i ${input.absolutePath} -vf $filter -c:v h264_mediacodec -b:v ${rate}M -c:a copy -movflags +faststart ${output.absolutePath}",
            "-y -i ${input.absolutePath} -vf $filter -c:v mpeg4 -q:v 3 -c:a copy -movflags +faststart ${output.absolutePath}",
        )
        var last = ""
        for (cmd in tries) {
            output.delete()
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                try {
                    val session = com.arthenica.ffmpegkit.FFmpegKit.executeAsync(
                        cmd,
                        { s ->
                            last = (s.failStackTrace ?: s.allLogsAsString ?: "").takeLast(300)
                            if (cont.isActive) cont.resume(com.arthenica.ffmpegkit.ReturnCode.isSuccess(s.returnCode))
                        },
                        { },
                        { st -> onProgress((st.time.toDouble() / (length * 1000.0)).coerceIn(0.0, 1.0).toFloat()) },
                    )
                    cont.invokeOnCancellation { try { com.arthenica.ffmpegkit.FFmpegKit.cancel(session.sessionId) } catch (_: Throwable) {} }
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            if (ok && output.exists() && output.length() > 0) return
        }
        throw IllegalStateException("Smoothing failed. ${last.trim()}".take(220))
    }

    private val bandwidth = Regex("BANDWIDTH=(\\d+)")

    /** The best picture of an HLS stream: the highest-bitrate variant, when it can be played on its own. */
    private suspend fun best(url: String, headers: Map<String, String>): String {
        return try {
            val r = Net.get(url, headers, 12_000)
            if (!r.ok || !r.body.contains("#EXT-X-STREAM-INF")) return url
            val lines = r.body.lines()
            var top = -1L
            var pick: String? = null
            var needsAudio = false
            for ((i, l) in lines.withIndex()) {
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                val bw = bandwidth.find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (bw > top) {
                    top = bw
                    pick = next.trim()
                    needsAudio = Regex("AUDIO=\"").containsMatchIn(l)
                }
            }
            if (pick == null || needsAudio) url else java.net.URI(url).resolve(pick).toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            url
        }
    }

    private class Overlay(private val ctx: Context, private val req: ClipRequest) : BitmapOverlay() {
        private var size = Size(1280, 720)
        private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private var lastText: String? = null
        private var lastBitmap: Bitmap = empty

        private val face: Typeface = try {
            ResourcesCompat.getFont(ctx, if (req.fontId == "fira") R.font.caption_bold else R.font.trebuchet_regular) ?: Typeface.DEFAULT_BOLD
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            size = videoSize
            lastText = null
        }

        private fun clean(s: String) = s.replace(Regex("<[^>]*>"), "").replace(Regex("\\{[^}]*\\}"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = req.start + presentationTimeUs / 1_000_000.0
            val text = if (req.captionsOn) req.cues.firstOrNull { t >= it.start && t <= it.end }?.let { clean(it.text) } ?: "" else ""
            if (text == lastText) return lastBitmap
            lastText = text
            lastBitmap = if (text.isEmpty() && req.text.isEmpty()) empty else render(text)
            return lastBitmap
        }

        private fun render(text: String): Bitmap {
            val w = size.width
            val h = size.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val px = w * 0.034f * (req.scale / 0.88f)
            fun paint(stroke: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                isFakeBoldText = req.fontId != "fira"
                textSize = px
                if (stroke) { style = Paint.Style.STROKE; strokeWidth = px * 0.26f; strokeJoin = Paint.Join.ROUND; color = Color.BLACK }
                else { style = Paint.Style.FILL; color = Color.WHITE }
            }
            val maxW = (w * 0.86f).toInt()
            fun layout(p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.15f).build()
            if (text.isNotEmpty()) {
                val fill = layout(paint(false))
                val stroke = layout(paint(true))
                val x = (w - maxW) / 2f
                val y = h - h * 0.07f - fill.height
                c.save()
                c.translate(x, y)
                if (req.box) {
                    val pad = px * 0.4f
                    val bg = Paint().apply { color = Color.argb(190, 0, 0, 0) }
                    c.drawRoundRect(-pad, -pad * 0.3f, maxW + pad, fill.height + pad * 0.3f, px * 0.35f, px * 0.35f, bg)
                } else {
                    stroke.draw(c)
                }
                fill.draw(c)
                c.restore()
            }
            if (req.text.isNotEmpty()) {
                val tpx = w * req.textSize
                fun tp(stroke: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = face
                    isFakeBoldText = req.fontId != "fira"
                    textSize = tpx
                    if (stroke) { style = Paint.Style.STROKE; strokeWidth = tpx * 0.2f; strokeJoin = Paint.Join.ROUND; color = Color.BLACK }
                    else { style = Paint.Style.FILL; color = req.textColor }
                }
                val tw = (w * 0.9f).toInt()
                fun tl(p: TextPaint) = StaticLayout.Builder.obtain(req.text, 0, req.text.length, p, tw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.1f).build()
                val f = tl(tp(false))
                val st = tl(tp(true))
                c.save()
                c.translate((req.textX * w - tw / 2f).coerceIn(0f, (w - tw).toFloat().coerceAtLeast(0f)), (req.textY * h - f.height / 2f).coerceIn(0f, (h - f.height).toFloat().coerceAtLeast(0f)))
                st.draw(c)
                f.draw(c)
                c.restore()
            }
            return bmp
        }
    }

    /**
     * The asset loader that reads the stream with our headers. Its constructor has changed between Media3 versions, so the
     * one this version has is picked and filled in by parameter type (the real classes are compared, because release
     * builds rename them).
     */
    private fun assetLoader(app: Context, sources: DefaultMediaSourceFactory): androidx.media3.transformer.AssetLoader.Factory {
        val decoder = DefaultDecoderFactory.Builder(app).build()
        val ctors = DefaultAssetLoaderFactory::class.java.constructors.sortedByDescending { it.parameterTypes.size }
        for (c in ctors) {
            val args = ArrayList<Any?>()
            var ok = true
            for (t in c.parameterTypes) {
                val a: Any? = when {
                    t == Context::class.java -> app
                    t == androidx.media3.transformer.Codec.DecoderFactory::class.java -> decoder
                    t == Clock::class.java -> Clock.DEFAULT
                    t == androidx.media3.exoplayer.source.MediaSource.Factory::class.java -> sources
                    t == androidx.media3.common.util.BitmapLoader::class.java -> androidx.media3.datasource.DataSourceBitmapLoader(app)
                    t == java.lang.Boolean.TYPE -> false
                    else -> { ok = false; null }
                }
                if (!ok) break
                args.add(a)
            }
            if (!ok) continue
            try {
                return c.newInstance(*args.toTypedArray()) as androidx.media3.transformer.AssetLoader.Factory
            } catch (_: Exception) {
            }
        }
        throw IllegalStateException("Could not set up the video reader (${ctors.size} variants tried)")
    }

    /** Network access for a stream that needs its Referer and other headers. */
    fun dataSource(headers: Map<String, String>): DataSource.Factory {
        val engine = Net.cronet
        return if (engine != null) {
            androidx.media3.datasource.cronet.CronetDataSource.Factory(engine, sharedExecutor).setDefaultRequestProperties(headers)
        } else {
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Net.client).setDefaultRequestProperties(headers)
        }
    }

    /** Makes the clip and returns where it was saved. Must be called on the main thread. */
    suspend fun make(ctx: Context, req: ClipRequest, onProgress: (Float) -> Unit): Uri {
        val source = if (req.hls) best(req.url, req.headers) else req.url
        val app = ctx.applicationContext
        val data = dataSource(req.headers)
        val mediaItem = MediaItem.Builder().setUri(source).apply { if (req.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((req.start * 1000).toLong())
                    .setEndPositionMs(((req.start + req.length) * 1000).toLong())
                    .build(),
            ).build()
        val effects = ArrayList<Effect>()
        effects.addAll(filterEffects(req.filter))
        if (req.height > 0) effects.add(Presentation.createForHeight(req.height))
        if (!(req.smooth > 0) && req.fps in 1..59) effects.add(FrameDropEffect.createDefaultFrameDropEffect(req.fps.toFloat()))
        if ((req.captionsOn && req.cues.isNotEmpty()) || req.text.isNotEmpty()) effects.add(OverlayEffect(ImmutableList.of<TextureOverlay>(Overlay(app, req))))
        val edited = EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), effects)).build()

        val out = File(app.cacheDir, "clip-${System.currentTimeMillis()}.mp4")
        val smoothing = req.smooth > 0 && smoothAvailable
        val encoder = DefaultEncoderFactory.Builder(app)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(req.mbps.coerceIn(2, 40) * 1_000_000).build())
            .setEnableFallback(true)
            .build()
        val loader = assetLoader(app, DefaultMediaSourceFactory(data))

        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(app)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .setAssetLoaderFactory(loader)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { try { transformer.cancel() } catch (_: Exception) {} }
            transformer.start(edited, out.absolutePath)
            val holder = ProgressHolder()
            io.anibiar.app.App.scope.launch(Dispatchers.Main) {
                while (cont.isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f * (if (smoothing) 0.25f else 1f))
                    delay(300)
                }
            }
        }
        if (smoothing) {
            val smoothed = File(app.cacheDir, "clip-smooth-${System.currentTimeMillis()}.mp4")
            try {
                withContext(Dispatchers.IO) { smooth(out, smoothed, req.smooth, req.length, req.mbps, { onProgress(0.25f + it * 0.75f) }) }
            } finally {
                out.delete()
            }
            return withContext(Dispatchers.IO) { saveToGallery(app, smoothed, req.name) }
        }
        return withContext(Dispatchers.IO) { saveToGallery(app, out, req.name) }
    }

    private fun saveToGallery(ctx: Context, file: File, name: String): Uri {
        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "AniBiar" }.take(60)
        val fileName = "$safe ${System.currentTimeMillis() / 1000}.mp4"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AniBiar")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Could not create the video")
                ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                return uri
            }
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AniBiar").apply { mkdirs() }
            val dest = File(dir, fileName)
            file.copyTo(dest, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            return Uri.fromFile(dest)
        } finally {
            file.delete()
        }
    }
}
