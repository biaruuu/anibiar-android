package io.anibiar.app.data

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.anibiar.app.App
import io.anibiar.app.core.Config
import io.anibiar.app.core.Net
import io.anibiar.app.core.SupaException
import io.anibiar.app.core.Prefs
import io.anibiar.app.core.arr
import io.anibiar.app.core.jsonOf
import io.anibiar.app.core.obj
import io.anibiar.app.core.parseArray
import io.anibiar.app.core.parseObject
import io.anibiar.app.core.str
import io.anibiar.app.core.objects
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class User(val id: String, val email: String?, val meta: JSONObject)

class Profile(
    val username: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val bio: String? = null,
    val handle: String? = null,
    val decoration: String? = null,
) {
    companion object {
        fun from(r: JSONObject) = Profile(r.str("username"), r.str("avatar_url"), r.str("banner_url"), r.str("bio"), r.str("handle"), r.str("decoration"))
    }
}

/** Plain Supabase REST (GoTrue, PostgREST and Storage) over OkHttp, with the session kept on the phone. */
object Supa {
    private val url get() = Config.supabaseUrl
    private val key get() = Config.supabaseKey

    private fun message(body: String, code: Int): Pair<String?, String> {
        val j = parseObject(body)
        val msg = j?.str("msg") ?: j?.str("error_description") ?: j?.str("message") ?: j?.str("error") ?: "Server error (HTTP $code)."
        return (j?.str("code") ?: j?.opt("error_code")?.toString()?.takeIf { it != "null" }) to msg
    }

    /** One call; throws [SupaException] with the server's message when the answer is not a success. */
    suspend fun call(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        prefer: String? = null,
        extra: Map<String, String> = emptyMap(),
        bytes: ByteArray? = null,
        contentType: String = "application/json",
        timeoutMs: Long = 20_000,
    ): String {
        val h = LinkedHashMap<String, String>()
        h["apikey"] = key
        h["Authorization"] = "Bearer ${token ?: key}"
        if (prefer != null) h["Prefer"] = prefer
        h.putAll(extra)
        val res = Net.request(method, "$url$path", h, body, contentType, timeoutMs, bytes)
        if (!res.ok) {
            val (code, msg) = message(res.body, res.code)
            throw SupaException(code, msg)
        }
        return res.body
    }
}

/** Signed-in user and profile (same `profiles` table as the website). Compose reads the state fields directly. */
object Auth {
    var user by mutableStateOf<User?>(null)
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set
    var ready by mutableStateOf(false)
        private set

    private var access: String? = null
    private var refresh: String? = null
    private var expiresAt: Long = 0 // seconds
    private val lock = Mutex()

    val signedIn: Boolean get() = user != null

    fun init() {
        try {
            val s = Prefs.getString("auth.session")?.let { parseObject(it) }
            if (s != null) {
                access = s.str("access_token")
                refresh = s.str("refresh_token")
                expiresAt = s.optLong("expires_at", 0)
                val u = s.obj("user")
                if (u != null) user = readUser(u)
            }
        } catch (_: Exception) {
        }
        ready = true
        if (user != null) App.scope.launch {
            try { token() } catch (_: Exception) {}
            loadProfile()
        }
    }

    private fun readUser(u: JSONObject) = User(u.str("id") ?: "", u.str("email"), u.obj("user_metadata") ?: JSONObject())

    private fun save(user: JSONObject?) {
        val old = parseObject(Prefs.getString("auth.session") ?: "{}")
        val u = user ?: old?.obj("user")
        val o = jsonOf("access_token" to access, "refresh_token" to refresh, "expires_at" to expiresAt, "user" to u)
        Prefs.putString("auth.session", o.toString())
    }

