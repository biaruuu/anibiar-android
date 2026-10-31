package io.anibiar.app

import android.app.Application
import android.content.Context
import io.anibiar.app.data.Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        // remember the last crash so the connection report in Settings can show it
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = android.util.Log.getStackTraceString(e).take(1800)
                io.anibiar.app.core.Prefs.putString("crash.last", "${java.util.Date()}  [${t.name}]\n$text")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
        Auth.init() // the profile loads in the background; the app opens right away
    }

    companion object {
        lateinit var ctx: Context
            private set

        /** Work that must outlive a screen (cache refreshes, library saves). */
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
