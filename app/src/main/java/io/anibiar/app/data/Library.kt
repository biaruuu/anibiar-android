package io.anibiar.app.data

import io.anibiar.app.App
import io.anibiar.app.core.Config
import io.anibiar.app.core.Net
import io.anibiar.app.core.Prefs
import io.anibiar.app.core.int
import io.anibiar.app.core.jsonOf
import io.anibiar.app.core.long
import io.anibiar.app.core.objects
import io.anibiar.app.core.parseArray
import io.anibiar.app.core.str
import io.anibiar.app.core.dbl
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/* Continue Watching, Favorites and Watchlist. Always saved on this phone; when signed in it is also saved in the same
 * Supabase tables the website uses, so everything follows the account. */

private fun isoOf(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

private fun msOf(iso: String?): Long {
    if (iso == null) return 0
    return try {
        // "2026-10-30T08:15:02.123456+00:00" -> keep the first 19 characters, always UTC
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(19))!!.time
    } catch (_: Exception) {
        0
    }
}

class HistoryItem(
    val animeId: Int,
    val episode: Int,
    val dub: Boolean,
    val position: Double,
    val duration: Double,
    val title: String,
    val image: String? = null,
    val totalEpisodes: Int? = null,
    val updatedAt: Long, // ms since epoch
) {
    /** A show you finished (past 92%) of its last episode is not worth "continuing". */
    val finished: Boolean get() = duration > 0 && position / duration > 0.92 && (totalEpisodes == null || episode >= totalEpisodes)

    fun toLocal(): JSONObject = jsonOf(
        "animeId" to animeId, "episode" to episode, "audio" to if (dub) "dub" else "sub", "position" to position,
        "duration" to duration, "title" to title, "image" to image, "totalEpisodes" to totalEpisodes, "updatedAt" to updatedAt,
    )

    fun toRow(userId: String): JSONObject = jsonOf(
        "user_id" to userId, "anime_id" to animeId, "episode" to episode, "audio" to if (dub) "dub" else "sub",
        "position" to position, "duration" to duration, "title" to title, "image" to image,
        "total_episodes" to totalEpisodes, "updated_at" to isoOf(updatedAt),
    )

    companion object {
        fun fromLocal(j: JSONObject) = HistoryItem(
            j.getInt("animeId"), j.getInt("episode"), j.str("audio") == "dub", j.optDouble("position", 0.0), j.optDouble("duration", 0.0),
            j.str("title") ?: "", j.str("image"), j.int("totalEpisodes"), j.optLong("updatedAt", 0),
        )

        fun fromRow(r: JSONObject) = HistoryItem(
            r.getInt("anime_id"), r.getInt("episode"), r.str("audio") == "dub", r.dbl("position") ?: 0.0, r.dbl("duration") ?: 0.0,
            r.str("title") ?: "", r.str("image"), r.int("total_episodes"), msOf(r.str("updated_at")),
        )
    }
}

class ListItem(val animeId: Int, val title: String, val image: String?, val addedAt: Long) {
    fun toLocal(): JSONObject = jsonOf("animeId" to animeId, "title" to title, "image" to image, "addedAt" to addedAt)

    companion object {
        fun fromLocal(j: JSONObject) = ListItem(j.getInt("animeId"), j.str("title") ?: "", j.str("image"), j.optLong("addedAt", 0))
        fun fromRow(r: JSONObject) = ListItem(r.getInt("anime_id"), r.str("title") ?: "", r.str("image"), msOf(r.str("added_at")))
    }
}

enum class Collection { LIST, FAVORITES }

object Library {
    private const val HISTORY_KEY = "anibiar.history"
    private const val LIST_KEY = "anibiar.mylist"
    private const val FAV_KEY = "anibiar.favorites"

    private val uid: String? get() = Auth.user?.id

    private fun read(key: String): List<JSONObject> = parseArray(Prefs.getString(key) ?: "[]").objects()
    private fun write(key: String, value: List<JSONObject>) = Prefs.putString(key, JSONArray(value).toString())

    /** Tells the website's server something was written so its Redis copy is refreshed (best effort). */
    suspend fun bump() {
        val token = Auth.token() ?: return
        try {
            Net.request("POST", "${Config.siteUrl}/api/me/library", mapOf("Authorization" to "Bearer $token"), timeoutMs = 8_000)
        } catch (_: Exception) {
        }
    }

    private fun bumpLater() { App.scope.launch { bump() } }

    private suspend fun rest(method: String, path: String, body: String? = null, prefer: String? = null): String =
        Supa.call(method, path, body, token = Auth.token(), prefer = prefer)

    private const val UPSERT = "resolution=merge-duplicates,return=minimal"

    /* ----------------------------------- history ----------------------------------- */

    fun localHistory(): List<HistoryItem> =
        read(HISTORY_KEY).mapNotNull { runCatching { HistoryItem.fromLocal(it) }.getOrNull() }.sortedByDescending { it.updatedAt }

