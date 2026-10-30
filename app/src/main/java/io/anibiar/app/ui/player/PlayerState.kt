@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.anibiar.app.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.anibiar.app.core.Net
import io.anibiar.app.core.Prefs
import io.anibiar.app.data.StreamSubtitle
import io.anibiar.app.data.TimeRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class SkipKind { INTRO, OUTRO }

/** The "Intro skipped / Undo" toast: where the viewer was before the automatic skip. */
class SkipToast(val kind: SkipKind, val from: Double)

/** One picture quality the stream offers (a row in the Quality menu). */
class QualityLevel(val height: Int, internal val group: TrackGroup, internal val index: Int, internal val bitrate: Int)

private class Cue(val start: Double, val end: Double, val text: String)

private fun cueTime(raw: String): Double? {
    val p = raw.trim().replace(',', '.').split(':')
    return try {
        when (p.size) {
            3 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble()
            2 -> p[0].toDouble() * 60 + p[1].toDouble()
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

/** WebVTT and SubRip share the "start --> end" line, so one reader serves both. */
private fun parseCues(raw: String): List<Cue> {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿")
    val out = ArrayList<Cue>()
    for (block in text.split(Regex("\n\\s*\n"))) {
        val lines = block.split('\n')
        val i = lines.indexOfFirst { it.contains("-->") }
        if (i < 0) continue
        val parts = lines[i].split("-->")
        if (parts.size < 2) continue
        val s = cueTime(parts[0])
        val e = cueTime(parts[1].trim().split(Regex("\\s+"))[0])
        if (s == null || e == null) continue
        val body = lines.drop(i + 1).joinToString("\n").trim()
        if (body.isNotEmpty()) out.add(Cue(s, e, body))
    }
    return out
}

private fun cleanCaption(s: String): String = s
    .replace(Regex("<[^>]*>"), "")
    .replace(Regex("\\{[^}]*\\}"), "")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    .trim()

/**
 * Everything the player knows: the video (Media3 ExoPlayer), captions, quality, speed and intro/outro skipping.
 * The controls (inline and fullscreen) only draw this and call its methods. All calls must be made on the main thread.
 */
class PlayerState(context: Context) {
    val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_NETWORK)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var released = false

    /* ---------- settings the watch screen and the menu share (remembered on the phone) ---------- */

    var autoplay by mutableStateOf(Prefs.getBool("player.autoplay", true))
    var autoNext by mutableStateOf(Prefs.getBool("player.autoNext", true))
    var autoSkip by mutableStateOf(Prefs.getBool("player.autoSkip", true))

    /** Called when the viewer flips one of the three switches in the menu. */
    var onPrefsChanged: (() -> Unit)? = null

    fun setAutoplay(v: Boolean) { autoplay = v; Prefs.putBool("player.autoplay", v); onPrefsChanged?.invoke() }
    fun setAutoNext(v: Boolean) { autoNext = v; Prefs.putBool("player.autoNext", v); onPrefsChanged?.invoke() }
    fun setAutoSkip(v: Boolean) { autoSkip = v; Prefs.putBool("player.autoSkip", v); onPrefsChanged?.invoke() }

    /* ---------- data from the page ---------- */

    var intro: TimeRange? = null
    var outro: TimeRange? = null
    var lengthHint: Double? = null

    /* ---------- callbacks ---------- */

    var onTime: ((position: Double, duration: Double) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onFail: (() -> Unit)? = null

    /* ---------- state drawn by the UI ---------- */

    var position by mutableDoubleStateOf(0.0)
        private set
    var duration by mutableDoubleStateOf(0.0)
        private set
    var bufferedFraction by mutableFloatStateOf(0f)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var controlsVisible by mutableStateOf(true)
        private set
    var seeking by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
        private set
    var levels by mutableStateOf<List<QualityLevel>>(emptyList())
        private set
    var quality by mutableStateOf(-1) // -1 = Auto
        private set
    var tracks by mutableStateOf<List<StreamSubtitle>>(emptyList())
        private set
    var track by mutableStateOf(-1)
        private set
    var caption by mutableStateOf("")
        private set
    var skipTarget by mutableStateOf<SkipKind?>(null)
        private set
    var skipToast by mutableStateOf<SkipToast?>(null)
        private set
    var menuOpen by mutableStateOf(false)
        private set
    var menuView by mutableStateOf("main")
        private set
    var muted by mutableStateOf(false)
        private set
    var seekFlash by mutableStateOf<Int?>(null) // -1 back, 1 forward (brief)
        private set
    var captionScale by mutableFloatStateOf(Prefs.getFloat("anibiar:capScale", 0.88f)) // Small
        private set
    var captionBox by mutableStateOf(Prefs.getBool("anibiar:capBox", false)) // Outline
        private set

    /* ---------- private bookkeeping ---------- */

    private var cues: List<Cue> = emptyList()
    private var trackGen = 0
    private val skipped = booleanArrayOf(false, false)
    private val inside = booleanArrayOf(false, false)
    private var endedSent = false
    private var lastReport = -10.0
    private var lastTouch = 0L
    private var toastJob: Job? = null
    private var flashJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                loading = false
                ready = true
            }
            if (state == Player.STATE_ENDED && !endedSent) {
                endedSent = true
                val d = if (exo.duration > 0) exo.duration / 1000.0 else duration
                position = d
                onTime?.invoke(d, d)
                onEnded?.invoke()
            }
            syncPlaying()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncPlaying()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && exo.playbackState != Player.STATE_ENDED) flush()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val best = HashMap<Int, QualityLevel>()
            for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    if (f.height <= 0) continue
                    val old = best[f.height]
                    if (old == null || f.bitrate > old.bitrate) best[f.height] = QualityLevel(f.height, g.mediaTrackGroup, i, f.bitrate)
                }
            }
            levels = best.values.sortedByDescending { it.height }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (failed) return
            failed = true
            loading = false
            onFail?.invoke()
        }
    }

    init {
        exo.addListener(listener)
        scope.launch {
            while (true) {
                tick()
                delay(250)
            }
        }
    }

    private fun syncPlaying() {
        val p = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.playbackState != Player.STATE_IDLE && !failed
        if (p != playing) {
            playing = p
            if (p) bump() else {
                controlsVisible = true
            }
        }
    }

    /* ---------------------------------- opening ---------------------------------- */

    /** Opens a new video. Intro/outro times, subtitles and the resume point come with it. */
    fun open(
        url: String,
        hls: Boolean,
        headers: Map<String, String>,
        subtitles: List<StreamSubtitle>,
        startAt: Double,
        intro: TimeRange?,
        outro: TimeRange?,
        length: Double?,
    ) {
        if (released) return
        this.intro = intro
        this.outro = outro
        lengthHint = length
        skipped[0] = false; skipped[1] = false
        inside[0] = false; inside[1] = false
        skipTarget = null
        skipToast = null
        endedSent = false
        lastReport = -10.0
        loading = true
        ready = false
        failed = false
        levels = emptyList()
        quality = -1
        tracks = subtitles
        track = -1
        cues = emptyList()
        caption = ""
        position = if (startAt > 1) startAt else 0.0
        duration = 0.0
        bufferedFraction = 0f

        val merged = LinkedHashMap(headers)
        subtitles.firstOrNull()?.headers?.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        val dataSource = OkHttpDataSource.Factory(Net.client).setDefaultRequestProperties(merged)
        val item = MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(item)

        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        exo.setMediaSource(source, if (startAt > 1) (startAt * 1000).toLong() else 0L)
        exo.setPlaybackSpeed(speed)
        exo.volume = if (muted) 0f else 1f
        exo.playWhenReady = autoplay
        exo.prepare()
        controlsVisible = true
        bump()

        // captions: the default one, else English, else the first
        var pick = subtitles.indexOfFirst { it.isDefault }
        if (pick < 0) {
            pick = subtitles.indexOfFirst {
                val l = "${it.label} ${it.lang}".lowercase()
                l.contains("english") || l.startsWith("en")
            }
        }
        if (pick < 0 && subtitles.isNotEmpty()) pick = 0
        setTrack(pick)
    }

    /* --------------------------------- quality --------------------------------- */

    fun setQuality(index: Int) {
        if (index == quality) return
        val params = exo.trackSelectionParameters.buildUpon()
        if (index < 0 || index >= levels.size) {
            params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            quality = -1
        } else {
            val level = levels[index]
            params.setOverrideForType(TrackSelectionOverride(level.group, level.index))
            quality = index
        }
        exo.trackSelectionParameters = params.build()
    }

    /* --------------------------------- captions --------------------------------- */

    fun setTrack(i: Int) {
        track = i
        cues = emptyList()
        caption = ""
        if (i < 0 || i >= tracks.size) return
        val gen = ++trackGen
        val t = tracks[i]
        scope.launch {
            val parsed = try {
                withContext(Dispatchers.IO) {
                    val res = Net.get(t.src, t.headers, 20_000)
                    if (res.ok) parseCues(res.body) else emptyList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (gen == trackGen && !released) cues = parsed
        }
    }

    private fun captionAt(p: Double): String {
        for (c in cues) if (p >= c.start && p <= c.end) return cleanCaption(c.text)
        return ""
    }

    fun setCaptionStyle(scale: Float? = null, box: Boolean? = null) {
        if (scale != null) {
            captionScale = scale
            Prefs.putFloat("anibiar:capScale", scale)
        }
        if (box != null) {
            captionBox = box
            Prefs.putBool("anibiar:capBox", box)
        }
    }

    /* ---------------------------------- controls ---------------------------------- */

    fun togglePlay() {
        if (exo.playbackState == Player.STATE_ENDED) {
            endedSent = false
            exo.seekTo(0)
            exo.play()
        } else if (exo.playWhenReady) {
            exo.pause()
        } else {
            exo.play()
        }
        bump()
    }

    fun seekTo(seconds: Double) {
        val max = if (duration > 0) duration else seconds
        val t = seconds.coerceIn(0.0, max.coerceAtLeast(0.0))
        exo.seekTo((t * 1000).toLong())
        position = t
        if (exo.playbackState == Player.STATE_ENDED || t < duration - 1) endedSent = false
    }

    fun seekBy(delta: Double) = seekTo(position + delta)

    fun setSpeed(s: Float) {
        speed = s
        exo.setPlaybackSpeed(s)
    }

    fun toggleMute() {
        muted = !muted
        exo.volume = if (muted) 0f else 1f
        bump()
    }

    fun flash(dir: Int) {
        seekFlash = dir
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(550)
            seekFlash = null
        }
    }

    fun openMenu() {
        menuView = "main"
        menuOpen = true
        controlsVisible = true
    }

    fun closeMenu() {
        menuOpen = false
        bump()
    }

    fun setMenuView(v: String) {
        menuView = v
    }

    /* ------------------------------ controls visibility ------------------------------ */

    fun showControls() {
        controlsVisible = true
        bump()
    }

    fun hideControls() {
        controlsVisible = false
    }

    fun toggleControls() = if (controlsVisible) hideControls() else showControls()

    /** Restarts the auto-hide timer (controls hide 3.5 s after the last touch while playing). */
    private fun bump() {
        lastTouch = System.currentTimeMillis()
    }

    fun touch() = bump()

    /* ----------------------------------- skipping ----------------------------------- */

    private fun rangeOf(k: SkipKind): TimeRange? = if (k == SkipKind.INTRO) intro else outro

    fun skipTo(kind: SkipKind, automatic: Boolean) {
        val r = rangeOf(kind) ?: return
        if (!ready) return
        val from = position
        skipped[kind.ordinal] = true
        skipTarget = null
        seekTo(r.end)
        if (automatic) {
            skipToast = SkipToast(kind, from)
            toastJob?.cancel()
            toastJob = scope.launch {
                delay(5000)
                skipToast = null
            }
        }
    }

    fun undoSkip() {
        val t = skipToast ?: return
        seekTo(t.from)
        skipToast = null
    }

    private fun checkSkip() {
        val len = duration
        // Times made for a differently cut video would skip the wrong part: ignore them.
        fun isInside(r: TimeRange?) = r != null && position >= r.start && position < r.end && !(len > 0 && r.end > len + 1)
        val now = booleanArrayOf(isInside(intro), isInside(outro))
        for (k in SkipKind.values()) {
            val i = k.ordinal
            // The viewer seeked into the range by hand: never auto-skip it, but keep the Skip button.
            if (now[i] && !inside[i] && position - (rangeOf(k)?.start ?: 0.0) > 2) skipped[i] = true
            inside[i] = now[i]
        }
        val kind = if (now[0]) SkipKind.INTRO else if (now[1]) SkipKind.OUTRO else null
        if (kind != null && autoSkip && !skipped[kind.ordinal] && playing) {
            skipTo(kind, automatic = true)
            return
        }
        if (kind != skipTarget) skipTarget = kind
    }

    /* ------------------------------------- tick ------------------------------------- */

    private fun tick() {
        if (released) return
        val pos = exo.currentPosition / 1000.0
        val dur = if (exo.duration > 0) exo.duration / 1000.0 else 0.0
        position = pos
        if (dur != duration) duration = dur
        if (dur > 0) bufferedFraction = (exo.bufferedPosition / 1000.0 / dur).coerceIn(0.0, 1.0).toFloat()
        val c = if (track >= 0) captionAt(pos) else ""
        if (c != caption) caption = c
        checkSkip()
        if (abs(pos - lastReport) >= 5 && dur > 0 && playing) {
            lastReport = pos
            onTime?.invoke(pos, dur)
        }
        if (controlsVisible && playing && !seeking && !menuOpen && System.currentTimeMillis() - lastTouch > 3500) controlsVisible = false
    }

    /** Reports where the viewer is (also called when leaving the page). */
    fun flush() {
        if (released || !ready) return
        val d = if (exo.duration > 0) exo.duration / 1000.0 else duration
        if (d > 0) onTime?.invoke(exo.currentPosition / 1000.0, d)
    }

    /** Pauses when the app goes to the background. */
    fun pause() {
        if (!released) exo.pause()
    }

    fun release() {
        if (released) return
        flush()
        released = true
        scope.cancel()
        exo.removeListener(listener)
        exo.release()
    }
}
