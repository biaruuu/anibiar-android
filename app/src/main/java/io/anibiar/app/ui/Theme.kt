package io.anibiar.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.anibiar.app.R
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Discord "Onyx" dark theme, the same tokens as the website. Flat surfaces only: solid fills, hairline borders, no gradients. */
object C {
    val bg = Color(0xFF000000)
    val fg = Color(0xFFF2F3F5)
    val card = Color(0xFF0B0B0D)
    val secondary = Color(0xFF141416)
    val muted = Color(0xFF949BA4)
    val border = Color(0xFF26272B)
    val input = Color(0xFF2B2D31)
    val accent = Color(0xFF5865F2)
    val lime = Color(0xFFBEF264)
    val red = Color(0xFFDC2626)
    val green = Color(0xFF22C55E)
    val orange = Color(0xFFFB923C)

    /** Flat Onyx surfaces, from the page (black) upwards. */
    val surface = Color(0xFF0D0D0F)
    val raised = Color(0xFF17171A)
    val line = Color(0x1FFFFFFF)

    /** White with an alpha (0..1), like Tailwind's bg-white/10. */
    fun w(a: Float): Color = Color.White.copy(alpha = a)

    /** Black with an alpha (0..1). */
    fun k(a: Float): Color = Color.Black.copy(alpha = a)
}

/** Inter (variable font, SIL OFL), the same typeface family as Miruro. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = FontFamily(
    listOf(400, 500, 600, 700, 800, 900).map { w ->
        Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

@Composable
fun AniBiarTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Inter, letterSpacing = (-0.1).sp)) {
        ThemeBody(content)
    }
}

@Composable
private fun ThemeBody(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.fg,
            onPrimary = Color.Black,
            secondary = C.accent,
            background = C.bg,
            onBackground = C.fg,
            surface = C.card,
            onSurface = C.fg,
            error = Color(0xFFF87171),
        ),
        content = content,
    )
}

/** Width of the screen in dp, for layouts that adapt (narrow phones, wide phones, tablets). */
@Composable
fun screenWidth(): Dp = LocalConfiguration.current.screenWidthDp.dp

@Composable
fun statusBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
fun navBarHeight(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
