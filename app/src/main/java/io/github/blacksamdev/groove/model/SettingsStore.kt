package io.github.blacksamdev.groove.model

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Réglages de l'app (JSON local). Pour l'instant : mode d'autoplay + clé Last.fm.
 * Reproduit le OptionsDialog du desktop (off / youtube / lastfm).
 */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")

    var autoplayMode: String = "off"   // "off" | "youtube" | "lastfm"
        private set
    var lastfmApiKey: String = ""
        private set
    var karaokeMode: String = "compact"   // "compact" | "fullscreen"
        private set

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            autoplayMode = o.optString("autoplayMode", "off")
            lastfmApiKey = o.optString("lastfmApiKey", "")
            karaokeMode = o.optString("karaokeMode", "compact")
        } catch (e: Exception) { }
    }

    private fun save() {
        try {
            val o = JSONObject()
            o.put("autoplayMode", autoplayMode)
            o.put("lastfmApiKey", lastfmApiKey)
            o.put("karaokeMode", karaokeMode)
            file.writeText(o.toString())
        } catch (e: Exception) { }
    }

    fun setAutoplayMode(mode: String) { autoplayMode = mode; save() }
    fun setLastfmApiKey(key: String) { lastfmApiKey = key; save() }
    fun setKaraokeMode(mode: String) { karaokeMode = mode; save() }
}
