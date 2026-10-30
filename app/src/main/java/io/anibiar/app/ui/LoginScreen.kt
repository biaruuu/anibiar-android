package io.anibiar.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anibiar.app.R
import io.anibiar.app.core.friendlyError
import io.anibiar.app.data.Auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val emailPattern = Regex("^\\S+@\\S+\\.\\S+$")

@Composable
fun LoginScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // signed in through the browser (Google / Discord) or e-mail link: leave this screen
    LaunchedEffect(Auth.signedIn) {
        if (Auth.signedIn) Nav.closeLogin()
    }

    fun submit() {
        val e = email.trim()
        if (!emailPattern.matches(e)) { error = "Enter a valid email address."; return }
        if (password.length < 6) { error = "Use a password of at least 6 characters."; return }
        busy = true
        error = null
        info = null
        scope.launch {
            try {
                if (signup) {
                    val confirm = Auth.signUp(e, password)
                    if (confirm) info = "Check your inbox and open the confirmation link, then log in."
                    else { Nav.closeLogin(); Toasts.show("Account created") }
                } else {
                    Auth.signIn(e, password)
                    Nav.closeLogin()
                    Toasts.show("Signed in")
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                error = friendlyError(ex)
            }
            busy = false
        }
    }

    fun oauth(provider: String) {
        error = null
        info = null
        try {
            Auth.oauth(ctx, provider)
        } catch (ex: Exception) {
            error = friendlyError(ex)
        }
    }

    fun reset() {
        val e = email.trim()
        if (!emailPattern.matches(e)) { error = "Enter your email above first."; return }
        scope.launch {
            try {
                Auth.resetPassword(e)
                info = "Password reset link sent. Check your inbox."
                error = null
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                error = friendlyError(ex)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = statusBarHeight() + 70.dp, bottom = navBarHeight() + 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.logo), "AniBiar", Modifier.height(44.dp))
            Spacer(Modifier.height(26.dp))
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(shape).background(C.surface, shape).border(1.dp, C.line, shape).padding(20.dp),
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    FlatSwitch(
                        value = signup,
                        options = listOf(false to "Log in", true to "Sign up"),
                        onChange = { signup = it; error = null; info = null },
                        width = maxWidth,
                        height = 40.dp,
                        fontSize = 13,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(if (signup) "Create your account" else "Welcome back", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color_White)
                Spacer(Modifier.height(4.dp))
                Text("Keep your history, favorites and watchlist on every device.", fontSize = 13.5.sp, lineHeight = 19.sp, color = C.w(0.55f))
                Spacer(Modifier.height(18.dp))
                PField(
                    value = email, onChange = { email = it }, hint = "Email", enabled = !busy,
                    keyboard = KeyboardType.Email, leading = Icons.Rounded.Email, imeAction = ImeAction.Next, radius = 14.dp,
                )
                Spacer(Modifier.height(12.dp))
                PField(
                    value = password, onChange = { password = it }, hint = "Password", enabled = !busy,
                    password = true, keyboard = KeyboardType.Password, leading = Icons.Rounded.Lock, imeAction = ImeAction.Done,
                    onDone = { if (!busy) submit() }, radius = 14.dp,
                )
                if (!signup) {
                    Text(
                        "Forgot password?",
                        Modifier.align(Alignment.End).padding(top = 10.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { reset() },
                        fontSize = 12.5.sp, color = C.w(0.6f), textDecoration = TextDecoration.Underline,
                    )
                }
                error?.let { PError(it, Modifier.padding(top = 14.dp)) }
                info?.let { PInfo(it, Modifier.padding(top = 14.dp)) }
                Spacer(Modifier.height(18.dp))
                FlatButton(if (signup) "Create account" else "Log in", Modifier.fillMaxWidth(), kind = Kind.White, height = 48.dp, busy = busy) { submit() }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(C.w(0.15f)))
                    Text("or", Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = C.w(0.5f))
                    Box(Modifier.weight(1f).height(1.dp).background(C.w(0.15f)))
                }
                Spacer(Modifier.height(18.dp))
                FlatButton("Continue with Google", Modifier.fillMaxWidth(), icon = Icons.Rounded.Public, height = 46.dp, enabled = !busy) { oauth("google") }
                Spacer(Modifier.height(10.dp))
                FlatButton("Continue with Discord", Modifier.fillMaxWidth(), icon = Icons.Rounded.Forum, kind = Kind.Accent, height = 46.dp, enabled = !busy) { oauth("discord") }
            }
        }
        FlatIconButton(
            Icons.AutoMirrored.Rounded.ArrowBack, onBack,
            Modifier.padding(start = 12.dp, top = statusBarHeight() + 8.dp), size = 42.dp, description = "Back",
        )
    }
}

private val Color_White = androidx.compose.ui.graphics.Color.White
