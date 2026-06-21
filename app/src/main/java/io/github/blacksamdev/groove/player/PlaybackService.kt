package io.github.blacksamdev.groove.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.framework.CastContext
import io.github.blacksamdev.groove.ui.MainActivity

/**
 * Service de lecture média (Media3 MediaSessionService).
 *
 * Héberge la MediaSession bâtie sur le player du PlaybackController. Media3
 * génère automatiquement la notification média (avec artwork via l'ArtworkUri
 * des MediaItem), gère l'écran verrouillé, les boutons casque/Bluetooth et le
 * passage en foreground service tant que la lecture est active.
 *
 * Conséquence : lecture + enchaînement continuent en arrière-plan / écran éteint
 * (comportement Spotify/Deezer). Un balayage volontaire de l'app arrête tout.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val castContext = try { CastContext.getSharedInstance(this) } catch (e: Exception) { null }
        PlaybackController.init(this, castContext)

        // Intent pour rouvrir l'app au tap sur la notif
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, PlaybackController.sessionPlayer())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App balayée hors des récentes : si rien ne joue, on arrête le service.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
