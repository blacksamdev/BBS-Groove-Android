package io.github.blacksamdev.groove

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Application — initialise l'interpréteur Python (Chaquopy) une seule fois.
 */
class GrooveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
