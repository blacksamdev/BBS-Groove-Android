package io.github.blacksamdev.groove.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.model.Playlist

/**
 * Liste des playlists perso. Chaque ligne : nom + nb de titres + lecture + suppression.
 * Un tap sur la ligne ouvre la playlist (affiche ses titres).
 */
class PlaylistAdapter(
    private val onOpen: (Playlist) -> Unit,
    private val onPlay: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private val items = mutableListOf<Playlist>()

    fun submit(playlists: List<Playlist>) {
        items.clear()
        items.addAll(playlists)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        holder.count.text = "${p.tracks.size} titre(s)"
        holder.row.setOnClickListener { onOpen(p) }
        holder.play.setOnClickListener { onPlay(p) }
        holder.delete.setOnClickListener { onDelete(p) }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view.findViewById(R.id.plRow)
        val name: TextView = view.findViewById(R.id.plName)
        val count: TextView = view.findViewById(R.id.plCount)
        val play: TextView = view.findViewById(R.id.plPlay)
        val delete: TextView = view.findViewById(R.id.plDelete)
    }
}
