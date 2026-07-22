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

    /**
     * Active/désactive l'aléatoire SANS perturber la lecture en cours :
     * la partie déjà jouée et la piste courante gardent leur place ; seuls
     * les morceaux restants sont réorganisés. Garantit qu'on écoute bien
     * toute la playlist avant de basculer sur l'autoplay.
     */
    fun setShuffle(enabled: Boolean) {
        shuffle = enabled
        if (tracks.isEmpty()) { rebuildOrder(); return }

        if (order.isEmpty() || orderPos !in order.indices) {
            rebuildOrder()
            orderPos = order.indexOf(currentIndex).coerceAtLeast(0)
            return
        }

        // Historique (jusqu'à la piste courante incluse) : inchangé
        val head = order.subList(0, orderPos + 1).toMutableList()
        // Reste à jouer : mélangé si shuffle, remis en ordre naturel sinon
        val tail = order.subList(orderPos + 1, order.size).toMutableList()
        if (enabled) {
            tail.shuffle()
        } else {
            tail.sort()
        }

        order = (head + tail).toMutableList()
        // orderPos ne bouge pas : la piste courante reste où elle est
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

    /**
     * Étend l'ordre de lecture après ajout de `count` pistes en fin de `tracks`
     * (autoplay). Les nouveaux indices sont ajoutés à la fin de l'ordre ;
     * si shuffle est actif, ils sont mélangés entre eux puis appondus.
     */
    fun appendOrder(count: Int) {
        if (count <= 0) return
        val start = tracks.size - count
        val newIdx = (start until tracks.size).toMutableList()
        if (shuffle) newIdx.shuffle()
        order.addAll(newIdx)
    }

    fun isEmpty() = tracks.isEmpty()
    fun size() = tracks.size
}
