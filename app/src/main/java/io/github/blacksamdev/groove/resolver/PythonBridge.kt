package io.github.blacksamdev.groove.resolver

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import io.github.blacksamdev.groove.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pont Kotlin <-> Python (Chaquopy).
 * Encapsule resolver.py (yt-dlp) et spotify_source.py (spotifyscraper).
 * Tous les appels Python sont suspendus sur Dispatchers.IO.
 */
object PythonBridge {

    private val py: Python by lazy { Python.getInstance() }
    private val resolverMod: PyObject by lazy { py.getModule("resolver") }
    private val spotifyMod:  PyObject by lazy { py.getModule("spotify_source") }

    /** Détecte si l'entrée est une URL Spotify/Deezer ou une recherche libre. */
    fun isUrl(input: String): Boolean {
        val s = input.trim()
        return s.startsWith("http") || s.contains("spotify.com") || s.contains("deezer.com")
    }

    /** Résout une URL Spotify -> liste de Track (métadonnées, sans stream). */
    suspend fun getSpotifyTracks(url: String): List<Track> = withContext(Dispatchers.IO) {
        val result = spotifyMod.callAttr("get_tracks", url)
        pyListToTracks(result)
    }

    /** Suggestions de pistes similaires (autoplay). mode: youtube|lastfm|off. */
    suspend fun suggest(mode: String, artist: String, title: String,
                        apiKey: String = "", limit: Int = 15): List<Track> =
        withContext(Dispatchers.IO) {
            val mod = py.getModule("autoplay")
            val result = mod.callAttr("suggest", mode, artist, title, apiKey, limit)
            pyListToTracks(result)
        }

    /** Recherche libre YouTube -> liste de Track. */
    suspend fun search(query: String, limit: Int = 15): List<Track> = withContext(Dispatchers.IO) {
        val result = resolverMod.callAttr("search", query, limit)
        pyListToTracks(result)
    }

    /** Résout une Track -> URL de stream audio directe (jouable par ExoPlayer). */
    suspend fun resolveStream(track: Track): String? = withContext(Dispatchers.IO) {
        // Si on a déjà une URL de page YouTube (issue de search), extraire son flux
        // directement : plus fiable et plus rapide qu'une nouvelle recherche.
        val r = if (track.webpageUrl.isNotEmpty()) {
            resolverMod.callAttr("resolve_from_url", track.webpageUrl)
        } else {
            resolverMod.callAttr("resolve", track.artist, track.title, track.durationMs)
        }
        r?.toString()
    }

    /** Candidats (versions) pour un titre. */
    suspend fun resolveCandidates(track: Track): List<Candidate> = withContext(Dispatchers.IO) {
        val r = resolverMod.callAttr(
            "resolve_candidates", track.artist, track.title, track.durationMs
        )
        val out = ArrayList<Candidate>()
        for (item in r.asList()) {
            val m = item.asMap()
            out.add(
                Candidate(
                    url        = m[k("url")]?.toString() ?: continue,
                    webpageUrl = m[k("webpage_url")]?.toString() ?: "",
                    title      = m[k("title")]?.toString() ?: "",
                    channel    = m[k("channel")]?.toString() ?: "",
                    durationS  = m[k("duration_s")]?.toDouble()?.toInt() ?: 0,
                )
            )
        }
        out
    }

    /** Résout une URL YouTube pérenne -> stream frais. */
    suspend fun resolveFromUrl(ytUrl: String): String? = withContext(Dispatchers.IO) {
        resolverMod.callAttr("resolve_from_url", ytUrl)?.toString()
    }

    // ── Helpers de conversion ─────────────────────────────────────────

    private fun k(s: String): PyObject = PyObject.fromJava(s)

    private fun pyListToTracks(pyList: PyObject?): List<Track> {
        if (pyList == null) return emptyList()
        val out = ArrayList<Track>()
        for (item in pyList.asList()) {
            val m = item.asMap()
            out.add(
                Track(
                    title      = m[k("title")]?.toString() ?: "",
                    artist     = m[k("artist")]?.toString() ?: "",
                    album      = m[k("album")]?.toString() ?: "",
                    durationMs = m[k("duration_ms")]?.toLong() ?: 0L,
                    artworkUrl = m[k("artwork_url")]?.toString() ?: "",
                    spotifyId  = m[k("spotify_id")]?.toString() ?: "",
                    webpageUrl = m[k("webpage_url")]?.toString() ?: "",
                )
            )
        }
        return out
    }

    data class Candidate(
        val url: String,
        val webpageUrl: String,
        val title: String,
        val channel: String,
        val durationS: Int,
    )
}
