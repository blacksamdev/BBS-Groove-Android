package io.github.blacksamdev.groove.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.model.Track

/**
 * Adapter de la file de lecture. Reproduit le format desktop :
 *   "  01.  Artiste  —  Titre"
 * Le titre courant est mis en évidence (couleur accent #1DB954).
 */
class TrackAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.VH>() {

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
        holder.line.setTextColor(if (position == currentIndex) accent else white)
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val line: TextView = view.findViewById(R.id.track_line)
    }
}
