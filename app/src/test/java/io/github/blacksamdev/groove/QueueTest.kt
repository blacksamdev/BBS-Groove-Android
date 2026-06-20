package io.github.blacksamdev.groove

import io.github.blacksamdev.groove.model.Track
import io.github.blacksamdev.groove.player.GrooveQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests de base de la logique de file (shuffle/repeat/navigation). */
class QueueTest {

    private fun sample() = listOf(
        Track("A", "x"), Track("B", "y"), Track("C", "z")
    )

    @Test fun loadSetsFirst() {
        val q = GrooveQueue(); q.load(sample())
        assertEquals("A", q.currentTrack()?.title)
    }

    @Test fun nextAdvances() {
        val q = GrooveQueue(); q.load(sample())
        assertEquals("B", q.goNext()?.title)
        assertEquals("C", q.goNext()?.title)
        assertNull(q.goNext())   // fin de file sans repeat
    }

    @Test fun repeatStaysOnCurrent() {
        val q = GrooveQueue(); q.load(sample())
        q.repeat = true
        assertEquals("A", q.goNext()?.title)
    }
}
