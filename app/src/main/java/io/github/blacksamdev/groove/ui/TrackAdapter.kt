package io.github.blacksamdev.groove.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.model.Track

/**
 * Liste de titres à deux niveaux (vignette + titre/artiste + durée + action).
 * Mode ADD (file) -> "+" ajouter à une playlist.
 * Mode REMOVE (playlist ouverte) -> "−" retirer de la playlist.
 *
 * Vignettes chargées via Coil : cache mémoire+disque, annulation automatique
 * au recyclage de la vue (plus de connexions réseau accumulées).
 */
class TrackAdapter(
    private val mode: Mode = Mode.ADD,
    private val onClick: (Int) -> Unit,
    private val onAction: (Int) -> Unit = {},
    private val onMoveUp: (Int) -> Unit = {},
    private val onMoveDown: (Int) -> Unit = {},
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    enum class Mode { ADD, REMOVE }

    private val items = mutableListOf<Track>()
    private var currentIndex = -1

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

        // Flèches de réordonnancement : uniquement dans une playlist ouverte.
        if (mode == Mode.REMOVE) {
            holder.moveUp.visibility = View.VISIBLE
            holder.moveDown.visibility = View.VISIBLE
            // Grisées et inactives aux extrémités.
            val isFirst = position == 0
            val isLast = position == items.size - 1
            holder.moveUp.isEnabled = !isFirst
            holder.moveUp.alpha = if (isFirst) 0.3f else 1f
            holder.moveDown.isEnabled = !isLast
            holder.moveDown.alpha = if (isLast) 0.3f else 1f
            holder.moveUp.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p > 0) onMoveUp(p)
            }
            holder.moveDown.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p in 0 until items.size - 1) onMoveDown(p)
            }
        } else {
            holder.moveUp.visibility = View.GONE
            holder.moveDown.visibility = View.GONE
        }

        // Vignette via Coil (annulation auto au recyclage, cache intégré)
        holder.art.load(t.artworkUrl.ifEmpty { null }) {
            placeholder(R.drawable.artwork_placeholder)
            error(R.drawable.artwork_placeholder)
            crossfade(true)
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
        val moveUp: ImageView = view.findViewById(R.id.track_move_up)
        val moveDown: ImageView = view.findViewById(R.id.track_move_down)
    }
}
