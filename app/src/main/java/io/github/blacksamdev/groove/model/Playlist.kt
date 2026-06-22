package io.github.blacksamdev.groove.model

/**
 * Playlist perso grOOve. Stockée en JSON local (métadonnées seules ;
 * le flux audio est résolu à la lecture, rien de périssable n'est persisté).
 */
data class Playlist(
    val name: String,
    val tracks: MutableList<Track> = mutableListOf(),
)
