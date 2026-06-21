package io.github.blacksamdev.groove.ui

import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.databinding.ActivityMainBinding
import io.github.blacksamdev.groove.model.Track
import io.github.blacksamdev.groove.player.GroovePlayer
import io.github.blacksamdev.groove.player.GrooveQueue
import io.github.blacksamdev.groove.resolver.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Écran principal BBS grOOve — transpose l'UI desktop (topbar + queue +
 * panneau titre/artwork + player bar) en Material3 / RecyclerView.
 * Lyrics synced, Last.fm et mode gaming flottant ne sont pas portés en v0.1.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackAdapter
    private lateinit var player: GroovePlayer
    private val queue = GrooveQueue()

    private var castContext: CastContext? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Les boutons volume physiques pilotent le flux MUSIC même app au premier plan
        volumeControlStream = AudioManager.STREAM_MUSIC

        castContext = try { CastContext.getSharedInstance(this) } catch (e: Exception) { null }

        player = GroovePlayer(this, castContext) { runOnUiThread { onNext() } }

        setupList()
        setupControls()
        startProgressLoop()
    }

    // ── Liste / file de lecture ───────────────────────────────────────

    private fun setupList() {
        adapter = TrackAdapter { index -> playAt(index) }
        binding.queueList.layoutManager = LinearLayoutManager(this)
        binding.queueList.adapter = adapter
    }

    private fun setupControls() {
        binding.btnLoad.setOnClickListener { loadInput() }
        binding.urlInput.setOnEditorActionListener { _, _, _ -> loadInput(); true }

        binding.btnPlay.setOnClickListener { onPlayPause() }
        binding.btnNext.setOnClickListener { onNext() }
        binding.btnPrev.setOnClickListener { onPrev() }

        binding.btnShuffle.setOnClickListener {
            val on = !queue.shuffle
            queue.setShuffle(on)
            binding.btnShuffle.alpha = if (on) 1f else 0.4f
        }
        binding.btnRepeat.setOnClickListener {
            queue.repeat = !queue.repeat
            binding.btnRepeat.alpha = if (queue.repeat) 1f else 0.4f
        }
        binding.btnShuffle.alpha = 0.4f
        binding.btnRepeat.alpha = 0.4f

        binding.seekBar.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                val dur = player.duration
                if (dur > 0) player.seekTo(dur * sb.progress / 1000)
            }
        })
    }

    // ── Cast button dans la toolbar ───────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext, menu, R.id.media_route_menu_item
        )
        return true
    }

    // ── Chargement (URL Spotify ou recherche libre) ───────────────────

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
            queue.load(tracks)
            adapter.submit(tracks)
            setStatus("${tracks.size} titre(s) chargé(s)")
            playAt(0)
        }
    }

    // ── Lecture ───────────────────────────────────────────────────────

    private fun playAt(index: Int) {
        val track = queue.goTo(index) ?: return
        adapter.setCurrent(queue.currentIndex)
        updateTrackPanel(track)
        resolveAndPlay(track)
    }

    private fun resolveAndPlay(track: Track) {
        setStatus("Résolution en cours…")
        lifecycleScope.launch {
            val stream = track.streamUrl ?: try {
                PythonBridge.resolveStream(track)
            } catch (e: Exception) { null }
            if (stream == null) { setStatus("Résolution échouée"); return@launch }
            track.streamUrl = stream
            player.play(track, stream)
            binding.btnPlay.text = "⏸"
            setStatus("Lecture")
        }
    }

    private fun onPlayPause() {
        if (queue.isEmpty()) return
        player.togglePause()
        binding.btnPlay.text = if (player.isPlaying) "⏸" else "▶"
    }

    private fun onNext() {
        val track = queue.goNext()
        if (track == null) { setStatus("Fin de la file"); return }
        adapter.setCurrent(queue.currentIndex)
        updateTrackPanel(track)
        resolveAndPlay(track)
    }

    private fun onPrev() {
        val track = queue.goPrev() ?: return
        adapter.setCurrent(queue.currentIndex)
        updateTrackPanel(track)
        resolveAndPlay(track)
    }

    // ── Panneau titre / artwork ───────────────────────────────────────

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

    // ── Progress loop ─────────────────────────────────────────────────

    private fun startProgressLoop() {
        ui.post(object : Runnable {
            override fun run() {
                if (player.isPlaying) {
                    val pos = player.position
                    val dur = player.duration
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
        player.release()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
