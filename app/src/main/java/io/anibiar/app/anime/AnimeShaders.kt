package io.anibiar.app.anime

import android.content.Context
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import kotlin.math.max
import kotlin.math.min

/** What the picture is getting right now, for the badge and the menu. */
data class AnimeEnhancementStatus(
    val lineSharpen: Boolean,
    val upscale: Boolean,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    val summary: String
        get() = buildList {
            if (upscale) add("${min(sourceWidth, sourceHeight)}p → ${min(outputWidth, outputHeight)}p")
            if (lineSharpen) add("sharper lines")
        }.joinToString(" · ")
}

/**
 * Anime4K (MIT, https://github.com/bloc97/Anime4K) running as an ExoPlayer video effect:
 * restore/darken/thin lines, a 2x CNN upscale, and a final line sharpen. Ported from NuvioMobile-Enhanced.
 */
@UnstableApi
internal object AnimeShaders {
    private const val TAG = "AnimeShaders"
    private const val DIR = "anime4k"
    private const val RESTORE = "Anime4K_Restore_CNN_S.glsl"
    private const val DARKEN = "Anime4K_Darken_HQ.glsl"
    private const val THIN = "Anime4K_Thin_HQ.glsl"
    private const val UPSCALE = "Anime4K_Upscale_CNN_x2_S.glsl"
    private const val FINAL = "AniBiar_Line_Sharpen.glsl"
    private const val UPSCALE_CONDITION = "//!WHEN OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *"

    /** 1440p is the least we aim for, so a 720p/1080p show is sharpened even on a 1080p phone. */
    const val MINIMUM_UPSCALE_LONG_SIDE = 2560
    const val MINIMUM_UPSCALE_SHORT_SIDE = 1440

    private val cache = HashMap<String, List<MpvUserShaderPass>>()

    @Synchronized
    private fun shader(context: Context, name: String): List<MpvUserShaderPass> = cache.getOrPut(name) {
        var src = context.assets.open("$DIR/$name").bufferedReader().use { it.readText() }
        if (name == UPSCALE) {
            val condition = "$UPSCALE_CONDITION " +
                "$MINIMUM_UPSCALE_LONG_SIDE MAIN.w / 1.200 > $MINIMUM_UPSCALE_SHORT_SIDE MAIN.h / 1.200 > * + " +
                "$MINIMUM_UPSCALE_SHORT_SIDE MAIN.w / 1.200 > $MINIMUM_UPSCALE_LONG_SIDE MAIN.h / 1.200 > * + 0 >"
            src = src.replace(UPSCALE_CONDITION + "\n", condition + "\n")
        }
        MpvUserShaderParser.parse(src)
    }

    fun effects(context: Context, upscale: Boolean, sharpen: Boolean, tracker: AnimeShaderStatusTracker?): List<Effect> {
        if (!upscale && !sharpen) return emptyList()
        return try {
            val restore = if (sharpen) shader(context, RESTORE) + shader(context, DARKEN) + shader(context, THIN) else emptyList()
            val up = if (upscale) shader(context, UPSCALE) else emptyList()
            val fin = if (sharpen) shader(context, FINAL) else emptyList()
            val m = context.resources.displayMetrics
            listOf<Effect>(
                AnimeShaderEffect(
                    passes = restore + up + fin,
                    sharpenPasses = restore + fin,
                    upscalePasses = up,
                    displayLongSide = max(m.widthPixels, m.heightPixels),
                    displayShortSide = min(m.widthPixels, m.heightPixels),
                    statusTracker = tracker,
                ),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Anime4K could not be prepared", e)
            emptyList()
        }
    }
}
