package io.anibiar.app.core

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The last network calls, shown in Settings > Connection report, so a failure can be read instead of guessed. */
object DebugLog {
    val lines = mutableStateListOf<String>()

    @Synchronized
    fun add(text: String) {
        lines.add(0, text)
        while (lines.size > 80) lines.removeAt(lines.size - 1)
    }
}

class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

object Net {
    /**
     * Chrome's network stack (Cronet) from Google Play services: HTTP/3 over QUIC, HTTP/2, Brotli and a disk cache.
     * Null when this phone has no Play services, in which case plain OkHttp (HTTP/2) is used.
     */
    val cronet: org.chromium.net.CronetEngine? by lazy {
        try {
            val dir = java.io.File(io.anibiar.app.App.ctx.cacheDir, "cronet").apply { mkdirs() }
            org.chromium.net.CronetEngine.Builder(io.anibiar.app.App.ctx)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .setStoragePath(dir.absolutePath)
                .enableHttpCache(org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISK, 30L * 1024 * 1024)
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    /** What the connection report shows: which stack is carrying the requests. */
    val stackName: String get() = if (cronet != null) "Cronet (HTTP/3 + HTTP/2)" else "OkHttp (HTTP/2)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .apply {
            val engine = cronet
            if (engine != null) {
                try {
                    addInterceptor(com.google.net.cronet.okhttptransport.CronetInterceptor.newBuilder(engine).build())
                } catch (_: Throwable) {
                }
            }
        }
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun label(url: String): String {
        val u = try { java.net.URI(url) } catch (_: Exception) { return url.take(60) }
        return (u.host ?: "") + (u.path ?: "").take(40)
    }

    /**
     * One request. Throws IOException when the server can't be reached (or the time runs out); any HTTP status,
     * including errors, comes back as a result so callers can read the server's message.
     */
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String = "application/json",
        timeoutMs: Long = 15_000,
        bytes: ByteArray? = null,
    ): HttpResult {
        val started = System.currentTimeMillis()
        val reqBody: RequestBody? = when {
            bytes != null -> bytes.toRequestBody(contentType.toMediaType())
            body != null -> body.toRequestBody(contentType.toMediaType())
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(url).method(method, reqBody)
        for ((k, v) in headers) builder.header(k, v)
        val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(builder.build())
        var proto = ""
        val result = try {
            call.await().use { res -> proto = res.protocol.toString(); HttpResult(res.code, res.body?.string() ?: "") }
        } catch (e: IOException) {
            DebugLog.add("FAIL  ${System.currentTimeMillis() - started}ms  $method ${label(url)}  ${e.javaClass.simpleName}: ${e.message ?: ""}".take(200))
            throw e
        }
        DebugLog.add("${result.code}  ${System.currentTimeMillis() - started}ms  $proto  $method ${label(url)}")
        return result
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): HttpResult =
        request("GET", url, headers, null, timeoutMs = timeoutMs)

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): HttpResult =
        request("POST", url, headers, json, timeoutMs = timeoutMs)
}
