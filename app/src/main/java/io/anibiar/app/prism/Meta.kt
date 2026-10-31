package io.anibiar.app.prism

import io.anibiar.app.App
import io.anibiar.app.core.Net
import io.anibiar.app.core.arr
import io.anibiar.app.core.int
import io.anibiar.app.core.jsonOf
import io.anibiar.app.core.obj
import io.anibiar.app.core.objects
import io.anibiar.app.core.parseObject
import io.anibiar.app.core.str
import io.anibiar.app.core.strings
import io.anibiar.app.data.AniList
import io.anibiar.app.data.AppCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class Media(
    val id: Int,
    val idMal: Int?,
    val english: String?,
    val romaji: String?,
    val native: String?,
    val status: String,
    val format: String?,
    val episodes: Int?,
    val seasonYear: Int?,
    val startYear: Int?,
    val synonyms: List<String>,
)

/** What every provider needs to know about an anime: AniList facts and the ani.zip episode data. */
object Meta {
    private val medias = ConcurrentHashMap<Int, Media>()
    private val zips = ConcurrentHashMap<Int, JSONObject>()
    private val offsets = ConcurrentHashMap<Int, Int>()
    private val flights = ConcurrentHashMap<String, Deferred<*>>()

    /** Runs [block] once for a key even when several callers ask at the same time. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> once(key: String, block: suspend () -> T): T {
        val d = flights.getOrPut(key) {
            App.scope.async { try { block() } finally { flights.remove(key) } }
        } as Deferred<T>
        return d.await()
    }

    suspend fun media(id: Int): Media {
        medias[id]?.let { return it }
        return once("media:$id") {
            val d = AniList.query(
                "query (\$id: Int) { Media(id: \$id, type: ANIME) { id idMal title { english romaji native } status format episodes seasonYear startDate { year } synonyms } }",
                jsonOf("id" to id),
            )
            val m = d.obj("Media") ?: throw PrismException("No data found for AniList id $id")
            val t = m.obj("title")
            val status = when (m.str("status")) { "CANCELLED" -> "FINISHED"; null -> "RELEASING"; else -> m.str("status")!! }
            val media = Media(
                id, m.int("idMal"), t?.str("english"), t?.str("romaji"), t?.str("native"), status, m.str("format"),
                m.int("episodes"), m.int("seasonYear"), m.obj("startDate")?.int("year"), m.arr("synonyms").strings(),
            )
            medias[id] = media
            media
        }
    }

    /** ani.zip: episode titles, pictures, descriptions and air dates. Null when it can't be reached. */
    suspend fun zip(id: Int): JSONObject? {
        zips[id]?.let { return it }
        return once("zip:$id") {
            val cached = AppCache.read("zip:$id")
            val parsed = cached?.let { parseObject(it.data) }
            if (parsed != null && cached.ageDays < 1) {
                zips[id] = parsed
                return@once parsed
            }
            try {
                val r = Net.get("https://api.ani.zip/mappings?anilist_id=$id", mapOf("Accept" to "application/json", "User-Agent" to Net.UA), 10_000)
                val j = if (r.ok) parseObject(r.body) else null
                if (j != null && j.length() > 0) {
                    zips[id] = j
                    AppCache.write("zip:$id", j.toString(), 86_400)
                }
                j ?: parsed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                parsed
            }
        }
    }

    fun titles(media: Media, zip: JSONObject?): List<String> {
        val t = zip?.obj("titles")
        return listOfNotNull(media.english, media.romaji, media.native, t?.str("en"), t?.str("x-jat"), t?.str("ja"))
            .plus(media.synonyms).filter { it.isNotBlank() }.distinct()
    }

    fun expectedCount(media: Media, zip: JSONObject?): Int? {
        val nums = ArrayList<Int>()
        media.episodes?.let { nums.add(it) }
        zip?.obj("episodes")?.keys()?.forEach { k -> k.toIntOrNull()?.let { nums.add(it) } }
        return nums.filter { it > 0 }.maxOrNull()
    }

    class EpMeta(val title: String?, val duration: Int?, val filler: Boolean, val description: String?, val image: String?, val airDate: String?)

    fun episodeMeta(n: Int, zip: JSONObject?): EpMeta {
        val az = zip?.obj("episodes")?.obj(n.toString())
        val runtime = az?.int("runtime") ?: az?.int("length")
        val title = az?.obj("title")?.let { it.str("en") ?: it.str("x-jat") }
        return EpMeta(
            title, runtime?.let { it * 60 }, (az?.opt("filler") as? Boolean) ?: false,
            az?.str("overview") ?: az?.str("summary"),
            az?.str("image") ?: zip?.obj("images")?.str("cover"),
            az?.str("airdate") ?: az?.str("aired"),
        )
    }

    private fun prequel(relations: JSONObject?, depth: Int = 0): Int {
        if (relations == null || depth > 5) return 0
        val edge = relations.arr("edges").objects().firstOrNull {
            it.str("relationType") == "PREQUEL" && it.obj("node")?.str("type") == "ANIME" && (it.obj("node")?.int("episodes") ?: 0) >= 5
        } ?: return 0
        val node = edge.obj("node")!!
        return (node.int("episodes") ?: 0) + prequel(node.obj("relations"), depth + 1)
    }

    /** Episodes of the earlier seasons, for sites that number all seasons in one run. */
    suspend fun prequelOffset(id: Int): Int {
        offsets[id]?.let { return it }
        val frag = "edges{relationType(version:2) node{id type episodes relations{edges{relationType(version:2) node{id type episodes relations{edges{relationType(version:2) node{id type episodes relations{edges{relationType(version:2) node{id type episodes}}}}}}}}}}}"
        val d = AniList.query("query(\$id:Int){Media(id:\$id,type:ANIME){relations{$frag}}}", jsonOf("id" to id))
        val v = prequel(d.obj("Media")?.obj("relations"))
        offsets[id] = v
        return v
    }
}

class PrismException(message: String) : Exception(message)
