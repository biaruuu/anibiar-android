package io.anibiar.app.core

import android.content.Context
import android.content.SharedPreferences
import io.anibiar.app.App

/** Tiny wrapper over SharedPreferences. Every call is safe to make from any thread. */
object Prefs {
    private val sp: SharedPreferences by lazy { App.ctx.getSharedPreferences("anibiar", Context.MODE_PRIVATE) }

    fun getString(key: String): String? = try { sp.getString(key, null) } catch (_: Exception) { null }
    fun putString(key: String, value: String?) {
        try {
            sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        } catch (_: Exception) {
        }
    }

    fun getBool(key: String, default: Boolean): Boolean = try { sp.getBoolean(key, default) } catch (_: Exception) { default }
    fun putBool(key: String, value: Boolean) {
        try { sp.edit().putBoolean(key, value).apply() } catch (_: Exception) {}
    }

    fun getInt(key: String, default: Int): Int = try { sp.getInt(key, default) } catch (_: Exception) { default }
    fun putInt(key: String, value: Int) {
        try { sp.edit().putInt(key, value).apply() } catch (_: Exception) {}
    }

    fun getFloat(key: String, default: Float): Float = try { sp.getFloat(key, default) } catch (_: Exception) { default }
    fun putFloat(key: String, value: Float) {
        try { sp.edit().putFloat(key, value).apply() } catch (_: Exception) {}
    }
}
