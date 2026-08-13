package dev.shadowgps.core

import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.nav.PositionFix
import dev.shadowgps.core.nav.StartJoinWatcher
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RouteFailure
import dev.shadowgps.core.routing.RoutePlanner
import dev.shadowgps.core.routing.SnapRadius
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Starting a trip from somewhere the road network does not reach: a car park, a field, a
 * campus. The route begins at the nearest road instead, and guidance waits until the driver
 * has actually got there.
 */
class DetachedStartTest {

    private val grid = GraphBuilder.build(Fixtures.grid(rows = 4, cols = 6))
    private val planner = RoutePlanner(grid, detectors = emptyList())

    private val destination = Fixtures.position(0, 5)

    /** 900 m south of the grid — far outside any sane snap allowance. */
    private val strandedStart = destinationPoint(Fixtures.position(0, 1), bearing = 180.0, meters = 900.0)

    @Test
    fun `a start with no road nearby falls back to the closest one`() {
        val plan = planner.plan(
            origin = strandedStart,
            destination = destination,
            profiles = listOf(PrivacyProfile.FASTEST),
            fallbackStartMeters = 3_000.0,
        )

        assertFalse(plan.isEmpty, "the fallback should produce a usable route")
        assertNull(plan.failure)

        val provisional = plan.provisionalStart
        assertNotNull(provisional, "the plan must admit it does not start where asked")
        assertEquals(strandedStart, provisional!!.requested)
        assertEquals(900.0, provisional.distanceMeters, 30.0)
        assertNotNull(provisional.roadName)

        // The route itself begins on the road, not out in the field.
        val routeStart = plan.routes.single().geometry.first()
        assertTrue(haversineMeters(routeStart, provisional.joinPoint) < 5.0)
    }

    @Test
    fun `the fallback picks the nearest road, not just any road`() {
        val plan = planner.plan(
            strandedStart,
            destination,
            listOf(PrivacyProfile.FASTEST),
            fallbackStartMeters = 3_000.0,
        )

        val joinPoint = plan.provisionalStart!!.joinPoint
        val nearest = grid.snapNearest(strandedStart, maxDistanceMeters = 3_000.0)!!

        assertEquals(nearest.point.lat, joinPoint.lat, 1e-6)
        assertEquals(nearest.point.lon, joinPoint.lon, 1e-6)
    }

    @Test
    fun `a normal start is not flagged as provisional`() {
        val plan = planner.plan(
            Fixtures.position(0, 0),
            destination,
            listOf(PrivacyProfile.FASTEST),
            fallbackStartMeters = 3_000.0,
        )

        assertNull(plan.provisionalStart, "a start already on a road needs no fallback")
    }

    @Test
    fun `the fallback stays off when not asked for`() {
        val plan = planner.plan(strandedStart, destination, listOf(PrivacyProfile.FASTEST))

        assertTrue(plan.isEmpty)
        assertEquals(RouteFailure.ORIGIN_UNREACHABLE, plan.failure)
        assertNull(plan.provisionalStart)
    }

    @Test
    fun `the fallback does not paper over other failures`() {
        // An unreachable destination is not fixed by moving the start, and pretending
        // otherwise would report a route to somewhere the driver did not ask for.
        val plan = planner.plan(
            Fixtures.position(0, 0),
            dev.shadowgps.core.geo.LatLon(41.0, -100.0),
            listOf(PrivacyProfile.FASTEST),
            fallbackStartMeters = 3_000.0,
        )

        assertEquals(RouteFailure.DESTINATION_UNREACHABLE, plan.failure)
        assertNull(plan.provisionalStart)
    }

    @Test
    fun `nothing within the fallback range still fails honestly`() {
        val plan = planner.plan(
            destinationPoint(Fixtures.position(0, 0), bearing = 180.0, meters = 20_000.0),
            destination,
            listOf(PrivacyProfile.FASTEST),
            fallbackStartMeters = 3_000.0,
        )

        assertEquals(RouteFailure.ORIGIN_UNREACHABLE, plan.failure)
        assertNull(plan.provisionalStart)
    }

    @Test
    fun `routability is reported directly`() {
        assertTrue(planner.isRoutableFrom(Fixtures.position(0, 0)))
        assertFalse(planner.isRoutableFrom(strandedStart))
        assertTrue(planner.isRoutableFrom(strandedStart, snapMeters = 2_000.0))
    }

    // ------------------------------------------------------------------ joining

    private fun fixAt(
        position: dev.shadowgps.core.geo.LatLon,
        accuracy: Double? = 10.0,
        speed: Double? = null,
    ) = PositionFix(position = position, accuracyMeters = accuracy, speedMetersPerSecond = speed)

