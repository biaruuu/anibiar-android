package io.anibiar.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anibiar.app.core.Config
import io.anibiar.app.core.DebugLog
import io.anibiar.app.core.Net
import io.anibiar.app.core.friendlyError
import io.anibiar.app.data.Auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Field { Username, Name, Email }

private class Probe(val name: String, val text: String, val ok: Boolean)

private val handlePattern = Regex("^[a-z0-9_.]{3,20}$")
private val emailPattern = Regex("^\\S+@\\S+\\.\\S+$")

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Field?>(null) }
    var initial by remember { mutableStateOf("") }

    fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toasts.show("Couldn't open the link.")
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = statusBarHeight() + 16.dp, bottom = navBarHeight() + 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(20.dp))

                if (Auth.ready && !Auth.signedIn) {
                    Panel(padding = PaddingValues(22.dp)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Log in to manage your account.", color = C.w(0.8f), fontSize = 14.sp)
                            Spacer(Modifier.height(14.dp))
                            FlatButton("Log in / Sign up", kind = Kind.White, height = 44.dp) { Nav.openLogin() }
                        }
                    }
                } else {
                    val rows = listOf(
                        Triple(Field.Username, "Username", Auth.handle ?: ""),
                        Triple(Field.Name, "Display Name", Auth.name ?: ""),
                        Triple(Field.Email, "Email", Auth.user?.email ?: ""),
                    )
                    Caption("MY ACCOUNT")
                    Panel(padding = PaddingValues(0.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            rows.forEachIndexed { i, (field, label, value) ->
                                if (i > 0) Divider()
                                Row(
                                    Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { initial = value; editing = field }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, fontSize = 12.5.sp, color = C.w(0.55f))
                                        Spacer(Modifier.height(3.dp))
                                        Text(value.ifEmpty { "—" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    FlatButton("Edit", height = 34.dp, fontSize = 12, padding = PaddingValues(horizontal = 16.dp)) { initial = value; editing = field }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    FlatButton("Log out", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout, kind = Kind.Danger, height = 46.dp) {
                        scope.launch {
                            Auth.signOut()
                            Toasts.show("Logged out")
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
                Caption("ABOUT")
                Panel(padding = PaddingValues(0.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        LinkRow("Open the website") { openUrl(Config.siteUrl) }
                        Divider()
                        LinkRow("Terms of Service") { openUrl("${Config.siteUrl}/terms") }
                        Divider()
                        LinkRow("Privacy Policy") { openUrl("${Config.siteUrl}/privacy") }
                    }
                }

                Spacer(Modifier.height(28.dp))
                ConnectionReport()

                Spacer(Modifier.height(22.dp))
                Text(
                    "AniBiar for Android · v2.0",
                    Modifier.fillMaxWidth(), fontSize = 12.sp, color = C.w(0.4f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    editing?.let { f ->
        PSheet(onDismiss = { editing = null }) { EditSheet(f, initial) { editing = null } }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, Modifier.padding(bottom = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = C.w(0.5f))
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.w(0.12f)))
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = C.w(0.5f))
    }
}

/* ------------------------------------------------ edit sheet ------------------------------------------------ */

@Composable
private fun EditSheet(field: Field, initial: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    val title: String
    val hint: String
    val max: Int
    when (field) {
        Field.Username -> { title = "Change your username"; hint = "Use 3 to 20 letters, numbers, dots or underscores."; max = 20 }
        Field.Name -> { title = "Change your display name"; hint = "This is the name people see on your profile."; max = 32 }
        Field.Email -> { title = "Change your email"; hint = "We will send a confirmation link to the new address."; max = 100 }
    }

    fun save() {
        val v = text.trim()
        error = null
        busy = true
        scope.launch {
            try {
                when (field) {
                    Field.Username -> {
                        val handle = v.lowercase()
                        if (!handlePattern.matches(handle)) throw Exception("Use 3 to 20 letters, numbers, dots or underscores.")
                        Auth.saveProfile(org.json.JSONObject().put("handle", handle))
                        onClose()
                    }
                    Field.Name -> {
                        if (v.isEmpty()) throw Exception("Enter a display name.")
                        Auth.saveProfile(org.json.JSONObject().put("username", v))
                        onClose()
                    }
                    Field.Email -> {
                        if (!emailPattern.matches(v)) throw Exception("Enter a valid email address.")
                        if (v.equals(Auth.user?.email ?: "", ignoreCase = true)) throw Exception("That is already your email.")
                        Auth.changeEmail(v)
                        done = "Check your inbox. Open the confirmation link to finish changing your email."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Icon(
                Icons.Rounded.Close, "Close",
                Modifier.size(24.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
                tint = C.w(0.8f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(hint, fontSize = 13.5.sp, lineHeight = 19.sp, color = C.w(0.55f))
        Spacer(Modifier.height(16.dp))
        PField(
            value = text, onChange = { text = it }, hint = "", enabled = !busy && done == null, maxLength = max,
            keyboard = if (field == Field.Email) KeyboardType.Email else KeyboardType.Text,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = { if (!busy && done == null) save() }, autoFocus = true,
        )
        error?.let { PError(it, Modifier.padding(top = 12.dp)) }
        done?.let { PInfo(it, Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FlatButton(if (done != null) "Close" else "Cancel", height = 44.dp) { onClose() }
            if (done == null) {
                Spacer(Modifier.width(10.dp))
                FlatButton("Save", kind = Kind.White, height = 44.dp, busy = busy, padding = PaddingValues(horizontal = 28.dp)) { save() }
            }
        }
    }
}

/* ------------------------------------------------ connection report ------------------------------------------------ */

private suspend fun probe(name: String, call: suspend () -> Int): Probe {
    val t = System.currentTimeMillis()
    return try {
        val code = call()
        Probe(name, "HTTP $code · ${System.currentTimeMillis() - t} ms", code in 200..299)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Probe(name, "failed after ${System.currentTimeMillis() - t} ms · ${e.javaClass.simpleName}: ${e.message ?: ""}".take(160), false)
    }
}

@Composable
private fun ConnectionReport() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val results = remember { mutableStateListOf<Probe>() }
    var running by remember { mutableStateOf(false) }

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("AniBiar connection report")
        sb.appendLine("Site: ${Config.siteUrl}")
        sb.appendLine("Streaming API: ${Config.anivexaUrl}")
        sb.appendLine("Upstash cache: ${if (Config.upstashOn) "on" else "off"}")
        sb.appendLine("TMDB logos: ${if (Config.tmdbOn) "on" else "off"}")
        if (results.isNotEmpty()) {
            sb.appendLine()
            results.forEach { sb.appendLine("${if (it.ok) "OK  " else "FAIL"} ${it.name}: ${it.text}") }
        }
        if (DebugLog.lines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Recent calls:")
            DebugLog.lines.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    fun runTests() {
        if (running) return
        running = true
        results.clear()
        scope.launch {
            val body = "{\"query\":\"query { Page(page: 1, perPage: 1) { media(type: ANIME) { id } } }\"}"
            results.add(probe("AniList (direct)") { Net.postJson("https://graphql.anilist.co", body, mapOf("Accept" to "application/json"), 12_000).code })
            results.add(probe("Your site (AniList copy)") { Net.postJson("${Config.siteUrl}/api/anilist", body, timeoutMs = 12_000).code })
            results.add(probe("Streaming API") { Net.get(Config.anivexaUrl, timeoutMs = 12_000).code })
            running = false
        }
    }

    Caption("CONNECTION REPORT")
    Panel(padding = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Setting("Site", Config.siteUrl)
            Setting("Streaming API", Config.anivexaUrl)
            Setting("Upstash cache", if (Config.upstashOn) "on" else "off")
            Setting("TMDB logos", if (Config.tmdbOn) "on" else "off")
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlatButton(if (running) "Testing…" else "Run test", Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, height = 40.dp, fontSize = 13, busy = running) { runTests() }
                FlatButton("Copy report", Modifier.weight(1f), icon = Icons.Rounded.ContentCopy, height = 40.dp, fontSize = 13) {
                    clipboard.setText(AnnotatedString(report()))
                    Toasts.show("Report copied")
                }
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                results.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(RoundedCornerShape(99.dp)).background(if (p.ok) C.green else Color(0xFFF87171)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(p.text, fontSize = 12.sp, color = C.w(0.6f))
                        }
                    }
                }
            }
            if (DebugLog.lines.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("RECENT CALLS", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = C.w(0.5f))
                Spacer(Modifier.height(6.dp))
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(C.k(0.4f), shape).border(1.dp, C.line, shape).padding(10.dp)) {
                    DebugLog.lines.take(40).forEach { line ->
                        Text(line, fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, color = if (line.startsWith("FAIL")) Color(0xFFFCA5A5) else C.w(0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Setting(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(110.dp), fontSize = 12.5.sp, color = C.w(0.55f))
        Text(value, Modifier.weight(1f), fontSize = 12.5.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
