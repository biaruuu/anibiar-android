package io.anibiar.app.data

import io.anibiar.app.core.arr
import io.anibiar.app.core.int
import io.anibiar.app.core.obj
import io.anibiar.app.core.str
import io.anibiar.app.core.strings
import org.json.JSONObject

class Titles(val romaji: String?, val english: String?, val native: String?) {
    companion object {
        fun from(j: JSONObject?) = Titles(j?.str("romaji"), j?.str("english"), j?.str("native"))
    }
}

class Anime(
    val id: Int,
    val title: Titles,
    val cover: String? = null, // extraLarge
    val coverLarge: String? = null,
    val color: String? = null,
    val banner: String? = null,
    val description: String? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val seasonYear: Int? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val status: String? = null,
    val nextEpisode: Int? = null,
    val synonyms: List<String> = emptyList(),
    val popularity: Int? = null,
) {
    val name: String get() = title.english ?: title.romaji ?: "Untitled"
    val poster: String? get() = cover ?: coverLarge
    val thumb: String? get() = coverLarge ?: cover

    /** Episodes you can watch now: aired so far for a running show, otherwise the total. */
    val aired: Int get() = if (status == "RELEASING" && nextEpisode != null) (nextEpisode - 1).coerceAtLeast(0) else (episodes ?: 0)

    val formatLabel: String get() = when (format) { null -> "TV"; "TV_SHORT" -> "TV"; else -> format.replace('_', ' ') }

    companion object {
        fun from(j: JSONObject): Anime {
            val c = j.obj("coverImage")
            return Anime(
                id = j.getInt("id"),
                title = Titles.from(j.obj("title")),
                cover = c?.str("extraLarge"),
                coverLarge = c?.str("large"),
                color = c?.str("color"),
                banner = j.str("bannerImage"),
                description = j.str("description"),
                format = j.str("format"),
                episodes = j.int("episodes"),
                seasonYear = j.int("seasonYear"),
                genres = j.arr("genres").strings(),
                averageScore = j.int("averageScore"),
                status = j.str("status"),
                nextEpisode = j.obj("nextAiringEpisode")?.int("episode"),
                synonyms = j.arr("synonyms").strings(),
                popularity = j.int("popularity"),
            )
        }
    }
}

class LatestEpisode(val id: Int, val airingAt: Long, val episode: Int, val media: Anime)

class LatestPage(val items: List<LatestEpisode>, val lastPage: Int)

class SearchPage(val items: List<Anime>, val hasNext: Boolean)

class FuzzyDate(val year: Int?, val month: Int?, val day: Int?) {
    val empty: Boolean get() = year == null
    override fun toString(): String {
        if (year == null) return "?"
        val m = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val mo = if (month != null && month in 1..12) "${m[month - 1]} " else ""
        val d = if (day != null) "$day, " else ""
        return "$mo$d$year"
    }

    companion object {
        fun from(j: JSONObject?) = FuzzyDate(j?.int("year"), j?.int("month"), j?.int("day"))
    }
}

class Relation(val type: String, val media: Anime, val mediaType: String?, val year: Int?)

class Detail(
    val anime: Anime,
    val idMal: Int?,
    val duration: Int?,
    val season: String?,
    val start: FuzzyDate,
    val end: FuzzyDate,
    val meanScore: Int?,
    val studio: String?,
    val trailerId: String?,
    val trailerSite: String?,
    val relations: List<Relation>,
    val recommendations: List<Anime>,
    val siteUrl: String?,
)

fun formatName(f: String?): String = when (f) {
    "TV" -> "TV Series"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> f ?: "Unknown"
}

fun statusName(s: String?): String = when (s) {
    "FINISHED" -> "Finished"
    "RELEASING" -> "Airing"
    "NOT_YET_RELEASED" -> "Upcoming"
    "CANCELLED" -> "Cancelled"
    "HIATUS" -> "On hiatus"
    else -> s ?: "Unknown"
}

/** "5m ago", "3h ago", "2d ago" from a unix time in seconds. */
fun timeAgo(unixSeconds: Long): String {
    val s = (System.currentTimeMillis() / 1000.0 - unixSeconds).coerceAtLeast(0.0)
    return when {
        s < 3600 -> "${(s / 60).toInt().coerceIn(1, 59)}m ago"
        s < 86400 -> "${(s / 3600).toInt()}h ago"
        else -> "${(s / 86400).toInt()}d ago"
    }
}

fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

/** 83.5 -> "1:23", 3725 -> "1:02:05". */
fun clock(seconds: Double): String {
    val s = if (seconds.isNaN() || seconds < 0) 0 else seconds.toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    val mm = if (h > 0) m.toString().padStart(2, '0') else "$m"
    return (if (h > 0) "$h:" else "") + mm + ":" + ss.toString().padStart(2, '0')
}
