package io.github.blacksamdev.groove.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistance des playlists en JSON local (filesDir/playlists.json).
 * Léger, sans dépendance, sans permission. Lecture/écriture synchrone
 * (fichier petit). Pas de framework JSON externe : org.json (stdlib Android).
 *
 * Gère aussi l'export / import d'une playlist au format .bbsgroove (JSON
 * autonome) pour le partage entre appareils — hors-ligne, sans serveur.
 */
class PlaylistStore(context: Context) {

    private val file = File(context.filesDir, "playlists.json")

    companion object {
        /** Version du format de fichier .bbsgroove (pour compatibilité future). */
        const val EXPORT_VERSION = 1
        const val EXPORT_EXTENSION = "bbsgroove"
    }

    // ── (Dé)sérialisation d'un titre ──────────────────────────────────

    private fun trackToJson(t: Track): JSONObject = JSONObject().apply {
        put("title", t.title)
        put("artist", t.artist)
        put("album", t.album)
        put("durationMs", t.durationMs)
        put("artworkUrl", t.artworkUrl)
        put("spotifyId", t.spotifyId)
        put("webpageUrl", t.webpageUrl)
    }

    private fun trackFromJson(o: JSONObject): Track = Track(
        title      = o.optString("title"),
        artist     = o.optString("artist"),
        album      = o.optString("album"),
        durationMs = o.optLong("durationMs"),
        artworkUrl = o.optString("artworkUrl"),
        spotifyId  = o.optString("spotifyId"),
        webpageUrl = o.optString("webpageUrl"),
    )

    // ── Persistance globale ───────────────────────────────────────────

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
                    tracks.add(trackFromJson(ta.getJSONObject(j)))
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
            for (t in p.tracks) ta.put(trackToJson(t))
            o.put("tracks", ta)
            arr.put(o)
        }
        try { file.writeText(arr.toString()) } catch (e: Exception) { }
    }

    // ── Opérations de haut niveau ─────────────────────────────────────

    fun hasPlaylist(name: String): Boolean = load().any { it.name == name }

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

    /** Remplace intégralement les titres d'une playlist (créée si absente). */
    fun replacePlaylist(name: String, tracks: List<Track>): MutableList<Playlist> {
        val all = load()
        val pl = all.firstOrNull { it.name == name }
        if (pl != null) {
            pl.tracks.clear()
            pl.tracks.addAll(tracks)
        } else {
            all.add(Playlist(name, tracks.toMutableList()))
        }
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

    /** Nom unique dérivé de `base` : "Rock", "Rock (2)", "Rock (3)"… */
    fun uniqueName(base: String): String {
        val existing = load().map { it.name }.toHashSet()
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    // ── Export / import .bbsgroove ────────────────────────────────────

    /** Sérialise une playlist en JSON autonome (contenu d'un fichier .bbsgroove). */
    fun exportJson(playlist: Playlist): String {
        val o = JSONObject()
        o.put("bbs_groove_playlist", EXPORT_VERSION)
        o.put("name", playlist.name)
        val ta = JSONArray()
        for (t in playlist.tracks) ta.put(trackToJson(t))
        o.put("tracks", ta)
        return o.toString(2)
    }

    /**
     * Parse le contenu d'un fichier .bbsgroove en Playlist.
     * Accepte le format versionné ({bbs_groove_playlist, name, tracks}) et,
     * par tolérance, un objet {name, tracks} brut. Retourne null si invalide.
     */
    fun parseImport(text: String): Playlist? {
        return try {
            val o = JSONObject(text)
            val name = o.optString("name").trim()
            val ta = o.optJSONArray("tracks") ?: return null
            if (name.isEmpty()) return null
            val tracks = mutableListOf<Track>()
            for (j in 0 until ta.length()) {
                tracks.add(trackFromJson(ta.getJSONObject(j)))
            }
            Playlist(name, tracks)
        } catch (e: Exception) {
            null
        }
    }
}