    @Test
    fun `guidance waits while the driver is still off the network`() {
        val watcher = StartJoinWatcher(grid)

        repeat(5) {
            assertNull(watcher.update(fixAt(strandedStart)), "still nowhere near a road")
        }
        assertEquals(0, watcher.consecutiveQualifyingFixes)
    }

    @Test
    fun `reaching a road hands over, after more than one fix`() {
        val watcher = StartJoinWatcher(grid)
        val onRoad = Fixtures.midBlock(0, 1, 0, 2)

        assertNull(watcher.update(fixAt(onRoad)), "one good fix is not yet proof")
        val joined = watcher.update(fixAt(onRoad))

        assertNotNull(joined)
        assertEquals(onRoad.lat, joined!!.lat, 1e-9)
        assertEquals(onRoad.lon, joined.lon, 1e-9)
    }

    @Test
    fun `a lone good fix among bad ones does not trigger the handover`() {
        val watcher = StartJoinWatcher(grid)
        val onRoad = Fixtures.midBlock(0, 1, 0, 2)

        assertNull(watcher.update(fixAt(onRoad)))
        assertNull(watcher.update(fixAt(strandedStart)))
        // The streak restarted, so the next good fix is only the first again.
        assertNull(watcher.update(fixAt(onRoad)))
        assertNotNull(watcher.update(fixAt(onRoad)))
    }

    @Test
    fun `an untrustworthy fix does not count even when it lands on a road`() {
        val watcher = StartJoinWatcher(grid)
        val onRoad = Fixtures.midBlock(0, 1, 0, 2)

        // A position claiming 400 m of error can sit on a road purely by luck; starting
        // turn-by-turn on that basis would guide down a street the driver is not on.
        repeat(4) { assertNull(watcher.update(fixAt(onRoad, accuracy = 400.0))) }

        assertNull(watcher.update(fixAt(onRoad, accuracy = 20.0)))
        assertNotNull(watcher.update(fixAt(onRoad, accuracy = 20.0)))
    }

    @Test
    fun `a moving fix is convincing even with poor reported accuracy`() {
        val watcher = StartJoinWatcher(grid)
        val onRoad = Fixtures.midBlock(0, 1, 0, 2)

        // Nothing travels at 14 m per second anywhere but a road.
        assertNull(watcher.update(fixAt(onRoad, accuracy = 300.0, speed = 14.0)))
        assertNotNull(watcher.update(fixAt(onRoad, accuracy = 300.0, speed = 14.0)))
    }

    @Test
    fun `resetting clears progress towards a handover`() {
        val watcher = StartJoinWatcher(grid)
        val onRoad = Fixtures.midBlock(0, 1, 0, 2)

        watcher.update(fixAt(onRoad))
        watcher.reset()

        assertEquals(0, watcher.consecutiveQualifyingFixes)
        assertNull(watcher.update(fixAt(onRoad)), "the streak should start over")
    }

    @Test
    fun `the driver can join somewhere other than the planned start`() {
        // Leaving a car park by a different exit is normal, so the handover reports where
        // they really are and lets the route be replanned from there.
        val watcher = StartJoinWatcher(grid)
        val differentStreet = Fixtures.midBlock(2, 3, 2, 4)

        watcher.update(fixAt(differentStreet))
        val joined = watcher.update(fixAt(differentStreet))

        assertNotNull(joined)
        val replanned = planner.plan(joined!!, destination, listOf(PrivacyProfile.FASTEST))
        assertFalse(replanned.isEmpty)
        assertNull(replanned.provisionalStart, "guidance now starts where the driver is")
    }

    @Test
    fun `the whole sequence works end to end`() {
        // Plan from a car park, walk out to the road, get guidance.
        val plan = planner.plan(
            strandedStart,
            destination,
            listOf(PrivacyProfile.FASTEST),
            originSnapMeters = SnapRadius.DEFAULT_METERS,
            fallbackStartMeters = 3_000.0,
        )
        val provisional = plan.provisionalStart!!

        val watcher = StartJoinWatcher(grid)
        var handover: dev.shadowgps.core.geo.LatLon? = null

        // Walk in ten steps from the stranded start towards the join point.
        for (step in 1..10) {
            val fraction = step / 10.0
            val position = dev.shadowgps.core.geo.LatLon(
                strandedStart.lat + (provisional.joinPoint.lat - strandedStart.lat) * fraction,
                strandedStart.lon + (provisional.joinPoint.lon - strandedStart.lon) * fraction,
            )
            handover = watcher.update(fixAt(position, accuracy = 15.0))
            if (handover != null) break
        }

        assertNotNull(handover, "arriving at the road should hand over to guidance")

        val guided = planner.plan(handover!!, destination, listOf(PrivacyProfile.FASTEST))
        assertFalse(guided.isEmpty)
        assertNull(guided.provisionalStart)
        assertTrue(guided.routes.single().steps.isNotEmpty())
    }
}
