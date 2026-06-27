package io.github.blacksamdev.groove.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.model.Track

/**
 * Adapter de liste de titres, utilisé dans deux contextes (mode) :
 *   - ADD    : file de lecture -> bouton "+" pour ajouter le titre à une playlist
 *   - REMOVE : playlist ouverte -> bouton "−" pour retirer le titre de la playlist
 *
 * Le titre courant (mode ADD seulement) est mis en évidence en accent.
 */
class TrackAdapter(
    private val mode: Mode = Mode.ADD,
    private val onClick: (Int) -> Unit,
    private val onAction: (Int) -> Unit = {},
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
        val num = (position + 1).toString().padStart(2, '0')
        holder.line.text = "  $num.  ${t.artist}  —  ${t.title}"

        val accent = 0xFF1DB954.toInt()
        val white  = 0xFFFFFFFF.toInt()
        holder.line.setTextColor(
            if (mode == Mode.ADD && position == currentIndex) accent else white
        )

        // Bouton d'action selon le mode
        holder.action.text = if (mode == Mode.ADD) "+" else "−"
        holder.action.setTextColor(if (mode == Mode.ADD) accent else 0xFFFF5555.toInt())
        holder.action.setOnClickListener { onAction(holder.bindingAdapterPosition) }

        holder.line.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val line: TextView = view.findViewById(R.id.track_line)
        val action: TextView = view.findViewById(R.id.track_action)
    }
}
