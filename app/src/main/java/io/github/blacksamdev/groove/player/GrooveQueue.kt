package io.github.blacksamdev.groove.player

import io.github.blacksamdev.groove.model.Track

/**
 * File de lecture — porte la logique de bbs_groove.core.playlist (desktop).
 * Gère index courant, shuffle (ordre mélangé) et repeat (titre courant).
 */
class GrooveQueue {

    val tracks = mutableListOf<Track>()
    var currentIndex = 0
        private set

    var shuffle = false
        private set
    var repeat = false

    private var order = mutableListOf<Int>()   // ordre de lecture (indices dans tracks)
    private var orderPos = 0

    fun load(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        rebuildOrder()
        orderPos = 0
        currentIndex = order.firstOrNull() ?: 0
    }

    fun currentTrack(): Track? = tracks.getOrNull(currentIndex)

    fun setShuffle(enabled: Boolean) {
        val cur = currentIndex
        shuffle = enabled
        rebuildOrder()
        // Repositionner sur le titre courant dans le nouvel ordre
        orderPos = order.indexOf(cur).coerceAtLeast(0)
    }

    private fun rebuildOrder() {
        order = tracks.indices.toMutableList()
        if (shuffle) order.shuffle()
    }

    /** Avance. Retourne le nouveau Track, ou null si fin de file (sans repeat). */
    fun goNext(): Track? {
        if (tracks.isEmpty()) return null
        if (repeat) return currentTrack()
        if (orderPos + 1 >= order.size) return null
        orderPos++
        currentIndex = order[orderPos]
        return currentTrack()
    }

    fun goPrev(): Track? {
        if (tracks.isEmpty()) return null
        if (orderPos - 1 < 0) return currentTrack()
        orderPos--
        currentIndex = order[orderPos]
        return currentTrack()
    }

    fun goTo(index: Int): Track? {
        if (index !in tracks.indices) return null
        currentIndex = index
        orderPos = order.indexOf(index).coerceAtLeast(0)
        return currentTrack()
    }

    // ── Helpers fenêtre glissante (sans muter l'état) ─────────────────

    /** Index réel (dans tracks) du suivant selon l'ordre courant, ou null. */
    fun peekNextIndex(): Int? {
        if (tracks.isEmpty()) return null
        if (orderPos + 1 >= order.size) return null
        return order[orderPos + 1]
    }

    /** Index réel du précédent selon l'ordre courant, ou null. */
    fun peekPrevIndex(): Int? {
        if (tracks.isEmpty()) return null
        if (orderPos - 1 < 0) return null
        return order[orderPos - 1]
    }

    fun trackAt(index: Int): Track? = tracks.getOrNull(index)

    /** Positionne sur un index réel sans renvoyer (resync depuis Media3). */
    fun syncTo(index: Int) {
        if (index !in tracks.indices) return
        currentIndex = index
        orderPos = order.indexOf(index).coerceAtLeast(0)
    }

    fun isEmpty() = tracks.isEmpty()
    fun size() = tracks.size
}
