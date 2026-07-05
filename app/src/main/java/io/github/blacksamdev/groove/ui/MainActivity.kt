package io.github.blacksamdev.groove.ui

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.app.AlertDialog
import android.media.AudioManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
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
import io.github.blacksamdev.groove.model.Playlist
import io.github.blacksamdev.groove.model.PlaylistStore
import io.github.blacksamdev.groove.model.SettingsStore
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
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var store: PlaylistStore
    private lateinit var settings: SettingsStore
    private var openPlaylist: Playlist? = null

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
        PlaybackController.onStatus = { msg -> runOnUiThread { setStatus(msg) } }
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
        adapter = TrackAdapter(
            mode = TrackAdapter.Mode.ADD,
            onClick = { index -> PlaybackController.playAt(index) },
            onAction = { index -> promptAddTrackToPlaylist(index) },
        )
        binding.queueList.layoutManager = LinearLayoutManager(this)
        binding.queueList.adapter = adapter

        store = PlaylistStore(this)
        settings = SettingsStore(this)
        playlistAdapter = PlaylistAdapter(
            onOpen   = { pl -> openPlaylistTracks(pl) },
            onPlay   = { pl -> if (pl.tracks.isNotEmpty()) { adapter.submit(pl.tracks); PlaybackController.load(pl.tracks); showPlayback() } },
            onDelete = { pl -> store.deletePlaylist(pl.name); refreshPlaylists() },
        )
        binding.playlistsList.layoutManager = LinearLayoutManager(this)
        binding.playlistsList.adapter = playlistAdapter
    }

    // ── Bascule panneaux lecture <-> playlists ────────────────────────

    private fun showPlaylists() {
        openPlaylist = null
        binding.playbackPanel.visibility = android.view.View.GONE
        binding.playlistsPanel.visibility = android.view.View.VISIBLE
        binding.playlistsTitle.text = "Mes playlists grOOve"
        binding.playlistsList.adapter = playlistAdapter
        refreshPlaylists()
    }

    private fun showPlayback() {
        binding.playlistsPanel.visibility = android.view.View.GONE
        binding.playbackPanel.visibility = android.view.View.VISIBLE
    }

    private fun refreshPlaylists() {
        playlistAdapter.submit(store.load())
    }

    /** Affiche les titres d'une playlist dans la liste playlists (réutilise TrackAdapter). */
    private fun openPlaylistTracks(pl: Playlist) {
        openPlaylist = pl
        binding.playlistsTitle.text = pl.name
        // Ouvrir une playlist démarre sa lecture, même si un titre joue déjà.
        if (pl.tracks.isNotEmpty()) {
            adapter.submit(pl.tracks)
            PlaybackController.load(pl.tracks)
        }
        val ta = TrackAdapter(
            mode = TrackAdapter.Mode.REMOVE,
            onClick = { index ->
                adapter.submit(pl.tracks)
                PlaybackController.load(pl.tracks)
                PlaybackController.playAt(index)
                showPlayback()
            },
            onAction = { index ->
                // Retrait immédiat du titre de cette playlist
                store.removeTrack(pl.name, index)
                pl.tracks.removeAt(index)
                openPlaylistTracks(pl)   // recharge la vue de la playlist
            },
        )
        ta.submit(pl.tracks)
        binding.playlistsList.adapter = ta
    }

    /** Dialog Options : mode autoplay (off/youtube/lastfm) + clé Last.fm. */
    private fun showOptions() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val label = TextView(this).apply {
            text = "Lecture automatique en fin de file"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        container.addView(label)

        val group = RadioGroup(this)
        val rbOff = RadioButton(this).apply { text = "Désactivé"; setTextColor(0xFFFFFFFF.toInt()) }
        val rbYt  = RadioButton(this).apply { text = "YouTube (sans clé)"; setTextColor(0xFFFFFFFF.toInt()) }
        val rbLfm = RadioButton(this).apply { text = "Last.fm (recommandations)"; setTextColor(0xFFFFFFFF.toInt()) }
        group.addView(rbOff); group.addView(rbYt); group.addView(rbLfm)
        when (settings.autoplayMode) {
            "youtube" -> rbYt.isChecked = true
            "lastfm"  -> rbLfm.isChecked = true
            else      -> rbOff.isChecked = true
        }
        container.addView(group)

        val keyInput = EditText(this).apply {
            hint = "Clé API Last.fm"
            setText(settings.lastfmApiKey)
            setTextColor(0xFFFFFFFF.toInt())
        }
        container.addView(keyInput)

        val link = TextView(this).apply {
            text = "Obtenir une clé gratuite : last.fm/api"
            setTextColor(0xFF1DB954.toInt())
            textSize = 11f
        }
        container.addView(link)

        AlertDialog.Builder(this)
            .setTitle("Options")
            .setView(container)
            .setPositiveButton("Enregistrer") { _, _ ->
                val mode = when (group.checkedRadioButtonId) {
                    rbYt.id  -> "youtube"
                    rbLfm.id -> "lastfm"
                    else     -> "off"
                }
                settings.setAutoplayMode(mode)
                settings.setLastfmApiKey(keyInput.text.toString().trim())
                // Réinjecter dans le moteur de lecture immédiatement
                PlaybackController.setAutoplayConfig(settings.autoplayMode, settings.lastfmApiKey)
                setStatus("Options enregistrées")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun promptNewPlaylist() {
        val input = EditText(this).apply { hint = "Nom de la playlist" }
        AlertDialog.Builder(this)
            .setTitle("Nouvelle playlist")
            .setView(input)
            .setPositiveButton("Créer") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { store.create(name); refreshPlaylists() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Ajoute un seul titre (de la file) à une playlist au choix. */
    private fun promptAddTrackToPlaylist(index: Int) {
        val track = PlaybackController.queue.tracks.getOrNull(index) ?: return
        val existing = store.load()
        val names = existing.map { it.name }.toMutableList()
        names.add(0, "➕ Nouvelle playlist…")
        AlertDialog.Builder(this)
            .setTitle("Ajouter « ${track.title} » à…")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply { hint = "Nom de la playlist" }
                    AlertDialog.Builder(this)
                        .setTitle("Nouvelle playlist")
                        .setView(input)
                        .setPositiveButton("Créer") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                store.addTracks(name, listOf(track))
                                setStatus("Ajouté à « $name »")
                            }
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                } else {
                    val name = existing[which - 1].name
                    store.addTracks(name, listOf(track))
                    setStatus("Ajouté à « $name »")
                }
            }
            .show()
    }

    /** Importe la file courante dans une playlist (nouvelle ou existante). */
    private fun promptImport() {
        val tracks = PlaybackController.queue.tracks.toList()
        if (tracks.isEmpty()) { setStatus("Rien à importer"); return }
        val existing = store.load()
        val names = existing.map { it.name }.toMutableList()
        names.add(0, "➕ Nouvelle playlist…")
        AlertDialog.Builder(this)
            .setTitle("Importer ${tracks.size} titre(s) dans…")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply { hint = "Nom de la playlist" }
                    AlertDialog.Builder(this)
                        .setTitle("Nouvelle playlist")
                        .setView(input)
                        .setPositiveButton("Créer") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                store.addTracks(name, tracks)
                                setStatus("Importé dans « $name »")
                            }
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                } else {
                    val name = existing[which - 1].name
                    store.addTracks(name, tracks)
                    setStatus("Importé dans « $name »")
                }
            }
            .show()
    }

    private fun setupControls() {
        binding.btnLoad.setOnClickListener { loadInput() }
        binding.btnPlaylists.setOnClickListener { showPlaylists() }
        binding.btnOptions.setOnClickListener { showOptions() }
        binding.btnImport.setOnClickListener { promptImport() }
        binding.btnBack.setOnClickListener { if (openPlaylist != null) showPlaylists() else showPlayback() }
        binding.btnNewPlaylist.setOnClickListener { promptNewPlaylist() }
        binding.urlInput.setOnEditorActionListener { _, _, _ -> loadInput(); true }

        binding.btnPlay.setOnClickListener { PlaybackController.togglePause() }
        binding.btnNext.setOnClickListener { PlaybackController.next() }
        binding.btnPrev.setOnClickListener { PlaybackController.prev() }

        binding.btnShuffle.setOnClickListener {
            val on = !PlaybackController.queue.shuffle
            PlaybackController.setShuffle(on)
            binding.btnShuffle.alpha = if (on) 1f else 0.4f
        }
        binding.btnRepeat.setOnClickListener {
            val rep = !PlaybackController.queue.repeat
            PlaybackController.setRepeat(rep)
            binding.btnRepeat.alpha = if (rep) 1f else 0.4f
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
        binding.btnPlay.setImageResource(
            if (PlaybackController.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
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
        PlaybackController.onStatus = null
        super.onDestroy()
    }
}
