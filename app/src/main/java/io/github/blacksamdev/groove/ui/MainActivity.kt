package io.github.blacksamdev.groove.ui

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.databinding.ActivityMainBinding
import io.github.blacksamdev.groove.model.Track
import io.github.blacksamdev.groove.player.PlaybackController
import io.github.blacksamdev.groove.player.PlaybackService
import io.github.blacksamdev.groove.resolver.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Écran principal — désormais un CLIENT du PlaybackService.
 *
 * La lecture, la file et la résolution vivent dans le service (via
 * PlaybackController), donc elles survivent à l'arrière-plan. MainActivity
 * affiche l'état et envoie des commandes ; elle ne détient plus le player.
 * La connexion MediaController garantit que démarrer la lecture lance le
 * foreground service (et donc la notif média).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackAdapter

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        volumeControlStream = AudioManager.STREAM_MUSIC

        // Pré-initialise CastContext tôt (le service le refera, idempotent)
        try { CastContext.getSharedInstance(this) } catch (e: Exception) { }

        setupList()
        setupControls()
        startProgressLoop()

        // Branche les callbacks du controller -> rafraîchissement UI
        PlaybackController.onStateChanged = { runOnUiThread { refreshTransport() } }
        PlaybackController.onTrackChanged = { track ->
            runOnUiThread {
                adapter.setCurrent(PlaybackController.queue.currentIndex)
                track?.let { updateTrackPanel(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Connexion au MediaSessionService (démarre/attache la session)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            mediaController = future.get()
            // Si une lecture est déjà en cours (revenu de l'arrière-plan), resync l'UI
            restoreUiFromController()
        }, MoreExecutors.directExecutor())
        controllerFuture = future
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onStop()
    }

    // ── Liste ─────────────────────────────────────────────────────────

    private fun setupList() {
        adapter = TrackAdapter { index -> PlaybackController.playAt(index) }
        binding.queueList.layoutManager = LinearLayoutManager(this)
        binding.queueList.adapter = adapter
    }

    private fun setupControls() {
        binding.btnLoad.setOnClickListener { loadInput() }
        binding.urlInput.setOnEditorActionListener { _, _, _ -> loadInput(); true }

        binding.btnPlay.setOnClickListener { PlaybackController.togglePause() }
        binding.btnNext.setOnClickListener { PlaybackController.next() }
        binding.btnPrev.setOnClickListener { PlaybackController.prev() }

        binding.btnShuffle.setOnClickListener {
            val on = !PlaybackController.queue.shuffle
            PlaybackController.queue.setShuffle(on)
            binding.btnShuffle.alpha = if (on) 1f else 0.4f
        }
        binding.btnRepeat.setOnClickListener {
            PlaybackController.queue.repeat = !PlaybackController.queue.repeat
            binding.btnRepeat.alpha = if (PlaybackController.queue.repeat) 1f else 0.4f
        }
        binding.btnShuffle.alpha = if (PlaybackController.queue.shuffle) 1f else 0.4f
        binding.btnRepeat.alpha = if (PlaybackController.queue.repeat) 1f else 0.4f

        binding.seekBar.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                val dur = PlaybackController.duration
                if (dur > 0) PlaybackController.seekTo(dur * sb.progress / 1000)
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext, menu, R.id.media_route_menu_item
        )
        return true
    }

    // ── Chargement ────────────────────────────────────────────────────

    private fun loadInput() {
        val input = binding.urlInput.text.toString().trim()
        if (input.isEmpty()) return
        setStatus("Chargement…")
        lifecycleScope.launch {
            val tracks = try {
                if (PythonBridge.isUrl(input)) PythonBridge.getSpotifyTracks(input)
                else PythonBridge.search(input)
            } catch (e: Exception) {
                setStatus("Erreur : ${e.message}"); return@launch
            }
            if (tracks.isEmpty()) { setStatus("Aucun titre trouvé."); return@launch }
            adapter.submit(tracks)
            setStatus("${tracks.size} titre(s) chargé(s)")
            PlaybackController.load(tracks)
        }
    }

    // ── Sync UI <- état lecture ───────────────────────────────────────

    private fun restoreUiFromController() {
        val tracks = PlaybackController.queue.tracks
        if (tracks.isNotEmpty()) {
            adapter.submit(tracks)
            adapter.setCurrent(PlaybackController.queue.currentIndex)
            PlaybackController.currentTrack()?.let { updateTrackPanel(it) }
        }
        refreshTransport()
    }

    private fun refreshTransport() {
        binding.btnPlay.text = if (PlaybackController.isPlaying) "⏸" else "▶"
    }

    private fun updateTrackPanel(track: Track) {
        binding.trackTitle.text = track.title
        binding.trackArtist.text = track.artist
        binding.trackAlbum.text = listOf(track.album, track.durationLabel)
            .filter { it.isNotEmpty() }.joinToString("  ·  ")
        binding.artwork.setImageResource(R.drawable.artwork_placeholder)
        if (track.artworkUrl.isNotEmpty()) loadArtwork(track.artworkUrl)
    }

    private fun loadArtwork(url: String) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }
                catch (e: Exception) { null }
            }
            if (bmp != null) binding.artwork.setImageBitmap(bmp)
        }
    }

    private fun startProgressLoop() {
        ui.post(object : Runnable {
            override fun run() {
                if (PlaybackController.isPlaying) {
                    val pos = PlaybackController.position
                    val dur = PlaybackController.duration
                    if (dur > 0) binding.seekBar.progress = (pos * 1000 / dur).toInt()
                    binding.timeCur.text = fmt(pos)
                    binding.timeDur.text = fmt(dur)
                }
                ui.postDelayed(this, 500)
            }
        })
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun setStatus(msg: String) { binding.status.text = msg }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        PlaybackController.onStateChanged = null
        PlaybackController.onTrackChanged = null
        super.onDestroy()
    }
}
