package io.anibiar.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anibiar.app.data.Auth
import kotlinx.coroutines.delay

/** The whole app: the four tabs, the screens opened on top of them and the toast. */
@Composable
fun Root() {
    val stack = Nav.stack
    val top = stack.lastOrNull()
    BackHandler(enabled = stack.isNotEmpty()) { Nav.back() }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Shell()
        // one screen on top at a time; the tabs stay alive underneath so their scroll position is kept
        AnimatedVisibility(visible = top != null, enter = fadeIn() + slideInVertically { it / 24 }, exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(C.bg).blockTouches()) {
                // keep showing the last screen while it fades out
                var shown by remember { mutableStateOf<Route?>(null) }
                if (top != null) shown = top
                when (val r = shown) {
                    is Route.Info -> InfoScreen(id = r.id, onBack = { Nav.back() })
                    is Route.Watch -> WatchScreen(id = r.id, episode = r.episode, dub = r.dub, onBack = { Nav.back() })
                    Route.Login -> LoginScreen(onBack = { Nav.back() })
                    null -> {}
                }
            }
        }
        ToastHost(Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ToastHost(modifier: Modifier) {
    val msg = Toasts.message
    LaunchedEffect(Toasts.serial) {
        if (Toasts.message != null) {
            delay(3000)
            Toasts.clear()
        }
    }
    AnimatedVisibility(visible = msg != null, modifier = modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        var last by remember { mutableStateOf("") }
        if (msg != null) last = msg
        Box(
            Modifier.padding(horizontal = 16.dp).padding(bottom = navBarHeight() + 84.dp).widthIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B1B1F)).border(1.dp, C.line, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(last, color = C.fg, fontSize = 14.sp)
        }
    }
}

private class Tab(val label: String, val on: ImageVector, val off: ImageVector)

@Composable
fun Shell() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searchSignal by remember { mutableIntStateOf(0) }

    fun go(i: Int) {
        if (i == 2 && !Auth.signedIn && Auth.ready) {
            Nav.openLogin()
            return
        }
        tab = i
        if (i == 1) searchSignal++
    }

    BackHandler(enabled = tab != 0 && Nav.stack.isEmpty()) { tab = 0 }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        // all four tabs stay composed (so scroll and typed text survive); only the current one is visible
        Box(Modifier.fillMaxSize()) {
            TabLayer(tab == 0) { HomeScreen(onSearch = { go(1) }) }
            TabLayer(tab == 1) { SearchScreen(focusSignal = searchSignal) }
            TabLayer(tab == 2) { ProfileScreen() }
            TabLayer(tab == 3) { SettingsScreen() }
        }

        val tabs = listOf(
            Tab("Home", Icons.Rounded.Home, Icons.Outlined.Home),
            Tab("Search", Icons.Rounded.Search, Icons.Rounded.Search),
            Tab("Profile", Icons.Outlined.PersonOutline, Icons.Outlined.PersonOutline),
            Tab("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp + navBarHeight()), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = 380.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(999.dp))
                    .background(Color(0xF2111113), RoundedCornerShape(999.dp)).border(1.dp, C.line, RoundedCornerShape(999.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tabs.forEachIndexed { i, t ->
                    val on = tab == i
                    Column(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(999.dp))
                            .background(if (on) C.w(0.12f) else Color.Transparent)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { go(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val tint = if (on) Color.White else C.w(0.75f)
                        if (i == 2 && Auth.signedIn && Auth.avatar != null) {
                            Avatar(url = Auth.avatar, size = 22.dp)
                        } else {
                            Icon(if (on) t.on else t.off, t.label, Modifier.size(22.dp), tint = tint)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(t.label, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabLayer(visible: Boolean, content: @Composable () -> Unit) {
    // A hidden tab stays composed (its scroll position and typed text survive) but is measured to zero size and never placed,
    // so it is not drawn and takes no touches.
    val hide = Modifier.layout { measurable, constraints ->
        measurable.measure(constraints)
        layout(0, 0) {}
    }
    Box(Modifier.fillMaxSize().then(if (visible) Modifier else hide)) { content() }
}
