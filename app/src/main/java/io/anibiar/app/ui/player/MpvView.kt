package io.anibiar.app.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The libmpv video engine: draws the picture with mpv (gpu-next / gpu) so the Anime4K GLSL shaders can run on it.
 * Window and surface handling follows NuvioMobile-Enhanced's MpvSurfaceView.
 * Values mpv reports are kept in volatile fields so the player can read them from the main thread.
 */
class MpvView(context: Context) : FrameLayout(context) {
    private val mpv = MPV()
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "AniBiarMpv").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val released = AtomicBoolean(false)
    private val surfaceReady = CompletableDeferred<Unit>()
    private var surfaceAttached = false
    private var destroyJob: Job? = null
    private var ready = false

    @Volatile var position = 0.0; private set
    @Volatile var duration = 0.0; private set
    @Volatile var buffered = 0.0; private set
    @Volatile var paused = true; private set
    @Volatile var stalled = false; private set
    @Volatile var loaded = false; private set
    @Volatile var ended = false; private set
    @Volatile var videoW = 0; private set
    @Volatile var videoH = 0; private set
    @Volatile var outW = 0; private set
    @Volatile var outH = 0; private set
    @Volatile var error: String? = null; private set

    /** Starts mpv. Returns false when libmpv cannot start on this phone. */
    fun start(shaders: List<String>): Boolean {
        return try {
            Utils.copyAssets(context)
            mpv.create(context)
            mpv.setOptionString("config", "yes")
            mpv.setOptionString("config-dir", context.filesDir.path)
            mpv.setOptionString("gpu-shader-cache-dir", context.cacheDir.path)
            mpv.setOptionString("icc-cache-dir", context.cacheDir.path)
            mpv.setOptionString("vo", "gpu-next")
            mpv.setOptionString("profile", "fast")
            mpv.setOptionString("hwdec", "auto")
            mpv.setOptionString("msg-level", "all=warn")
            mpv.setOptionString("ytdl", "no")
            mpv.setOptionString("tls-verify", "yes")
            mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
            mpv.setOptionString("demuxer-lavf-o", "protocol_whitelist=[file,crypto,data,http,https,tcp,tls]")
            val cache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 * 1024 * 1024 else 32 * 1024 * 1024
            mpv.setOptionString("demuxer-max-bytes", "$cache")
            mpv.setOptionString("demuxer-max-back-bytes", "$cache")
            mpv.setOptionString("hls-bitrate", "max") // always the best picture the stream has
            mpv.setOptionString("sid", "no") // captions are drawn by the app (same style as the other engine)
            mpv.setOptionString("vd-lavc-film-grain", "cpu")
            if (shaders.isNotEmpty()) mpv.setOptionString("glsl-shaders", shaders.joinToString(":"))
            mpv.setPropertyBoolean("keep-open", true)
            mpv.setPropertyBoolean("audio-fallback-to-null", true)
            mpv.init()
            mpv.setOptionString("force-window", "no")
            mpv.setOptionString("idle", "once")
            observe()
            addView(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) surfaceView() else textureView())
            ready = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "libmpv could not start", e)
            error = e.localizedMessage ?: "libmpv unavailable"
            false
        }
    }

    private fun observe() {
        mapOf(
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "video-params/w" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "video-params/h" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "osd-width" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "osd-height" to MPV.mpvFormat.MPV_FORMAT_INT64,
        ).forEach { (name, format) -> mpv.observeProperty(name, format) }
        mpv.addObserver(object : MPV.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {
                when (property) {
                    "video-params/w" -> videoW = value.toInt()
                    "video-params/h" -> videoH = value.toInt()
                    "osd-width" -> outW = value.toInt()
                    "osd-height" -> outH = value.toInt()
                }
            }
            override fun eventProperty(property: String, value: Boolean) {
                when (property) {
                    "pause" -> paused = value
                    "paused-for-cache" -> stalled = value
                    "eof-reached" -> ended = value
                }
            }
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {
                when (property) {
                    "time-pos" -> position = value
                    "duration" -> duration = value
                    "demuxer-cache-time" -> buffered = value
                }
            }
            override fun eventProperty(property: String, value: MPVNode) {}
            override fun event(eventId: Int, data: MPVNode) {
                when (eventId) {
                    MPV.mpvEvent.MPV_EVENT_START_FILE -> { loaded = false; ended = false }
                    MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> loaded = true
                    MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                        val reason = runCatching { data["reason"]?.asString() }.getOrNull()
                        if (reason.equals("error", ignoreCase = true)) {
                            error = runCatching { data["file_error"]?.asString() }.getOrNull() ?: "Could not play this stream"
                        }
                    }
                }
            }
        })
    }

    private fun run(block: () -> Unit) {
        if (released.get()) return
        scope.launch {
            if (!released.get()) runCatching(block).onFailure { Log.w(TAG, "libmpv call failed", it) }
        }
    }

    /** Opens a stream. Waits for the picture surface first, so the first frame is never lost. */
    fun load(url: String, headers: Map<String, String>, startAt: Double, startPaused: Boolean) {
        if (!ready) return
        position = startAt; duration = 0.0; buffered = 0.0; loaded = false; ended = false; error = null
        scope.launch {
            runCatching { surfaceReady.await() }
            if (released.get()) return@launch
            runCatching {
                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                if (!ua.isNullOrBlank()) mpv.setPropertyString("user-agent", ua)
                val fields = headers.filterKeys { !it.equals("User-Agent", true) }
                    .map { (k, v) -> "$k: ${v.replace(",", "\\,")}" }.joinToString(",")
                mpv.setPropertyString("http-header-fields", fields)
                mpv.setPropertyBoolean("pause", startPaused)
                if (startAt > 1) mpv.setOptionString("start", startAt.toString()) else mpv.setOptionString("start", "0")
                mpv.command("loadfile", url, "replace")
                mpv.setPropertyBoolean("pause", startPaused)
            }.onFailure { Log.w(TAG, "loadfile failed", it); error = it.localizedMessage ?: "Could not play this stream" }
        }
    }

    fun stop() = run { mpv.command("stop") }
    fun setPaused(p: Boolean) = run { mpv.setPropertyBoolean("pause", p) }
    fun seek(seconds: Double) = run { mpv.command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute") }
    fun setSpeed(s: Double) = run { mpv.setPropertyDouble("speed", s) }
    fun setMuted(m: Boolean) = run { mpv.setPropertyBoolean("mute", m) }
    fun setShaders(paths: List<String>) = run { mpv.setPropertyString("glsl-shaders", paths.joinToString(":")) }

    /* ---------------- surface ---------------- */

    private fun attach(surface: Surface) = run {
        mpv.attachSurface(surface)
        surfaceAttached = true
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu-next")
        surfaceReady.complete(Unit)
    }

    private fun resize(w: Int, h: Int) = run { mpv.setPropertyString("android-surface-size", "${w}x$h") }

    private fun detach(done: () -> Unit) {
        val job = destroyJob ?: scope.launch { detachNow() }
        job.invokeOnCompletion { done() }
    }

    private fun detachNow() {
        if (!surfaceAttached) return
        surfaceAttached = false
        runCatching {
            try {
                mpv.setPropertyString("vo", "null")
                mpv.setPropertyString("force-window", "no")
            } finally {
                mpv.detachSurface()
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        surfaceReady.cancel()
        destroyJob = scope.launch {
            try {
                detachNow()
                runCatching { mpv.destroy() }
            } finally {
                dispatcher.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun surfaceView() = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            private lateinit var layer: SurfaceControl
            private lateinit var output: Surface

            override fun surfaceCreated(holder: SurfaceHolder) {
                val f = holder.surfaceFrame
                layer = SurfaceControl.Builder().setName("AniBiar mpv video").setParent(surfaceControl)
                    .setBufferSize(f.width(), f.height()).build()
                output = Surface(layer)
                SurfaceControl.Transaction().use { it.setLayer(layer, 1).setVisibility(layer, true).apply() }
                attach(output)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SurfaceControl.Transaction().use { it.setBufferSize(layer, width, height).apply() }
                resize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val oldLayer = layer
                val oldOutput = output
                SurfaceControl.Transaction().use { it.reparent(oldLayer, null).apply() }
                detach { oldOutput.release(); oldLayer.release() }
            }
        })
    }

    private fun textureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private lateinit var output: Surface
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { output = Surface(t); attach(output); resize(w, h) }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = resize(w, h)
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                val old = output
                detach { old.release(); t.release() }
                return false
            }
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
        }
    }

    private companion object { const val TAG = "AniBiarMpv" }
}
