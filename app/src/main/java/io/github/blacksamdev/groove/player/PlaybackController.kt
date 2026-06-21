package io.github.blacksamdev.groove.player

import android.content.Context
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
 * Cœur de lecture partagé (singleton applicatif).
 *
 * Détient la file (GrooveQueue), le player Media3 (ExoPlayer + CastPlayer) et
 * orchestre la résolution yt-dlp. Vit dans le PlaybackService — donc la lecture
 * et l'enchaînement automatique continuent quand l'app passe en arrière-plan
 * ou que l'écran s'éteint (comportement Spotify/Deezer).
 *
 * L'UI (MainActivity) observe cet état mais ne le détient pas.
 */
object PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val queue = GrooveQueue()

    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null

    /** Player actif : Cast si session connectée, sinon ExoPlayer local. */
    var current: Player? = null
        private set

    /** Callbacks pour que l'UI se rafraîchisse (titre courant, état play/pause). */
    var onStateChanged: (() -> Unit)? = null
    var onTrackChanged: ((Track?) -> Unit)? = null

    private var initialized = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) advanceAuto()
            onStateChanged?.invoke()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onStateChanged?.invoke()
        }
    }

    /** Initialise les players. Idempotent — appelé par le service à sa création. */
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
            .setHandleAudioBecomingNoisy(true)   // pause au débranchement casque
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

    /** Player exposé à la MediaSession (toujours l'ExoPlayer : la session locale). */
    fun sessionPlayer(): Player = exoPlayer!!

    private fun switchTo(target: Player) {
        val cur = current ?: return
        if (cur === target) return
        val pos = cur.currentPosition
        val wasPlaying = cur.isPlaying
        val item = cur.currentMediaItem
        cur.stop()
        cur.clearMediaItems()
        current = target
        if (item != null) {
            target.setMediaItem(item, pos)
            target.prepare()
            target.playWhenReady = wasPlaying
        }
        onStateChanged?.invoke()
    }

    // ── File ──────────────────────────────────────────────────────────

    /** Charge une nouvelle file et démarre la première piste. */
    fun load(tracks: List<Track>) {
        queue.load(tracks)
        playCurrent()
    }

    fun playAt(index: Int) {
        queue.goTo(index)
        playCurrent()
    }

    fun next() {
        val t = queue.goNext() ?: return
        onTrackChanged?.invoke(t)
        resolveAndPlay(t)
    }

    fun prev() {
        val t = queue.goPrev() ?: return
        onTrackChanged?.invoke(t)
        resolveAndPlay(t)
    }

    /** Enchaînement automatique en fin de piste (STATE_ENDED). */
    private fun advanceAuto() {
        val t = queue.goNext() ?: return   // fin de file : s'arrête (autoplay similaires viendra ici)
        onTrackChanged?.invoke(t)
        resolveAndPlay(t)
    }

    private fun playCurrent() {
        val t = queue.currentTrack() ?: return
        onTrackChanged?.invoke(t)
        resolveAndPlay(t)
    }

    // ── Résolution + lecture ──────────────────────────────────────────

    private fun resolveAndPlay(track: Track) {
        val cached = track.streamUrl
        if (cached != null) {
            playStream(track, cached)
            return
        }
        scope.launch {
            val stream = try { PythonBridge.resolveStream(track) } catch (e: Exception) { null }
            if (stream != null) {
                track.streamUrl = stream
                playStream(track, stream)
            }
        }
    }

    private fun playStream(track: Track, streamUrl: String) {
        val player = current ?: return
        val item = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(
                        if (track.artworkUrl.isNotEmpty())
                            android.net.Uri.parse(track.artworkUrl) else null
                    )
                    .build()
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        onStateChanged?.invoke()
    }

    // ── Contrôles ─────────────────────────────────────────────────────

    fun togglePause() {
        val p = current ?: return
        if (p.isPlaying) p.pause() else p.play()
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
    }
}
