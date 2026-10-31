package io.anibiar.app.prism

import java.text.Normalizer

/** Text helpers of the Prism engine (title matching and HTML scraping). Ported from the Anivexa API's new-provider-utils. */
object Text {
    fun decodeEntities(s: String): String {
        var v = Regex("&#(\\d+);").replace(s) { it.groupValues[1].toInt().toChar().toString() }
        v = Regex("&#x([0-9a-fA-F]+);").replace(v) { it.groupValues[1].toInt(16).toChar().toString() }
        return v.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
    }

    fun stripTags(html: String): String = decodeEntities(html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " "))

    /** An attribute of one tag, e.g. attr("<a href='x'>", "href"). */
    fun attr(tag: String, name: String): String {
        val m = Regex(Regex.escape(name) + "=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag) ?: return ""
        return decodeEntities(m.groupValues[1])
    }

    fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun dice(na: String, nb: String): Double {
        if (na == nb) return 1.0
        if (na.length < 2 || nb.length < 2) return 0.0
        val grams = HashMap<String, Int>()
        for (i in 0 until na.length - 1) grams.merge(na.substring(i, i + 2), 1, Int::plus)
        var hits = 0
        for (i in 0 until nb.length - 1) {
            val g = nb.substring(i, i + 2)
            val c = grams[g] ?: 0
            if (c > 0) { hits++; grams[g] = c - 1 }
        }
        return 2.0 * hits / (na.length + nb.length - 2)
    }

    fun diceCoeff(a: String, b: String) = dice(norm(a), norm(b))

    private val stop = setOf(
        "a", "an", "and", "of", "the", "to", "in", "on", "for", "no", "wa", "season", "part", "stage", "cour", "chapter", "chapters",
        "episode", "episodes", "movie", "film", "tv", "ova", "ona", "special", "specials", "final", "finale", "arc",
    )

    private fun titleText(v: String): String =
        Normalizer.normalize(v.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun titleWords(v: String): Set<String> =
        titleText(v).split(Regex("\\s+")).filter { it.length > 1 && it !in stop && !Regex("^\\d+(?:st|nd|rd|th)?$").matches(it) }.toCollection(LinkedHashSet())

    private val four = listOf("season", "part", "stage", "cour")
    private val numberWords = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5")
    private val romans = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")

    private fun markerValues(value: String): Map<String, MutableSet<String>> {
        val normalized = titleText(value)
        val text = normalized.replace(Regex("\\s+"), "")
        val markers = LinkedHashMap<String, MutableSet<String>>()
        for (marker in four) {
            val values = LinkedHashSet<String>()
            Regex("$marker(\\d+)").findAll(text).forEach { values.add(it.groupValues[1]) }
            Regex("(\\d+)(?:st|nd|rd|th)$marker").findAll(text).forEach { values.add(it.groupValues[1]) }
            Regex("((?:\\d+(?:st|nd|rd|th))+?)$marker").findAll(text).forEach { m ->
                Regex("(\\d+)(?:st|nd|rd|th)").findAll(m.groupValues[1]).forEach { values.add(it.groupValues[1]) }
            }
            for ((word, number) in numberWords) if (text.contains(marker + word) || text.contains(word + marker)) values.add(number)
            if (values.isNotEmpty()) markers[marker] = values
        }
        val finals = LinkedHashSet<String>()
        Regex("final(season|chapters?|part|arc)").findAll(text).forEach { finals.add(if (it.groupValues[1].startsWith("chapter")) "chapters" else it.groupValues[1]) }
        if (finals.isNotEmpty()) markers["final"] = finals
        if (Regex("movie|film|gekijouban").containsMatchIn(text)) markers["format"] = linkedSetOf("movie")
        Regex("\\b(ova|ona|special)\\b").find(normalized)?.let { markers["format"] = linkedSetOf(it.groupValues[1]) }
        val ending = normalized.split(Regex("\\s+")).lastOrNull() ?: ""
        val number = Regex("^(\\d{1,2})(?:st|nd|rd|th)?$").find(ending)?.groupValues?.get(1) ?: Regex("([a-z])(\\d{1,2})$").find(text)?.groupValues?.get(2)
        val endingValue = number ?: romans[ending]
        val already = endingValue != null && four.any { markers[it]?.contains(endingValue) == true }
        if (endingValue != null && !already) markers["numbered"] = linkedSetOf(endingValue)
        Regex("(?:movie|film)(\\d+)").findAll(text).forEach { markers.getOrPut("numbered") { LinkedHashSet() }.add(it.groupValues[1]) }
        return markers
    }

    private fun unicodeDice(a: String, b: String) = dice(titleText(a).replace(Regex("\\s+"), ""), titleText(b).replace(Regex("\\s+"), ""))

    /** How surely a site's title is the wanted anime: wrong seasons, parts, movies and OVAs are rejected. 0 = no, 1 = same. */
    fun identityScore(titles: List<String>, candidates: List<String>): Double {
        val target = titles.filter { it.isNotBlank() }
        val cands = candidates.filter { it.isNotBlank() }
        val targetMarkers = LinkedHashMap<String, MutableSet<String>>()
        for (t in target.take(3)) for ((k, v) in markerValues(t)) targetMarkers.getOrPut(k) { LinkedHashSet() }.addAll(v)
        var best = 0.0
        for (title in target) {
            val qw = titleWords(title)
            for (cand in cands) {
                val cw = titleWords(cand)
                val coreT = qw.joinToString(" ").ifEmpty { title }
                val coreC = cw.joinToString(" ").ifEmpty { cand }
                val lexical = unicodeDice(coreT, coreC)
                if (qw.isEmpty() || cw.isEmpty()) { if (lexical < 0.86) continue }
                val cm = markerValues(cand)
                var conflict = false
                var penalty = 0.0
                for ((marker, qv) in targetMarkers) {
                    val cv = cm[marker]
                    if (cv != null && qv.none { it in cv }) { conflict = true; break }
                    if (cv == null && !(marker == "season" && cm.containsKey("final"))) penalty += if (marker in four) 0.22 else 0.16
                }
                if (conflict) continue
                for (marker in cm.keys) if (!targetMarkers.containsKey(marker)) penalty += if (marker in four) 0.18 else 0.12
                penalty = minOf(0.48, penalty)
                val shared = qw.count { it in cw }
                val coverage = if (qw.isNotEmpty() && cw.isNotEmpty()) minOf(shared.toDouble() / qw.size, shared.toDouble() / cw.size) else lexical
                if (minOf(qw.size, cw.size) < 3) { if (lexical < 0.76) continue } else if (coverage < 0.5) continue
                best = maxOf(best, maxOf(0.0, lexical * 0.7 + coverage * 0.3 - penalty))
            }
        }
        return best
    }

    fun titleScore(query: String, candidate: String, slug: String): Double {
        val base = maxOf(diceCoeff(query, candidate), diceCoeff(query, slug.replace("-", " ")))
        val qNum = Regex("\\d+").find(norm(query))?.value ?: ""
        val sNum = Regex("\\d+").find(slug)?.value ?: ""
        if (qNum.isNotEmpty() && sNum.isNotEmpty() && qNum != sNum) return base * 0.65
        if (qNum.isNotEmpty() && sNum.isEmpty()) return base * 0.65
        if (qNum.isEmpty() && sNum.isNotEmpty()) {
            val n = sNum.toInt()
            if (n > 1 && n < 1900) return base * (1 - 0.06 * (n - 1))
        }
        val isMovieQuery = Regex("\\b(movie|film|the movie)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val isMovieMatch = Regex("\\b(movie|film)\\b", RegexOption.IGNORE_CASE).containsMatchIn(candidate) || Regex("movie|film").containsMatchIn(slug)
        if (isMovieQuery && !isMovieMatch) return base * 0.4
        val qLen = norm(query).length
        val sLen = norm(slug.replace("-", " ")).length
        return if (sLen > qLen * 1.6 + 4) base * 0.8 else base
    }

    private fun searchQueries(title: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(title)
        val words = title.trim().split(Regex("\\s+"))
        if (words.size > 4) out.add(words.take(4).joinToString(" "))
        if (words.size > 3) out.add(words.take(3).joinToString(" "))
        val stripped = title.replace(Regex("\\bseason\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bpart\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d+rd\\b|\\b\\d+th\\b|\\b\\d+st\\b|\\b\\d+nd\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim()
        if (stripped.isNotEmpty() && stripped != title) out.add(stripped)
        return out.filter { it.length >= 3 }
    }

    /** All the search words worth trying for a list of titles. */
    fun searchTerms(titles: List<String>, limit: Int = 24): List<String> {
        val queries = LinkedHashMap<String, String>()
        fun add(v: String) {
            val q = v.replace(Regex("\\s+"), " ").trim()
            if (q.length >= 3) queries[q.lowercase()] = q
        }
        val unique = titles.filter { it.isNotBlank() }.map { it.trim() }.distinct().take(12)
        for (t in unique.take(8)) add(t)
        for (t in unique.take(4)) {
            add(t.replace(Regex("[^\\p{L}\\p{N}]+"), " "))
            for (q in searchQueries(t)) add(q)
            for (seg in t.split(Regex("[:–—|]")).map { it.trim() }) if (seg.length >= 4) add(seg)
            add(
                t.replace(Regex("\\b(?:season|part|cour|stage|chapter|episode)\\s*(?:\\d+|one|two|three|four|five|final)?\\b", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\b(?:\\d+)(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\bthe\\s+final\\s+chapters?\\b", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s+"), " ").trim(),
            )
        }
        for (t in unique.drop(8)) add(t)
        return queries.values.take(limit)
    }
}
