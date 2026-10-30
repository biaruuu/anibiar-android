package io.anibiar.app.core

import java.io.IOException

/** An error answered by Supabase (auth, database or storage). */
class SupaException(val code: String?, message: String) : Exception(message)

/** Plain message for an error shown to the person. */
fun friendlyError(e: Throwable): String {
    if (e is SupaException) return if (e.code == "23505") "That username is already taken." else (e.message ?: "Something went wrong.")
    val m = e.message
    return when {
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || (e is IOException && m.isNullOrBlank()) ->
            "Couldn't reach the server. Check your connection and try again."
        m.isNullOrBlank() -> "Something went wrong. Try again."
        else -> m
    }
}
