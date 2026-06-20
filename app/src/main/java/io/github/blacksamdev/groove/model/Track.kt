package io.github.blacksamdev.groove.model

/**
 * Représente un titre dans la file de lecture.
 * Mappé depuis les dicts Python (resolver.search / spotify_source.get_tracks).
 */
data class Track(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val artworkUrl: String = "",
    val spotifyId: String = "",
    val webpageUrl: String = "",
    // URL de stream résolue (remplie à la volée par le resolver yt-dlp)
    var streamUrl: String? = null,
) {
    val durationLabel: String
        get() {
            val s = (durationMs / 1000).toInt()
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }
}
