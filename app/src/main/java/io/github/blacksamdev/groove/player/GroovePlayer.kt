package io.github.blacksamdev.groove.player

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastContext
import io.github.blacksamdev.groove.model.Track

/**
 * Contrôleur de lecture. Remplace mpv (desktop) par Media3.
 * Bascule automatiquement entre ExoPlayer (local) et CastPlayer (Chromecast)
 * selon la disponibilité d'une session Cast.
 */
class GroovePlayer(
    context: Context,
    castContext: CastContext?,
    private val onTrackEnded: () -> Unit,
) {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private val castPlayer: CastPlayer? =
        castContext?.let { CastPlayer(it) }

    /** Lecteur actif courant : Cast si session connectée, sinon local. */
    var current: Player = exoPlayer
        private set

    private val endListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onTrackEnded()
        }
    }

    init {
        exoPlayer.addListener(endListener)
        castPlayer?.addListener(endListener)
        castPlayer?.setSessionAvailabilityListener(object :
            androidx.media3.cast.SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchTo(castPlayer!!)
            override fun onCastSessionUnavailable() = switchTo(exoPlayer)
        })
    }

    private fun switchTo(target: Player) {
        if (current === target) return
        val pos = current.currentPosition
        val wasPlaying = current.isPlaying
        val item = current.currentMediaItem
        current.stop()
        current.clearMediaItems()
        current = target
        if (item != null) {
            target.setMediaItem(item, pos)
            target.prepare()
            target.playWhenReady = wasPlaying
        }
    }

    fun play(track: Track, streamUrl: String) {
        val item = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build()
            )
            .build()
        current.setMediaItem(item)
        current.prepare()
        current.play()
    }

    fun togglePause() {
        if (current.isPlaying) current.pause() else current.play()
    }

    val isPlaying: Boolean get() = current.isPlaying
    val position: Long get() = current.currentPosition
    val duration: Long get() = current.duration.coerceAtLeast(0)

    fun seekTo(ms: Long) = current.seekTo(ms)
    fun setVolume(v: Float) { exoPlayer.volume = v.coerceIn(0f, 1f) }
    fun stop() { current.stop() }

    fun release() {
        exoPlayer.removeListener(endListener)
        exoPlayer.release()
        castPlayer?.release()
    }
}
