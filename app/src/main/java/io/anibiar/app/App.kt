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
        Auth.init() // the profile loads in the background; the app opens right away
    }

    companion object {
        lateinit var ctx: Context
            private set

        /** Work that must outlive a screen (cache refreshes, library saves). */
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
