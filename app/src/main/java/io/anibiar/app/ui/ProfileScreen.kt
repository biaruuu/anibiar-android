package io.anibiar.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anibiar.app.R
import io.anibiar.app.core.friendlyError
import io.anibiar.app.core.jsonOf
import io.anibiar.app.data.Auth
import io.anibiar.app.data.Collection
import io.anibiar.app.data.HistoryItem
import io.anibiar.app.data.Library
import io.anibiar.app.data.ListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/* ------------------------------------------------ shared bits (also used by Settings and Login) ------------------------------------------------ */

/** A flat text field: solid surface, hairline border that brightens on focus. */
@Composable
internal fun PField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    leading: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    autoFocus: Boolean = false,
    radius: Dp = 12.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    val shape = RoundedCornerShape(radius)
    Row(
        modifier.fillMaxWidth().clip(shape).background(C.surface, shape)
            .border(1.dp, if (focused) C.w(0.6f) else C.line, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            Icon(leading, null, Modifier.size(20.dp), tint = C.w(0.5f))
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = C.w(0.4f), fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= maxLength) onChange(it) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else minLines,
                textStyle = TextStyle(color = if (enabled) Color.White else C.w(0.5f), fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }, onSend = { onDone?.invoke() }),
            )
        }
    }
}

/** Small uppercase caption above a field. */
@Composable
internal fun PLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(bottom = 8.dp), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = C.w(0.6f))
}

/** Red box for an error message. */
@Composable
internal fun PError(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        modifier.fillMaxWidth().clip(shape).background(Color(0x1AEF4444), shape).border(1.dp, Color(0x4DF87171), shape).padding(horizontal = 14.dp, vertical = 10.dp),
        fontSize = 12.5.sp, color = Color(0xFFFCA5A5),
    )
}

/** Neutral box for an information message. */
@Composable
internal fun PInfo(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        modifier.fillMaxWidth().clip(shape).background(C.w(0.05f), shape).border(1.dp, C.line, shape).padding(horizontal = 14.dp, vertical = 10.dp),
        fontSize = 12.5.sp, color = C.w(0.85f),
    )
}

/** Bottom sheet in the app's flat style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = C.surface,
        contentColor = Color.White,
        scrimColor = C.k(0.72f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Box(Modifier.imePadding().navigationBarsPadding()) { content() }
    }
}

/* ------------------------------------------------ image helpers ------------------------------------------------ */

private class Picked(val bytes: ByteArray, val ext: String, val preview: ImageBitmap?)

private fun extOf(mime: String?): String = when (mime) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "jpg"
}

/** Reads the chosen picture; big pictures are scaled down (GIFs are kept as they are so they stay animated). */
private suspend fun readPicked(ctx: android.content.Context, uri: Uri, maxDim: Int): Picked? = withContext(Dispatchers.IO) {
    try {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val ext = extOf(ctx.contentResolver.getType(uri))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val bmp: Bitmap? = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val preview = bmp?.asImageBitmap()
        if (ext == "gif" || bmp == null) return@withContext Picked(raw, ext, preview)
        val out = ByteArrayOutputStream()
        val png = ext == "png"
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
        Picked(out.toByteArray(), if (png) "png" else "jpg", preview)
    } catch (_: Exception) {
        null
    }
}

/* ------------------------------------------------ the screen ------------------------------------------------ */

private fun joinedDate(): String? = null // the account's creation date is not part of the session data

