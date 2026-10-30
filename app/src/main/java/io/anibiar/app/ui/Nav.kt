package io.anibiar.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Screens that open on top of the four tabs. */
sealed class Route {
    data class Info(val id: Int) : Route()
    data class Watch(val id: Int, val episode: Int = 1, val dub: Boolean = false) : Route()
    object Login : Route()
}

object Nav {
    val stack = mutableStateListOf<Route>()

    fun openInfo(id: Int) { stack.add(Route.Info(id)) }
    fun openWatch(id: Int, episode: Int = 1, dub: Boolean = false) { stack.add(Route.Watch(id, episode, dub)) }
    fun openLogin() { if (stack.lastOrNull() !is Route.Login) stack.add(Route.Login) }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
    fun closeLogin() { stack.removeAll { it is Route.Login } }
}

/** A short message at the bottom of the screen (the app's snackbar). */
object Toasts {
    var message by mutableStateOf<String?>(null)
        private set
    var serial by mutableStateOf(0)
        private set

    fun show(text: String) {
        message = text
        serial++
    }

    fun clear() { message = null }
}
