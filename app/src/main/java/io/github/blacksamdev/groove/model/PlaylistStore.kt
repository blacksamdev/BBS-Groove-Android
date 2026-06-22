package io.github.blacksamdev.groove.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistance des playlists en JSON local (filesDir/playlists.json).
 * Léger, sans dépendance, sans permission. Lecture/écriture synchrone
 * (fichier petit). Pas de framework JSON externe : org.json (stdlib Android).
 */
class PlaylistStore(context: Context) {

    private val file = File(context.filesDir, "playlists.json")

    fun load(): MutableList<Playlist> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val out = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tracks = mutableListOf<Track>()
                val ta = o.optJSONArray("tracks") ?: JSONArray()
                for (j in 0 until ta.length()) {
                    val t = ta.getJSONObject(j)
                    tracks.add(
                        Track(
                            title      = t.optString("title"),
                            artist     = t.optString("artist"),
                            album      = t.optString("album"),
                            durationMs = t.optLong("durationMs"),
                            artworkUrl = t.optString("artworkUrl"),
                            spotifyId  = t.optString("spotifyId"),
                            webpageUrl = t.optString("webpageUrl"),
                        )
                    )
                }
                out.add(Playlist(o.optString("name"), tracks))
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(playlists: List<Playlist>) {
        val arr = JSONArray()
        for (p in playlists) {
            val o = JSONObject()
            o.put("name", p.name)
            val ta = JSONArray()
            for (t in p.tracks) {
                val to = JSONObject()
                to.put("title", t.title)
                to.put("artist", t.artist)
                to.put("album", t.album)
                to.put("durationMs", t.durationMs)
                to.put("artworkUrl", t.artworkUrl)
                to.put("spotifyId", t.spotifyId)
                to.put("webpageUrl", t.webpageUrl)
                ta.put(to)
            }
            o.put("tracks", ta)
            arr.put(o)
        }
        try { file.writeText(arr.toString()) } catch (e: Exception) { }
    }

    // ── Opérations de haut niveau ─────────────────────────────────────

    /** Crée une playlist vide (ignore si le nom existe déjà). */
    fun create(name: String): MutableList<Playlist> {
        val all = load()
        if (all.none { it.name == name } && name.isNotBlank()) {
            all.add(Playlist(name))
            save(all)
        }
        return all
    }

    /** Ajoute des titres à une playlist (créée si absente). */
    fun addTracks(name: String, tracks: List<Track>): MutableList<Playlist> {
        val all = load()
        val pl = all.firstOrNull { it.name == name } ?: Playlist(name).also { all.add(it) }
        pl.tracks.addAll(tracks)
        save(all)
        return all
    }

    fun deletePlaylist(name: String): MutableList<Playlist> {
        val all = load()
        all.removeAll { it.name == name }
        save(all)
        return all
    }

    fun removeTrack(name: String, index: Int): MutableList<Playlist> {
        val all = load()
        all.firstOrNull { it.name == name }?.let {
            if (index in it.tracks.indices) it.tracks.removeAt(index)
        }
        save(all)
        return all
    }
}