@Composable
fun ProfileScreen() {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var favorites by remember { mutableStateOf<List<ListItem>?>(null) }
    var list by remember { mutableStateOf<List<ListItem>?>(null) }
    var editing by remember { mutableStateOf(false) }
    val decos = rememberDecos()

    suspend fun reload() {
        history = Library.loadHistory()
        favorites = Library.load(Collection.FAVORITES)
        list = Library.load(Collection.LIST)
    }

    // when the account changes, and again whenever a screen opened on top (the player) is closed
    LaunchedEffect(Auth.user?.id, Nav.stack.size) {
        if (Nav.stack.isEmpty()) reload()
    }

    val width = screenWidth()
    val avatarSize = if (width < 360.dp) 80.dp else 96.dp
    val top = statusBarHeight()
    val tabs = listOf("History" to history?.size, "Favorites" to favorites?.size, "Watchlist" to list?.size)

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(bottom = 96.dp + navBarHeight())) {
        // banner
        Box(Modifier.fillMaxWidth().height(140.dp + top).background(C.w(0.08f))) {
            Auth.banner?.let { Img(it, Modifier.fillMaxSize()) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Box(Modifier.offset(y = -(avatarSize / 2))) {
                DecoratedAvatar(size = avatarSize, deco = findDeco(Auth.decoration, decos)) {
                    AvatarPicture(if (Auth.signedIn) Auth.avatar else null, Modifier.fillMaxSize(), avatarSize)
                }
            }
            Column(Modifier.offset(y = -(avatarSize / 2 - 12.dp))) {
                Text(
                    if (Auth.signedIn) (Auth.name ?: "") else "Guest",
                    fontSize = if (width < 360.dp) 24.sp else 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (Auth.signedIn) (Auth.handle ?: "") else "Log in to set an avatar and banner and keep your lists on every device.",
                    fontSize = 15.sp, color = C.w(0.7f),
                )
                Spacer(Modifier.height(16.dp))
                if (Auth.signedIn) {
                    FlatButton("Edit profile", Modifier.fillMaxWidth(), icon = Icons.Rounded.Edit, kind = Kind.Accent, height = 42.dp, radius = 12.dp) { editing = true }
                } else if (Auth.ready) {
                    FlatButton("Log in / Sign up", Modifier.fillMaxWidth(), kind = Kind.White, height = 44.dp, radius = 12.dp) { Nav.openLogin() }
                }
                if (Auth.signedIn) {
                    Spacer(Modifier.height(22.dp))
                    Text("Bio", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    val bio = Auth.profile?.bio.orEmpty()
                    Text(if (bio.isEmpty()) "No bio yet." else bio, fontSize = 15.sp, lineHeight = 21.sp, color = if (bio.isEmpty()) C.w(0.4f) else C.w(0.8f))
                    joinedDate()?.let { joined ->
                        Spacer(Modifier.height(18.dp))
                        Text("Member Since", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
                            Spacer(Modifier.width(10.dp))
                            Text(joined, fontSize = 15.sp, color = C.w(0.8f))
                        }
                    }
                }
            }
        }
        // tabs
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            tabs.forEachIndexed { i, (label, count) ->
                Box(
                    Modifier.weight(1f).height(46.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label + (count?.let { " $it" } ?: ""),
                        fontSize = if (width < 360.dp) 12.5.sp else 14.sp, fontWeight = FontWeight.Bold,
                        color = if (tab == i) Color.White else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(if (tab == i) Color.White else Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
            TabBody(
                tab = tab, history = history, favorites = favorites, list = list,
                onHistory = { history = it }, onFavorites = { favorites = it }, onList = { list = it },
                onRetry = { scope.launch { reload() } },
            )
        }
    }

    if (editing) EditProfileSheet(onDismiss = { editing = false })
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = C.w(0.6f))
    }
}

@Composable
private fun TabBody(
    tab: Int,
    history: List<HistoryItem>?,
    favorites: List<ListItem>?,
    list: List<ListItem>?,
    onHistory: (List<HistoryItem>) -> Unit,
    onFavorites: (List<ListItem>) -> Unit,
    onList: (List<ListItem>) -> Unit,
    onRetry: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (tab == 0) {
        val items = history ?: return PosterGridSkeleton(6)
        if (items.isEmpty()) return Empty("Nothing here yet. Start watching something.")
        val cols = if (screenWidth() < 600.dp) 2 else 3
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(cols).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    rowItems.forEach { h ->
                        Box(Modifier.weight(1f)) {
                            HistoryCard(
                                h = h,
                                onClick = { Nav.openWatch(h.animeId, h.episode, h.dub) },
                                onRemove = {
                                    onHistory(items.filter { it.animeId != h.animeId })
                                    scope.launch { Library.removeHistory(h.animeId) }
                                },
                            )
                        }
                    }
                    repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        return
    }
    val favTab = tab == 1
    val items = (if (favTab) favorites else list) ?: return PosterGridSkeleton(6)
    if (items.isEmpty()) return Empty(if (favTab) "No favorites yet." else "Your watchlist is empty.")
    PosterGrid(items, sidePadding = 32.dp) { it2 ->
        PosterTile(
            title = it2.title,
            image = it2.image,
            onClick = { Nav.openInfo(it2.animeId) },
            onRemove = {
                val rest = items.filter { x -> x.animeId != it2.animeId }
                if (favTab) onFavorites(rest) else onList(rest)
                scope.launch { Library.toggle(if (favTab) Collection.FAVORITES else Collection.LIST, it2.animeId, it2.title, it2.image) }
            },
        )
    }
}

/* ------------------------------------------------ edit profile sheet ------------------------------------------------ */

@Composable
private fun EditProfileSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val decos = rememberDecos()
    var name by remember { mutableStateOf(Auth.profile?.username ?: Auth.name ?: "") }
    var bio by remember { mutableStateOf(Auth.profile?.bio ?: "") }
    var deco by remember { mutableStateOf(Auth.decoration) }
    var avatarPick by remember { mutableStateOf<Picked?>(null) }
    var bannerPick by remember { mutableStateOf<Picked?>(null) }
    var dropAvatar by remember { mutableStateOf(false) }
    var dropBanner by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val p = readPicked(ctx, uri, 1024)
            if (p == null) error = "Couldn't read that picture." else { avatarPick = p; dropAvatar = false }
        }
    }
    val bannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val p = readPicked(ctx, uri, 2048)
            if (p == null) error = "Couldn't read that picture." else { bannerPick = p; dropBanner = false }
        }
    }

    fun save() {
        val n = name.trim()
        if (n.isEmpty()) { error = "Enter a display name."; return }
        busy = true
        error = null
        scope.launch {
            try {
                val patch = jsonOf("username" to n, "bio" to bio.trim(), "decoration" to deco)
                val oldAvatar = Auth.profile?.avatarUrl
                val oldBanner = Auth.profile?.bannerUrl
                val a = avatarPick
                val b = bannerPick
                if (a != null) patch.put("avatar_url", Auth.uploadImage(a.bytes, a.ext, "avatar"))
                else if (dropAvatar) patch.put("avatar_url", JSONObject.NULL)
                if (b != null) patch.put("banner_url", Auth.uploadImage(b.bytes, b.ext, "banner"))
                else if (dropBanner) patch.put("banner_url", JSONObject.NULL)
                Auth.saveProfile(patch)
                if (patch.has("avatar_url")) Auth.deleteImage(oldAvatar)
                if (patch.has("banner_url")) Auth.deleteImage(oldBanner)
                onDismiss()
            } catch (e: Exception) {
                error = friendlyError(e)
            }
            busy = false
        }
    }

    PSheet(onDismiss = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Edit profile", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                FlatIconButton(Icons.Rounded.Close, { if (!busy) onDismiss() }, size = 36.dp, iconSize = 18.dp)
            }
            Spacer(Modifier.height(14.dp))
            // preview
            val avatarUrl = if (dropAvatar) null else Auth.avatar
            val bannerUrl = if (dropBanner) null else Auth.banner
            Box(Modifier.fillMaxWidth().height(110.dp + 38.dp)) {
                Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp)).background(C.w(0.08f))) {
                    val bp = bannerPick?.preview
                    if (bp != null) Image(bp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else bannerUrl?.let { Img(it, Modifier.fillMaxSize()) }
                }
                DecoratedAvatar(size = 76.dp, deco = findDeco(deco, decos), modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp)) {
                    val ap = avatarPick?.preview
                    if (ap != null) Image(ap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else AvatarPicture(avatarUrl, Modifier.fillMaxSize(), 76.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    PLabel("Avatar")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlatButton("Change", icon = Icons.Rounded.PhotoCamera, height = 38.dp, fontSize = 13, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), enabled = !busy) { avatarLauncher.launch("image/*") }
                        if (avatarPick != null || Auth.avatar != null) {
                            Spacer(Modifier.width(8.dp))
                            FlatIconButton(Icons.Rounded.DeleteOutline, { if (!busy) { avatarPick = null; dropAvatar = true } }, size = 38.dp, iconSize = 19.dp)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    PLabel("Banner · GIFs work")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlatButton("Change", icon = Icons.Rounded.AddPhotoAlternate, height = 38.dp, fontSize = 13, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), enabled = !busy) { bannerLauncher.launch("image/*") }
                        if (bannerPick != null || Auth.banner != null) {
                            Spacer(Modifier.width(8.dp))
                            FlatIconButton(Icons.Rounded.DeleteOutline, { if (!busy) { bannerPick = null; dropBanner = true } }, size = 38.dp, iconSize = 19.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            PLabel("Avatar decoration")
            DecorationPicker(value = deco, onChange = { deco = it })
            Spacer(Modifier.height(18.dp))
            PLabel("Display name")
            PField(name, { name = it }, "Your name", enabled = !busy, maxLength = 32)
            Spacer(Modifier.height(14.dp))
            PLabel("Bio")
            PField(bio, { bio = it }, "Tell people what you watch", enabled = !busy, singleLine = false, minLines = 3, maxLength = 190)
            error?.let {
                Spacer(Modifier.height(10.dp))
                PError(it)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FlatButton("Cancel", height = 44.dp, enabled = !busy) { onDismiss() }
                Spacer(Modifier.width(10.dp))
                FlatButton("Save", kind = Kind.White, height = 44.dp, busy = busy, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp)) { save() }
            }
        }
    }
}
