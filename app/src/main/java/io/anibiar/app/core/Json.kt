package io.anibiar.app.core

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers over org.json so parsing code stays short and never throws on missing keys. */
fun JSONObject.str(key: String): String? {
    if (isNull(key)) return null
    val v = opt(key)
    return if (v is String) v.trim().takeIf { it.isNotEmpty() } else null
}

fun JSONObject.int(key: String): Int? {
    if (isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
}

fun JSONObject.long(key: String): Long? {
    if (isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toDoubleOrNull()?.toLong()
        else -> null
    }
}

fun JSONObject.dbl(key: String): Double? {
    if (isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}

fun JSONObject.bool(key: String): Boolean? {
    if (isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Boolean -> v
        is String -> v.equals("true", true)
        else -> null
    }
}

fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)
fun JSONObject.arr(key: String): JSONArray? = if (isNull(key)) null else optJSONArray(key)

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val v = opt(i)
        if (v is String) out.add(v)
    }
    return out
}

fun parseObject(text: String): JSONObject? = try { JSONObject(text) } catch (_: Exception) { null }
fun parseArray(text: String): JSONArray? = try { JSONArray(text) } catch (_: Exception) { null }

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
    return o
}
