package io.anibiar.app.core

import io.anibiar.app.BuildConfig

/** Build-time settings (see app/build.gradle.kts; the values come from the GitHub repository secrets). */
object Config {
    const val APP_NAME = "AniBiar"

    /** Your deployed AniBiar TV site. Only its cached AniList copy, the avatar decoration artwork and the library ping use it. */
    val siteUrl: String = BuildConfig.SITE_URL.trimEnd('/')

    val supabaseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val supabaseKey: String = BuildConfig.SUPABASE_KEY

    val upstashUrl: String = BuildConfig.UPSTASH_URL.trimEnd('/')
    val upstashToken: String = BuildConfig.UPSTASH_TOKEN
    val upstashOn: Boolean get() = upstashUrl.isNotEmpty() && upstashToken.isNotEmpty()

    val tmdbKey: String = BuildConfig.TMDB_KEY
    val tmdbOn: Boolean get() = tmdbKey.isNotEmpty()

    /** Prism sources to ask, in priority order. */
    val providers = io.anibiar.app.prism.Prism.providers

    /** OAuth (Google / Discord) returns here. Must be in Supabase > Authentication > URL Configuration > Redirect URLs. */
    const val AUTH_REDIRECT = "io.anibiar.app://login-callback"

    /** Turns a site-relative address ("/decorations/x.png") into a full one. */
    fun abs(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else if (path.startsWith("/")) "$siteUrl$path" else "$siteUrl/$path"
}
