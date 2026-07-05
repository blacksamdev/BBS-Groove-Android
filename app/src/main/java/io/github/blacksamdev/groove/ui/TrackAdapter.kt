package io.github.blacksamdev.groove.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Liste de titres à deux niveaux (vignette + titre/artiste + durée + action).
 * Mode ADD (file) -> "+" ajouter à une playlist.
 * Mode REMOVE (playlist ouverte) -> "−" retirer de la playlist.
 * Le titre courant (mode ADD) est mis en évidence en accent.
 */
class TrackAdapter(
    private val mode: Mode = Mode.ADD,
    private val onClick: (Int) -> Unit,
    private val onAction: (Int) -> Unit = {},
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    enum class Mode { ADD, REMOVE }

    private val items = mutableListOf<Track>()
    private var currentIndex = -1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun submit(tracks: List<Track>) {
        items.clear()
        items.addAll(tracks)
        notifyDataSetChanged()
    }

    fun setCurrent(index: Int) {
        val old = currentIndex
        currentIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val accent = 0xFF1DB954.toInt()
        val white  = 0xFFFFFFFF.toInt()

        holder.num.text = (position + 1).toString().padStart(2, '0')
        holder.title.text = t.title
        holder.artist.text = t.artist
        holder.dur.text = if (t.durationMs > 0) t.durationLabel else ""

        val isCurrent = mode == Mode.ADD && position == currentIndex
        holder.title.setTextColor(if (isCurrent) accent else white)

        holder.action.setImageResource(
            if (mode == Mode.ADD) R.drawable.ic_add else R.drawable.ic_delete
        )
        holder.action.setOnClickListener { onAction(holder.bindingAdapterPosition) }
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }

        // Vignette artwork (chargement async, léger)
        holder.art.setImageResource(R.drawable.artwork_placeholder)
        holder.artJob?.cancel()
        if (t.artworkUrl.isNotEmpty()) {
            val url = t.artworkUrl
            holder.artJob = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }
                    catch (e: Exception) { null }
                }
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.art.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val num: TextView = view.findViewById(R.id.track_num)
        val art: ImageView = view.findViewById(R.id.track_art)
        val title: TextView = view.findViewById(R.id.track_title)
        val artist: TextView = view.findViewById(R.id.track_artist)
        val dur: TextView = view.findViewById(R.id.track_dur)
        val action: ImageView = view.findViewById(R.id.track_action)
        var artJob: Job? = null
    }
}
