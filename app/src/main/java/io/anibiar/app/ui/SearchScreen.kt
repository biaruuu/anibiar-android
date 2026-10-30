package io.anibiar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anibiar.app.core.friendlyError
import io.anibiar.app.data.AniList
import io.anibiar.app.data.Anime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(focusSignal: Int) {
    val perPage = 24
    val top = statusBarHeight()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var text by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") } // the search that is shown
    var items by remember { mutableStateOf<List<Anime>?>(null) }
    var trending by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var hasNext by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try { trending = AniList.trending() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    // opening the tab focuses the field
    LaunchedEffect(focusSignal) {
        if (focusSignal > 0) {
            delay(120)
            try { focus.requestFocus() } catch (_: Exception) {}
            keyboard?.show()
        }
    }

    // search 350 ms after the last key
    LaunchedEffect(text, retry) {
        val t = text.trim()
        if (t.isEmpty()) {
            q = ""
            error = null
            items = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        q = t
        error = null
        page = 1
        items = null
        try {
            val r = AniList.searchPage(t, 1, perPage)
            items = r.items
            hasNext = r.hasNext
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }

    Column(
        Modifier.fillMaxSize().background(C.bg)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = top + 14.dp, bottom = 110.dp + navBarHeight()),
    ) {
        // search field
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = C.w(0.7f))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (text.isEmpty()) Text("Search anime…", color = C.w(0.4f), fontSize = 16.sp)
            }
            if (text.isNotEmpty()) {
                Box(
                    Modifier.size(32.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { text = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "Clear", tint = C.w(0.7f)) }
            }
        }
        Spacer(Modifier.height(22.dp))

        val list = items
        val err = error
        if (q.isEmpty() && text.trim().isEmpty()) {
            if (trending.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TrendingUp, null, Modifier.size(16.dp), tint = C.w(0.5f))
                    Spacer(Modifier.width(6.dp))
                    Text("Trending now", fontSize = 12.sp, color = C.w(0.5f))
                }
                Spacer(Modifier.height(12.dp))
                trending.forEach { TrendingRow(it) }
            }
        } else if (err != null) {
            ErrorBox(err, onRetry = { retry++ })
        } else if (list == null) {
            PosterGridSkeleton(9)
        } else if (list.isEmpty()) {
            Text("No results for \"$q\".", Modifier.fillMaxWidth().padding(vertical = 60.dp), textAlign = TextAlign.Center, color = C.w(0.6f))
        } else {
            Text("Results for \"$q\"", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            PosterGrid(list, sidePadding = 32.dp) { a ->
                val eps = a.aired
                PosterTile(
                    title = a.name,
                    image = a.thumb,
                    onClick = { Nav.openInfo(a.id) },
                    chips = {
                        if (eps > 0) Chip2("$eps", icon = Icons.Rounded.ClosedCaption)
                        if (a.averageScore != null) Chip2("${a.averageScore}", icon = Icons.Rounded.Star)
                    },
                )
            }
            if (hasNext) {
                Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                    FlatButton("Load more", busy = loadingMore, height = 42.dp) {
                        scope.launch {
                            loadingMore = true
                            try {
                                val r = AniList.searchPage(q, page + 1, perPage)
                                items = (items ?: emptyList()) + r.items
                                hasNext = r.hasNext
                                page++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = friendlyError(e)
                            } finally {
                                loadingMore = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendingRow(a: Anime) {
    Row(
        Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Nav.openInfo(a.id) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(a.thumb, Modifier.width(48.dp), radius = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontSize = 14.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val meta = listOfNotNull(a.formatLabel, a.seasonYear?.toString(), a.episodes?.let { "$it eps" }).joinToString(" · ")
            Text(meta, fontSize = 12.sp, color = C.w(0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
