package io.github.blacksamdev.groove.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.blacksamdev.groove.R
import io.github.blacksamdev.groove.model.LyricLine

/**
 * Affiche les lignes de paroles synchronisées. La ligne courante est en blanc
 * gras, les 2 voisines en gris clair, le reste en gris foncé (comme desktop).
 */
class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private val lines = mutableListOf<LyricLine>()
    private var currentIndex = -1

    fun submit(newLines: List<LyricLine>) {
        lines.clear()
        lines.addAll(newLines)
        currentIndex = -1
        notifyDataSetChanged()
    }

    /** Met à jour la ligne active ; retourne true si l'index a changé. */
    fun setCurrent(index: Int): Boolean {
        if (index == currentIndex) return false
        val old = currentIndex
        currentIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val l = lines[position]
        holder.line.text = if (l.text.isEmpty()) "♪" else l.text
        val dist = kotlin.math.abs(position - currentIndex)
        when {
            position == currentIndex -> {
                holder.line.setTextColor(0xFFFFFFFF.toInt())
                holder.line.textSize = 18f
                holder.line.setTypeface(holder.line.typeface, android.graphics.Typeface.BOLD)
            }
            dist <= 2 && currentIndex >= 0 -> {
                holder.line.setTextColor(0xFFCCCCCC.toInt())
                holder.line.textSize = 15f
                holder.line.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            else -> {
                holder.line.setTextColor(0xFF888888.toInt())
                holder.line.textSize = 14f
                holder.line.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    override fun getItemCount() = lines.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val line: TextView = view.findViewById(R.id.lyric_line)
    }
}
