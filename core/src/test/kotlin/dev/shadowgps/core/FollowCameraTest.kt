package dev.shadowgps.core

import dev.shadowgps.core.nav.FollowFraming
import dev.shadowgps.core.nav.followFraming
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FollowCameraTest {

    @Test
    fun `no manoeuvre to aim at is cruising`() {
        assertEquals(FollowFraming.CRUISING, followFraming(null))
    }

    @Test
    fun `far from a turn is cruising`() {
        assertEquals(FollowFraming.CRUISING, followFraming(2_000.0))
    }

    @Test
    fun `closes in on the approach and again at the junction`() {
        assertEquals(FollowFraming.APPROACHING, followFraming(450.0))
        assertEquals(FollowFraming.AT_MANEUVER, followFraming(100.0))
        assertEquals(FollowFraming.AT_MANEUVER, followFraming(0.0))
    }

    /**
     * The whole point of the feature, as a driver would experience it: one manoeuvre from a
     * long way out to the junction itself has to produce exactly one step in and then one
     * more, with no dithering in between.
     */
    @Test
    fun `a single approach steps in twice and no more`() {
        val distances = generateSequence(1_200.0) { it - 25.0 }.takeWhile { it >= 0.0 }
        var framing = FollowFraming.CRUISING
        val changes = mutableListOf<FollowFraming>()

        for (meters in distances) {
            val next = followFraming(meters, framing)
            if (next != framing) changes.add(next)
            framing = next
        }

        assertEquals(listOf(FollowFraming.APPROACHING, FollowFraming.AT_MANEUVER), changes)
    }

    /**
     * A stationary car at a red light still produces fixes, and they wander. Jitter across a
     * band boundary must not pump the map in and out — that is worse than not zooming at all.
     */
    @Test
    fun `jitter across a boundary does not flap`() {
        var framing = followFraming(140.0)
        assertEquals(FollowFraming.AT_MANEUVER, framing)

        var changes = 0
        // Ten metres of noise either side of the 150 m boundary.
        for (meters in listOf(155.0, 145.0, 158.0, 142.0, 160.0, 148.0)) {
            val next = followFraming(meters, framing)
            if (next != framing) changes++
            framing = next
        }

        assertEquals(0, changes)
        assertEquals(FollowFraming.AT_MANEUVER, framing)
    }

    /** Past the margin, though, it does have to let go. */
    @Test
    fun `driving away from a passed manoeuvre pulls back out`() {
        assertEquals(FollowFraming.APPROACHING, followFraming(230.0, FollowFraming.AT_MANEUVER))
        assertEquals(FollowFraming.CRUISING, followFraming(600.0, FollowFraming.APPROACHING))
    }
}