    private var lastBump = 0L

    suspend fun saveProgress(item: HistoryItem) {
        val next = (listOf(item) + localHistory().filter { it.animeId != item.animeId }).take(60)
        write(HISTORY_KEY, next.map { it.toLocal() })
        val u = uid ?: return
        try {
            rest("POST", "/rest/v1/watch_history?on_conflict=user_id,anime_id", item.toRow(u).toString(), UPSERT)
            if (System.currentTimeMillis() - lastBump > 15_000) {
                lastBump = System.currentTimeMillis()
                bumpLater()
            }
        } catch (_: Exception) {
            // offline: the local copy is still there
        }
    }

    suspend fun loadHistory(): List<HistoryItem> {
        val local = localHistory()
        val u = uid ?: return local
        return try {
            val rows = parseArray(rest("GET", "/rest/v1/watch_history?select=*&user_id=eq.$u&order=updated_at.desc&limit=60")).objects()
            val byId = LinkedHashMap<Int, HistoryItem>()
            rows.forEach { r -> runCatching { HistoryItem.fromRow(r) }.getOrNull()?.let { byId[it.animeId] = it } }
            val push = ArrayList<HistoryItem>()
            for (h in local) {
                val r = byId[h.animeId]
                if (r == null || h.updatedAt > r.updatedAt) {
                    byId[h.animeId] = h
                    push.add(h)
                }
            }
            if (push.isNotEmpty()) {
                rest("POST", "/rest/v1/watch_history?on_conflict=user_id,anime_id", JSONArray(push.map { it.toRow(u) }).toString(), UPSERT)
                bumpLater()
            }
            val merged = byId.values.sortedByDescending { it.updatedAt }
            write(HISTORY_KEY, merged.take(60).map { it.toLocal() })
            merged
        } catch (_: Exception) {
            local
        }
    }

    suspend fun removeHistory(animeId: Int) {
        write(HISTORY_KEY, read(HISTORY_KEY).filter { it.optInt("animeId") != animeId })
        val u = uid ?: return
        try {
            rest("DELETE", "/rest/v1/watch_history?user_id=eq.$u&anime_id=eq.$animeId")
            bumpLater()
        } catch (_: Exception) {
        }
    }

    /* ------------------------------ my list and favorites ------------------------------ */

    private fun table(c: Collection) = if (c == Collection.FAVORITES) "favorites" else "my_list"
    private fun key(c: Collection) = if (c == Collection.FAVORITES) FAV_KEY else LIST_KEY

    fun localCollection(c: Collection): List<ListItem> =
        read(key(c)).mapNotNull { runCatching { ListItem.fromLocal(it) }.getOrNull() }.sortedByDescending { it.addedAt }

    suspend fun load(c: Collection): List<ListItem> {
        val mine = localCollection(c)
        val u = uid ?: return mine
        return try {
            val rows = parseArray(rest("GET", "/rest/v1/${table(c)}?select=*&user_id=eq.$u&order=added_at.desc&limit=500")).objects()
            val remote = rows.mapNotNull { runCatching { ListItem.fromRow(it) }.getOrNull() }
            val have = remote.map { it.animeId }.toSet()
            val extra = mine.filter { it.animeId !in have }
            if (extra.isNotEmpty()) {
                val body = JSONArray(extra.map { jsonOf("user_id" to u, "anime_id" to it.animeId, "title" to it.title, "image" to it.image, "added_at" to isoOf(it.addedAt)) })
                rest("POST", "/rest/v1/${table(c)}?on_conflict=user_id,anime_id", body.toString(), UPSERT)
                bumpLater()
            }
            val merged = (remote + extra).sortedByDescending { it.addedAt }
            write(key(c), merged.map { it.toLocal() })
            merged
        } catch (_: Exception) {
            mine
        }
    }

    /** Returns true when the title is in the collection afterwards. */
    suspend fun toggle(c: Collection, animeId: Int, title: String, image: String?): Boolean {
        val all = read(key(c))
        val has = all.any { it.optInt("animeId") == animeId }
        val next = if (has) all.filter { it.optInt("animeId") != animeId }
        else listOf(ListItem(animeId, title, image, System.currentTimeMillis()).toLocal()) + all
        write(key(c), next)
        val u = uid
        if (u != null) {
            try {
                if (has) rest("DELETE", "/rest/v1/${table(c)}?user_id=eq.$u&anime_id=eq.$animeId")
                else rest("POST", "/rest/v1/${table(c)}?on_conflict=user_id,anime_id", jsonOf("user_id" to u, "anime_id" to animeId, "title" to title, "image" to image).toString(), UPSERT)
                bumpLater()
            } catch (_: Exception) {
            }
        }
        return !has
    }

    /** Quick check against what is on the phone (no network). */
    fun has(c: Collection, animeId: Int): Boolean = localCollection(c).any { it.animeId == animeId }
}
