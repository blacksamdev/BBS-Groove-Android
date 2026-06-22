package io.github.blacksamdev.groove.player

import android.content.Context
import android.net.Uri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    private var initialized = false

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
            onTrackChanged?.invoke(queue.currentTrack())
            onStateChanged?.invoke()
            // Réaligner la fenêtre autour de la nouvelle piste courante
            scope.launch { rebuildWindow(keepPlaying = true) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            onStateChanged?.invoke()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onStateChanged?.invoke()
        }
    }

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
        scope.launch { rebuildWindow(keepPlaying = wasPlaying) }
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
            val curStream = resolve(cur) ?: run { rebuilding = false; return }

            val items = mutableListOf<MediaItem>()
            val mapping = mutableListOf<Int>()

            // Précédent (si dispo) -> position 0
            val prevIdx = queue.peekPrevIndex()
            var curPos = 0
            if (prevIdx != null) {
                val pt = queue.trackAt(prevIdx)
                val ps = pt?.let { resolve(it) }
                if (pt != null && ps != null) {
                    items.add(buildItem(pt, ps)); mapping.add(prevIdx); curPos = 1
                }
            }

            // Courant
            items.add(buildItem(cur, curStream)); mapping.add(queue.currentIndex)

            // Suivant (si dispo)
            val nextIdx = queue.peekNextIndex()
            if (nextIdx != null) {
                val nt = queue.trackAt(nextIdx)
                val ns = nt?.let { resolve(it) }
                if (nt != null && ns != null) {
                    items.add(buildItem(nt, ns)); mapping.add(nextIdx)
                }
            }

            windowIndices.clear()
            windowIndices.addAll(mapping)

            player.setMediaItems(items, /* startIndex = */ curPos, /* startPositionMs = */ 0)
            player.prepare()
            player.playWhenReady = keepPlaying
            onTrackChanged?.invoke(cur)
            onStateChanged?.invoke()
        } finally {
            rebuilding = false
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
