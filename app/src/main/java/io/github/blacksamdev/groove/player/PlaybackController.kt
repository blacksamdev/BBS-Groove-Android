package io.github.blacksamdev.groove.player

import android.content.Context
import android.util.Log
import android.net.Uri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.android.gms.cast.framework.CastContext
import io.github.blacksamdev.groove.model.Track
import io.github.blacksamdev.groove.resolver.PythonBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cœur de lecture (singleton). Architecture "fenêtre glissante" :
 * Media3 tient une playlist de 3 items max [précédent, courant, suivant],
 * tous déjà résolus (URL de stream prête). Conséquences :
 *   - next/prev/enchaînement sont NATIFS Media3 -> exposés au système,
 *     donc pilotables depuis la voiture (AVRCP), le casque, la notif.
 *   - pas de trou STATE_ENDED entre les pistes, pas de perte de focus.
 *
 * GrooveQueue pilote l'ordre logique (shuffle/repeat) ; Media3 exécute.
 * Le mapping windowTracks relie chaque position Media3 à un index GrooveQueue.
 */
object PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val queue = GrooveQueue()

    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null

    var current: Player? = null
        private set

    var onStateChanged: (() -> Unit)? = null
    var onTrackChanged: ((Track?) -> Unit)? = null
    /** Messages d'état pour l'UI (résolution, erreurs réseau…). */
    var onStatus: ((String) -> Unit)? = null

    private var initialized = false

    // Réglages autoplay (alimentés par le service via setAutoplayConfig)
    private var autoplayMode: String = "off"
    private var lastfmApiKey: String = ""
    private var autoplayLoading = false

    fun setAutoplayConfig(mode: String, apiKey: String) {
        autoplayMode = mode
        lastfmApiKey = apiKey
    }

    // Mapping : position dans la playlist Media3 -> index réel dans queue.tracks
    private val windowIndices = mutableListOf<Int>()
    // Garde-fou pour éviter les reconstructions réentrantes
    private var rebuilding = false

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Media3 a changé de piste (UI, voiture, casque, ou fin auto).
            // On resynchronise GrooveQueue sur la nouvelle position, puis on
            // reconstruit la fenêtre [prec, courant, suiv] autour d'elle.
            if (rebuilding) return
            val player = current ?: return
            val pos = player.currentMediaItemIndex
            val realIndex = windowIndices.getOrNull(pos) ?: return
            queue.syncTo(realIndex)
            lastErrorRetryKey = null   // transition OK : réarmer le retry
            onTrackChanged?.invoke(queue.currentTrack())
            onStateChanged?.invoke()
            // Réaligner la fenêtre autour de la nouvelle piste courante
            scope.launch {
                rebuildWindow(keepPlaying = true)
                maybeAutoplay()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            onStateChanged?.invoke()
            if (state == Player.STATE_ENDED) {
                Log.d("BBSGroove", "STATE_ENDED atteint (fin de piste/ file)")
                // Fin réelle de lecture : si autoplay ON et file épuisée,
                // aller chercher la suite et relancer. C'est le filet qui
                // manquait -> le dernier morceau ne meurt plus en silence.
                scope.launch { onQueueEnded() }
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onStateChanged?.invoke()
        }

        override fun onPlayerError(error: PlaybackException) {
            // URL expirée ou flux invalide : on invalide le cache et on
            // re-résout UNE fois la piste courante (garde anti-boucle).
            val t = queue.currentTrack() ?: return
            val key = t.title + "|" + t.artist
            if (key == lastErrorRetryKey) {
                onStatus?.invoke("Lecture impossible : " + (error.message ?: "flux invalide"))
                return
            }
            lastErrorRetryKey = key
            t.streamUrl = null
            onStatus?.invoke("Flux expiré, nouvelle résolution…")
            scope.launch { rebuildWindow(keepPlaying = true, startFresh = true) }
        }
    }

    // Garde anti-boucle du retry sur erreur player (une re-résolution max/piste)
    private var lastErrorRetryKey: String? = null

    fun init(context: Context, castContext: CastContext?) {
        if (initialized) return
        initialized = true

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android) BBSGroove/1.0")
            .setAllowCrossProtocolRedirects(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(playerListener)
        exoPlayer = exo
        current = exo

        if (castContext != null) {
            val cast = CastPlayer(castContext)
            cast.addListener(playerListener)
            cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = switchTo(cast)
                override fun onCastSessionUnavailable() = exoPlayer?.let { switchTo(it) } ?: Unit
            })
            castPlayer = cast
        }
    }

    fun sessionPlayer(): Player = exoPlayer!!

    private fun switchTo(target: Player) {
        val cur = current ?: return
        if (cur === target) return
        val wasPlaying = cur.isPlaying
        cur.stop()
        cur.clearMediaItems()
        current = target
        scope.launch { rebuildWindow(keepPlaying = wasPlaying, startFresh = true) }
        onStateChanged?.invoke()
    }

    // ── Chargement / navigation ───────────────────────────────────────

    fun load(tracks: List<Track>) {
        queue.load(tracks)
        scope.launch { rebuildWindow(keepPlaying = true, startFresh = true) }
    }

    fun playAt(index: Int) {
        queue.goTo(index)
        scope.launch { rebuildWindow(keepPlaying = true, startFresh = true) }
    }

    /** next/prev manuels : on délègue à Media3 si la fenêtre le permet,
     *  sinon on reconstruit (cas bord de file). */
    fun next() {
        val player = current ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        } else {
            // bord de fenêtre : avancer la file logiquement puis reconstruire
            queue.goNext() ?: return
            scope.launch { rebuildWindow(keepPlaying = true, startFresh = true) }
        }
    }

    fun prev() {
        val player = current ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            player.play()
        } else {
            queue.goPrev() ?: return
            scope.launch { rebuildWindow(keepPlaying = true, startFresh = true) }
        }
    }

    // ── Construction de la fenêtre glissante ──────────────────────────

    /**
     * Reconstruit la playlist Media3 = [prec?, courant, suiv?], chaque item
     * résolu (URL stream). Place le curseur sur la piste courante.
     *
     * startFresh = true : on (re)pose toute la fenêtre et on démarre la lecture
     *   du courant (utilisé au load / playAt / saut hors fenêtre).
     * startFresh = false : simple réalignement après une transition Media3
     *   (on complète prec/suiv autour du courant sans casser la lecture).
     */
    private suspend fun rebuildWindow(keepPlaying: Boolean, startFresh: Boolean = false) {
        val player = current ?: return
        val cur = queue.currentTrack() ?: return

        if (!startFresh && !rebuilding) {
            // Réalignement léger : s'assurer que suiv et prec existent, sans
            // interrompre la lecture en cours. Si la fenêtre est déjà correcte
            // (courant au milieu, voisins présents), on complète seulement.
            ensureNeighbors(player)
            return
        }

        rebuilding = true
        try {
            // 1) Résoudre et lancer le COURANT tout de suite (démarrage rapide).
            onStatus?.invoke("Résolution…")
            val curStream = resolve(cur)
            if (curStream == null) {
                // Échec VISIBLE (réseau, yt-dlp, région…) au lieu du silence.
                onStatus?.invoke("Échec de résolution — vérifie le réseau et réessaie")
                return
            }

            windowIndices.clear()
            windowIndices.add(queue.currentIndex)
            player.setMediaItems(listOf(buildItem(cur, curStream)), 0, 0)
            player.prepare()
            player.playWhenReady = keepPlaying
            onTrackChanged?.invoke(cur)
            onStateChanged?.invoke()
            onStatus?.invoke("")
        } finally {
            rebuilding = false
        }

        // 2) Compléter prec/suiv en arrière-plan, sans bloquer le son.
        scope.launch { fillNeighbors() }
    }

    /** Ajoute précédent (en tête) et suivant (en queue) autour du courant. */
    private suspend fun fillNeighbors() {
        val player = current ?: return

        // Suivant d'abord (le plus utile : enchaînement + bouton next voiture)
        if (!player.hasNextMediaItem()) {
            val nextIdx = queue.peekNextIndex()
            if (nextIdx != null) {
                val nt = queue.trackAt(nextIdx)
                val ns = nt?.let { resolve(it) }
                if (nt != null && ns != null && current === player) {
                    player.addMediaItem(buildItem(nt, ns))
                    windowIndices.add(nextIdx)
                }
            }
        }
        // Puis précédent (bouton prev voiture instantané)
        if (!player.hasPreviousMediaItem()) {
            val prevIdx = queue.peekPrevIndex()
            if (prevIdx != null) {
                val pt = queue.trackAt(prevIdx)
                val ps = pt?.let { resolve(it) }
                if (pt != null && ps != null && current === player) {
                    player.addMediaItem(0, buildItem(pt, ps))
                    windowIndices.add(0, prevIdx)
                }
            }
        }
    }

    /** Complète prec/suiv autour du courant sans recréer toute la playlist. */
    private suspend fun ensureNeighbors(player: Player) {
        // Suivant manquant ?
        if (!player.hasNextMediaItem()) {
            val nextIdx = queue.peekNextIndex()
            if (nextIdx != null) {
                val nt = queue.trackAt(nextIdx)
                val ns = nt?.let { resolve(it) }
                if (nt != null && ns != null) {
                    player.addMediaItem(buildItem(nt, ns))
                    windowIndices.add(nextIdx)
                }
            }
        }
        // On limite la fenêtre à 3 : si plus de 3 items et courant pas en tête,
        // on élague l'extrémité la plus éloignée.
        while (player.mediaItemCount > 3) {
            val curPos = player.currentMediaItemIndex
            if (curPos > 1) {
                player.removeMediaItem(0)
                if (windowIndices.isNotEmpty()) windowIndices.removeAt(0)
            } else {
                val last = player.mediaItemCount - 1
                player.removeMediaItem(last)
                if (windowIndices.isNotEmpty()) windowIndices.removeAt(windowIndices.size - 1)
            }
        }
    }

    /**
     * Autoplay : si activé et qu'on atteint la fin de la file (aucune piste
     * suivante), récupère des titres similaires de la piste courante et les
     * ajoute à la file. Déclenché uniquement en fin de file complète.
     */
    /**
     * Appelé quand on ARRIVE sur le dernier morceau (transition) : précharge
     * l'autoplay en avance pour que la suite soit prête avant la fin.
     */
    private suspend fun maybeAutoplay() {
        if (autoplayMode == "off") return
        if (queue.peekNextIndex() != null) return   // pas encore en fin de file
        Log.d("BBSGroove", "Fin de file en approche -> préchargement autoplay ($autoplayMode)")
        fetchAndAppendSimilar(relaunch = false)
    }

    /**
     * Appelé sur STATE_ENDED : la lecture s'est arrêtée. Si autoplay ON et
     * qu'on peut trouver une suite, on l'ajoute ET on relance la lecture.
     * C'est le filet de sécurité qui empêche le dernier morceau de mourir.
     */
    private suspend fun onQueueEnded() {
        if (autoplayMode == "off") {
            Log.d("BBSGroove", "STATE_ENDED, autoplay off -> arrêt normal")
            return
        }
        val player = current ?: return
        // S'il reste un suivant dans la file (déjà ajouté par le préchargement),
        // on relance simplement dessus.
        if (queue.peekNextIndex() != null) {
            Log.d("BBSGroove", "STATE_ENDED mais suivant dispo -> relance")
            val t = queue.goNext()
            if (t != null) { onTrackChanged?.invoke(t); rebuildWindow(keepPlaying = true, startFresh = true) }
            return
        }
        // Sinon on va chercher des similaires MAINTENANT et on relance.
        Log.d("BBSGroove", "STATE_ENDED en fin de file -> autoplay de secours")
        fetchAndAppendSimilar(relaunch = true)
    }

    /**
     * Cœur de l'autoplay : récupère des titres similaires du morceau courant,
     * les ajoute à la file, et (si relaunch) relance la lecture dessus.
     * Robuste : gère l'échec réseau, log chaque étape, un seul appel à la fois.
     */
    private suspend fun fetchAndAppendSimilar(relaunch: Boolean) {
        if (autoplayLoading) { Log.d("BBSGroove", "autoplay déjà en cours, skip"); return }
        val cur = queue.currentTrack() ?: return
        autoplayLoading = true
        try {
            onStatus?.invoke("Recherche de titres similaires…")
            Log.d("BBSGroove", "suggest($autoplayMode) pour ${cur.artist} - ${cur.title}")
            val similar = try {
                PythonBridge.suggest(autoplayMode, cur.artist, cur.title, lastfmApiKey)
            } catch (e: Exception) {
                Log.e("BBSGroove", "suggest a échoué: ${e.message}"); emptyList()
            }
            Log.d("BBSGroove", "suggest a renvoyé ${similar.size} titre(s)")

            val known = queue.tracks.map { it.title + "|" + it.artist }.toHashSet()
            val fresh = similar.filter { (it.title + "|" + it.artist) !in known }

            if (fresh.isEmpty()) {
                Log.d("BBSGroove", "aucun titre similaire exploitable")
                onStatus?.invoke("Aucune suggestion trouvée")
                return
            }

            val firstNewIndex = queue.tracks.size
            queue.tracks.addAll(fresh)
            queue.appendOrder(fresh.size)
            onStateChanged?.invoke()
            Log.d("BBSGroove", "${fresh.size} titre(s) ajouté(s) à la file")
            onStatus?.invoke("")

            if (relaunch) {
                // Le player était arrêté : on saute au premier nouveau titre et on relance.
                queue.goTo(firstNewIndex)
                onTrackChanged?.invoke(queue.currentTrack())
                rebuildWindow(keepPlaying = true, startFresh = true)
            } else {
                // Préchargement : juste compléter la fenêtre Media3 avec le suivant.
                current?.let { ensureNeighbors(it) }
            }
        } finally {
            autoplayLoading = false
        }
    }

    private suspend fun resolve(track: Track): String? {
        track.streamUrl?.let { return it }
        val s = try { PythonBridge.resolveStream(track) } catch (e: Exception) { null }
        if (s != null) track.streamUrl = s
        return s
    }

    private fun buildItem(track: Track, streamUrl: String): MediaItem =
        MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(
                        if (track.artworkUrl.isNotEmpty()) Uri.parse(track.artworkUrl) else null
                    )
                    .build()
            )
            .build()

    // ── Contrôles ─────────────────────────────────────────────────────

    fun togglePause() {
        val p = current ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun setRepeat(enabled: Boolean) {
        queue.repeat = enabled
        // Repeat = répéter la piste courante (REPEAT_MODE_ONE), natif Media3 :
        // fonctionne aussi via la voiture / notif.
        current?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setShuffle(enabled: Boolean) {
        queue.setShuffle(enabled)
        scope.launch { rebuildWindow(keepPlaying = isPlaying, startFresh = true) }
    }

    val isPlaying: Boolean get() = current?.isPlaying ?: false
    val position: Long get() = current?.currentPosition ?: 0
    val duration: Long get() = (current?.duration ?: 0).coerceAtLeast(0)
    fun seekTo(ms: Long) { current?.seekTo(ms) }

    fun currentTrack(): Track? = queue.currentTrack()

    fun release() {
        exoPlayer?.release()
        castPlayer?.release()
        exoPlayer = null
        castPlayer = null
        current = null
        initialized = false
        windowIndices.clear()
    }
}