    private fun setSession(j: JSONObject) {
        access = j.str("access_token")
        refresh = j.str("refresh_token")
        val inSec = j.optLong("expires_in", 3600)
        expiresAt = j.optLong("expires_at", 0).takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + inSec)
        val u = j.obj("user")
        if (u != null) {
            val nu = readUser(u)
            val changed = user?.id != nu.id
            user = nu
            if (changed) profile = null
        }
        save(u)
    }

    /** A valid access token, refreshed when it is about to expire. */
    suspend fun token(): String? = lock.withLock {
        val a = access ?: return null
        if (System.currentTimeMillis() / 1000 < expiresAt - 60) return a
        val r = refresh ?: return a
        try {
            val res = Supa.call("POST", "/auth/v1/token?grant_type=refresh_token", jsonOf("refresh_token" to r).toString())
            parseObject(res)?.let { setSession(it) }
            access
        } catch (e: SupaException) {
            // the refresh token was rejected: the session is over
            if (e.code != null || e.message?.contains("refresh", true) == true) clear()
            null
        } catch (_: Exception) {
            a // offline: keep using what we have
        }
    }

    private fun clear() {
        access = null; refresh = null; expiresAt = 0
        user = null; profile = null
        Prefs.putString("auth.session", null)
    }

    /* ------------------------------- sign in / sign up ------------------------------- */

    suspend fun signIn(email: String, password: String) {
        val res = Supa.call("POST", "/auth/v1/token?grant_type=password", jsonOf("email" to email.trim(), "password" to password).toString())
        setSession(parseObject(res) ?: throw Exception("Unexpected answer from the server."))
        loadProfile()
    }

    /** Returns true when a confirmation email was sent (no session yet). */
    suspend fun signUp(email: String, password: String): Boolean {
        val res = Supa.call(
            "POST",
            "/auth/v1/signup?redirect_to=${URLEncoder.encode(Config.AUTH_REDIRECT, "UTF-8")}",
            jsonOf("email" to email.trim(), "password" to password).toString(),
        )
        val j = parseObject(res) ?: return true
        if (j.str("access_token") != null) {
            setSession(j)
            loadProfile()
            return false
        }
        return true
    }

    /** Opens Google / Discord sign-in in the browser; the answer comes back through [handleCallback]. */
    fun oauth(context: Context, provider: String) {
        val uri = Uri.parse("${Config.supabaseUrl}/auth/v1/authorize?provider=$provider&redirect_to=${URLEncoder.encode(Config.AUTH_REDIRECT, "UTF-8")}")
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }

    /** The link io.anibiar.app://login-callback#access_token=... that the browser hands back. Returns an error message or null. */
    suspend fun handleCallback(uri: Uri): String? {
        val frag = uri.fragment ?: uri.query ?: return null
        val p = HashMap<String, String>()
        for (part in frag.split('&')) {
            val i = part.indexOf('=')
            if (i > 0) p[part.substring(0, i)] = URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }
        p["error_description"]?.let { return it }
        val at = p["access_token"] ?: return null
        access = at
        refresh = p["refresh_token"]
        expiresAt = System.currentTimeMillis() / 1000 + (p["expires_in"]?.toLongOrNull() ?: 3600)
        return try {
            val res = Supa.call("GET", "/auth/v1/user", token = at)
            val u = parseObject(res) ?: throw Exception("Unexpected answer from the server.")
            user = readUser(u)
            profile = null
            save(u)
            loadProfile()
            null
        } catch (e: Exception) {
            clear()
            io.anibiar.app.core.friendlyError(e)
        }
    }

    suspend fun resetPassword(email: String) {
        Supa.call("POST", "/auth/v1/recover?redirect_to=${URLEncoder.encode(Config.AUTH_REDIRECT, "UTF-8")}", jsonOf("email" to email.trim()).toString())
    }

    suspend fun signOut() {
        val t = access
        if (t != null) try { Supa.call("POST", "/auth/v1/logout", token = t) } catch (_: Exception) {}
        clear()
    }

    suspend fun changeEmail(email: String) {
        val t = token() ?: throw Exception("Log in first.")
        Supa.call("PUT", "/auth/v1/user?redirect_to=${URLEncoder.encode(Config.AUTH_REDIRECT, "UTF-8")}", jsonOf("email" to email.trim()).toString(), token = t)
    }

    /* ----------------------------------- profile ----------------------------------- */

    suspend fun loadProfile() {
        val u = user
        if (u == null) { profile = null; return }
        try {
            val res = Supa.call("GET", "/rest/v1/profiles?select=username,avatar_url,banner_url,bio,handle,decoration&id=eq.${u.id}", token = token())
            val row = parseArray(res).objects().firstOrNull()
            if (user?.id == u.id) profile = if (row == null) Profile() else Profile.from(row)
        } catch (_: Exception) {
            if (profile == null && user?.id == u.id) profile = Profile()
        }
    }

    /** Called after the profile was saved: refreshes the website's Redis copy first so every device sees the change. */
    suspend fun refreshProfile() {
        Library.bump()
        loadProfile()
    }

    private fun meta(vararg keys: String): String? {
        val m = user?.meta ?: return null
        for (k in keys) m.str(k)?.let { return it }
        return null
    }

    val name: String?
        get() {
            profile?.username?.takeIf { it.isNotEmpty() }?.let { return it }
            return meta("full_name", "name", "user_name") ?: user?.email?.substringBefore('@')
        }
    val avatar: String? get() = profile?.avatarUrl?.takeIf { it.isNotEmpty() } ?: meta("avatar_url", "picture")
    val banner: String? get() = profile?.bannerUrl
    val handle: String? get() = profile?.handle ?: user?.email?.substringBefore('@')
    val decoration: String? get() = profile?.decoration

    /* ---------------------------------- profile edits ---------------------------------- */

    private const val BUCKET = "profiles"
    private val types = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif")

    /** Uploads to the viewer's own folder and returns the public address. [ext] is the file extension without a dot. */
    suspend fun uploadImage(bytes: ByteArray, ext: String, kind: String): String {
        val u = user ?: throw Exception("Log in first.")
        val e = ext.lowercase()
        val type = types[e] ?: throw Exception("Use a PNG, JPG, WebP or GIF image.")
        if (bytes.size > 8 * 1024 * 1024) throw Exception("Images can be up to 8 MB.")
        val path = "${u.id}/$kind-${System.currentTimeMillis()}.${if (e == "jpeg") "jpg" else e}"
        Supa.call(
            "POST", "/storage/v1/object/$BUCKET/$path",
            token = token(), bytes = bytes, contentType = type,
            extra = mapOf("cache-control" to "max-age=31536000", "x-upsert" to "false"),
            timeoutMs = 60_000,
        )
        return "${Config.supabaseUrl}/storage/v1/object/public/$BUCKET/$path"
    }

    suspend fun deleteImage(url: String?) {
        if (url == null) return
        val marker = "/object/public/$BUCKET/"
        val i = url.indexOf(marker)
        if (i < 0) return
        val path = URLDecoder.decode(url.substring(i + marker.length).substringBefore('?'), "UTF-8")
        try { Supa.call("DELETE", "/storage/v1/object/$BUCKET/$path", token = token()) } catch (_: Exception) {}
    }

    /** [patch] holds the columns to change (username, bio, handle, decoration, avatar_url, banner_url). */
    suspend fun saveProfile(patch: JSONObject) {
        val u = user ?: throw Exception("Log in first.")
        val body = JSONObject(patch.toString()).put("id", u.id)
        Supa.call("POST", "/rest/v1/profiles?on_conflict=id", body.toString(), token = token(), prefer = "resolution=merge-duplicates,return=minimal")
        refreshProfile()
    }
}
