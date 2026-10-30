package io.anibiar.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.anibiar.app.data.Auth
import io.anibiar.app.ui.AniBiarTheme
import io.anibiar.app.ui.Nav
import io.anibiar.app.ui.Root
import io.anibiar.app.ui.Toasts
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { AniBiarTheme { Root() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Google / Discord / e-mail links come back as io.anibiar.app://login-callback#access_token=... */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "io.anibiar.app") return
        lifecycleScope.launch {
            val err = Auth.handleCallback(uri)
            if (err != null) Toasts.show(err)
            else if (Auth.signedIn) {
                Nav.closeLogin()
                Toasts.show("Signed in")
            }
        }
    }
}
