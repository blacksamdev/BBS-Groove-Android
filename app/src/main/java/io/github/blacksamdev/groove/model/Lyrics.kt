package io.github.blacksamdev.groove.model

/** Une ligne de parole synchronisée : temps (secondes) + texte. */
data class LyricLine(val time: Double, val text: String)

/** Résultat de paroles pour un titre. */
data class Lyrics(
    val hasSynced: Boolean,
    val synced: List<LyricLine>,
    val plain: String,
) {
    val isEmpty: Boolean get() = !hasSynced && plain.isBlank()
}
