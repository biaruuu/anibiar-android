package io.anibiar.app.data

import io.anibiar.app.core.Net
import io.anibiar.app.core.arr
import io.anibiar.app.core.dbl
import io.anibiar.app.core.int
import io.anibiar.app.core.jsonOf
import io.anibiar.app.core.obj
import io.anibiar.app.core.objects
import io.anibiar.app.core.parseObject
import io.anibiar.app.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Aurora": a server that runs entirely inside the app. It is a Kotlin port of the MegaPlay Direct source of the
 * AniVault scraper: ask the player page for this MAL / AniList id and episode, read the player's getSources answer,
 * decrypt the `enc` payload with the key/IV found in the player's own script, and sign the playback URL when the
 * site asks for a token. No other server of ours is involved.
 */
object Vault {
    const val NAME = "Aurora"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val HOME = "https://megaplay.buzz"

    private val malIds = ConcurrentHashMap<Int, Int>()

    /** The MAL id of an AniList anime (0 when it has none), looked up once. */
    private suspend fun malId(anilistId: Int): Int {
        malIds[anilistId]?.let { return it }
        return try {
            val d = AniList.query(
                "query (\$id: Int) { Media(id: \$id, type: ANIME) { idMal } }",
                jsonOf("id" to anilistId),
            )
            val v = d.obj("Media")?.int("idMal") ?: 0
            malIds[anilistId] = v
            v
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    class Result(val source: StreamSource, val intro: TimeRange?, val outro: TimeRange?)

    /** One playable stream for this episode, or null when the server doesn't have it. */
    suspend fun stream(anilistId: Int, episode: Int, dub: Boolean): Result? {
        val language = if (dub) "dub" else "sub"
        // the MAL id is looked up for at most 2.5 s; after that (or when there is none) the AniList id is used
        val mal = withTimeoutOrNull(2_500) { malId(anilistId) } ?: 0
        val tries = ArrayList<Pair<String, Int>>()
        if (mal > 0) tries.add("mal" to mal)
        tries.add("ani" to anilistId)
        for ((kind, id) in tries) {
            val r = try {
                resolve("$HOME/stream/$kind/$id/$episode/$language", dub)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (r != null) return r
        }
        return null
    }

    private fun headers(referer: String, extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer,
        ) + extra

    private suspend fun resolve(playerUrl: String, dub: Boolean): Result? {
        val page = Net.get(playerUrl, headers("$HOME/"), 12_000)
        if (!page.ok || page.body.isEmpty()) return null
        val html = page.body
        val fileId = Regex("<title>File ([0-9]+)").find(html)?.groupValues?.get(1) ?: return null
        // redirects are followed by the client; the player may live on a mirror host, found from the page itself
        val host = Regex("""https?://([a-z0-9.-]+)/stream/getSources""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: playerUrl.toHttpUrl().host
        val referer = "https://$host/"

        val res = Net.get(
            "https://$host/stream/getSources?id=$fileId",
            headers(referer, mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/plain, */*")),
            10_000,
        )
        if (!res.ok) return null
        val data = parseObject(res.body) ?: return null

        var m3u8: String? = data.obj("sources")?.str("file") ?: data.arr("sources")?.let { a -> a.objects().firstOrNull()?.str("file") }
        val tracks = data.arr("tracks").objects().filter { !it.str("file").isNullOrEmpty() }
        val subs = tracks
            .filter { (it.str("kind") ?: "captions").let { k -> k == "captions" || k == "subtitles" } }
            .map {
                StreamSubtitle(
                    label = it.str("label") ?: "Subtitle",
                    lang = it.str("label") ?: "und",
                    src = it.str("file")!!,
                    isDefault = it.opt("default") == true,
                    headers = mapOf("User-Agent" to UA, "Referer" to referer),
                )
            }

        val enc = data.str("enc")
        if (m3u8.isNullOrEmpty() && !enc.isNullOrEmpty()) {
            val scripts = fetchScripts(host, html, referer)
            val decrypted = scripts.player?.let { decrypt(enc, it) }
            if (decrypted != null) {
                val key = scripts.signing?.let { signingKey(it) }
                m3u8 = sign(decrypted, key) ?: decrypted
            }
        }
        if (m3u8.isNullOrEmpty()) return null

        // the site's own workaround: a dead stream host is replaced with the host of the subtitle files
        if (m3u8.contains("mewstream.buzz")) {
            var replacement = "1oe.lostproject.club"
            subs.firstOrNull { !it.src.contains("mewstream.buzz") }?.let {
                try { replacement = it.src.toHttpUrl().host } catch (_: Exception) {}
            }
            try { m3u8 = m3u8.toHttpUrl().newBuilder().host(replacement).build().toString() } catch (_: Exception) {}
        }

        val hdr = linkedMapOf("User-Agent" to UA, "Accept" to "*/*", "Referer" to referer, "Origin" to referer.trimEnd('/'))
        val intro = TimeRange.from(data.opt("intro"))
        val outro = TimeRange.from(data.opt("outro"))
        val source = StreamSource(
            id = "aurora-0",
            name = NAME,
            soft = subs.isNotEmpty() || dub,
            hls = !m3u8.contains(".mp4"),
            src = m3u8,
            headers = hdr,
            subtitles = subs,
            intro = intro,
            outro = outro,
        )
        return Result(source, intro, outro)
    }

    /* ------------------------------ the player's scripts ------------------------------ */

    internal class Scripts(val player: String?, val signing: String?)

    private val scriptCache = ConcurrentHashMap<String, String>()

    internal suspend fun fetchScripts(host: String, html: String, referer: String): Scripts = coroutineScope {
        val origin = "https://$host/"
        val urls = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { m -> try { origin.toHttpUrl().resolve(m.groupValues[1])?.toString() } catch (_: Exception) { null } }
            .distinct().toList()
        val bodies = urls.map { u ->
            async {
                scriptCache[u] ?: try {
                    val r = Net.get(u, headers(referer, mapOf("Accept" to "*/*")), 8_000)
                    if (r.ok) r.body.also { scriptCache[u] = it } else null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    null
                }
            }
        }.map { it.await() }
        Scripts(
            player = bodies.firstOrNull { it != null && Regex("getSources", RegexOption.IGNORE_CASE).containsMatchIn(it) && Regex("AES-CBC", RegexOption.IGNORE_CASE).containsMatchIn(it) },
            signing = bodies.firstOrNull { it != null && it.contains("[a-f0-9]{32}") && it.contains("token=") },
        )
    }

    /* ------------------------------ decrypting the enc payload ------------------------------ */

    private fun decodeEscapes(v: String): String =
        Regex("""\\u([0-9A-Fa-f]{4})|\\x([0-9A-Fa-f]{2})|\\([\\'"bnfrtv0])""").replace(v) { m ->
            val u = m.groupValues[1]
            val x = m.groupValues[2]
            val e = m.groupValues[3]
            when {
                u.isNotEmpty() -> u.toInt(16).toChar().toString()
                x.isNotEmpty() -> x.toInt(16).toChar().toString()
                else -> when (e) { "b" -> "\b"; "n" -> "\n"; "f" -> "\u000c"; "r" -> "\r"; "t" -> "\t"; "v" -> "\u000b"; "0" -> "\u0000"; else -> e }
            }
        }

    /** Every quoted string in the script (comments, regex literals and template strings skipped). */
    internal fun scriptStrings(s: String): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        var prev = ' '
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '/' && i + 1 < n && s[i + 1] == '/') {
                i = s.indexOf('\n', i + 2)
                if (i < 0) break
                continue
            }
            if (c == '/' && i + 1 < n && s[i + 1] == '*') {
                i = s.indexOf("*/", i + 2)
                if (i < 0) break
                i += 2
                continue
            }
            if (c == '/' && "=(:,[!&|?{};".indexOf(prev) >= 0) {
                i++
                var inClass = false
                while (i < n) {
                    if (s[i] == '\\') { i += 2; continue }
                    if (s[i] == '[') inClass = true
                    if (s[i] == ']') inClass = false
                    if (s[i] == '/' && !inClass) {
                        i++
                        while (i < n && s[i].isLetter()) i++
                        break
                    }
                    i++
                }
                continue
            }
            if (c == '\'' || c == '"') {
                val sb = StringBuilder()
                i++
                while (i < n && s[i] != c) {
                    if (s[i] == '\\' && i + 1 < n) sb.append(s[i++])
                    sb.append(s[i++])
                }
                out.add(decodeEscapes(sb.toString()))
                i++
                continue
            }
            if (c == '`') {
                i++
                while (i < n && s[i] != '`') i += if (s[i] == '\\') 2 else 1
                i++
                continue
            }
            if (!c.isWhitespace()) prev = c
            i++
        }
        return out.toList()
    }

    // the key/IV that worked for a given player script, so later episodes skip the search
    private val working = ConcurrentHashMap<Int, Pair<ByteArray, ByteArray>>()

    private fun pick(data: JSONObject): String? {
        val direct = data.str("file") ?: data.str("url")
        if (!direct.isNullOrEmpty()) return direct
        val src = data.opt("sources")
        if (src is JSONObject) return src.str("file")
        if (src is org.json.JSONArray && src.length() > 0) return src.optJSONObject(0)?.str("file")
        return null
    }

    private fun tryDecrypt(bytes: ByteArray, key: ByteArray, iv: ByteArray): String? = try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val text = String(c.doFinal(bytes), Charsets.UTF_8)
        parseObject(text)?.let { pick(it) }?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    internal fun decrypt(value: String, script: String): String? {
        val bytes = try { java.util.Base64.getUrlDecoder().decode(value) } catch (_: Exception) { return null }
        if (bytes.isEmpty() || bytes.size % 16 != 0) return null
        val sh = script.hashCode()
        working[sh]?.let { (k, v) -> tryDecrypt(bytes, k, v)?.let { return it } }
        val candidates = scriptStrings(script).filter { val l = it.toByteArray().size; l in 1..32 }
        val ivs = candidates.filter { it.toByteArray().size == 16 }
        for (kv in candidates) {
            val key = ByteArray(32)
            val raw = kv.toByteArray()
            System.arraycopy(raw, 0, key, 0, raw.size.coerceAtMost(32))
            for (iv in ivs) {
                val ivb = iv.toByteArray()
                val r = tryDecrypt(bytes, key, ivb)
                if (r != null) {
                    working[sh] = key to ivb
                    return r
                }
            }
        }
        return null
    }

    /* ------------------------------ signing the playback URL ------------------------------ */

    private val keyCache = ConcurrentHashMap<Int, String>()

    /** The signing key is produced by a tiny string-decoder inside the site's script; it is run in a sandboxed JS engine. */
    internal fun signingKey(script: String): String? {
        val sh = script.hashCode()
        keyCache[sh]?.let { return it }
        val objectName = Regex("""\blet\s+([A-Za-z_$][\w$]*)\s*;\s*!\s*function\s*\(\)\s*\{""").find(script)?.groupValues?.get(1) ?: return null
        val entry = Regex("""\bconst\s+[A-Za-z_$][\w$]*\s*=\s*new URLSearchParams\b""").find(script) ?: return null
        val enc = script.indexOf("new TextEncoder();return")
        if (enc < 0) return null
        val keyVar = Regex("""new TextEncoder\(\);return[\s\S]{0,1200}?\]\(([A-Za-z_$][\w$]*)\),\{""", RegexOption.IGNORE_CASE)
            .find(script.substring(enc, minOf(script.length, enc + 1600)))?.groupValues?.get(1) ?: return null
        val expr = Regex("""(?:const|let|var)\s+""" + Regex.escape(keyVar) + """\s*=\s*(""" + Regex.escape(objectName) + """\.[A-Za-z_$][\w$]*\(\d+\))""")
            .find(script)?.groupValues?.get(1) ?: return null
        val code = "var globalThis=this;var console={log:function(){},warn:function(){},error:function(){}};" +
            script.substring(0, entry.range.first) + ";var __megaKey=" + expr + ";"
        var result: String? = null
        val t = Thread {
            val cx = org.mozilla.javascript.Context.enter()
            try {
                cx.optimizationLevel = -1
                val scope = cx.initSafeStandardObjects()
                cx.evaluateString(scope, code, "player", 1, null)
                val v = scope.get("__megaKey", scope)
                if (v is CharSequence) result = v.toString()
            } catch (_: Throwable) {
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        }
        t.isDaemon = true
        t.start()
        t.join(5_000)
        return result?.also { keyCache[sh] = it }
    }

    internal fun sign(url: String, key: String?): String? {
        if (key == null || Regex("[?&]token=", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        val m = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE).find(url) ?: return url
        val pathKey = m.groupValues[1].lowercase() + "/" + m.groupValues[2].lowercase()
        val payload = "${System.currentTimeMillis() / 1000 + 90}|$pathKey"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val token = enc.encodeToString(payload.toByteArray()) + "." + enc.encodeToString(mac.doFinal(payload.toByteArray()))
        return try { url.toHttpUrl().newBuilder().setQueryParameter("token", token).build().toString() } catch (_: Exception) { url }
    }
}
