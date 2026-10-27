package dev.shadowgps.core

import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RouteFailure
import dev.shadowgps.core.routing.RoutePlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Going somewhere the road network does not reach: the middle of a car park or complex whose
 * private roads are unmapped or did not download. The route ends at the nearest road, and
 * says so, instead of failing after the whole download.
 */
class DetachedEndTest {

    private val grid = GraphBuilder.build(Fixtures.grid(rows = 4, cols = 6))
    private val planner = RoutePlanner(grid, detectors = emptyList())

    private val origin = Fixtures.position(0, 0)

    /** 500 m south of the grid: past the normal snapping distance, inside the fallback. */
    private val strandedDestination = destinationPoint(Fixtures.position(0, 4), bearing = 180.0, meters = 500.0)

    @Test
    fun `without a fallback the destination still fails as before`() {
        val plan = planner.plan(origin, strandedDestination, listOf(PrivacyProfile.FASTEST))
        assertTrue(plan.isEmpty)
        assertEquals(RouteFailure.DESTINATION_UNREACHABLE, plan.failure)
        assertNull(plan.provisionalEnd)
    }

    @Test
    fun `a destination with no road nearby ends at the closest one`() {
        val plan = planner.plan(
            origin,
            strandedDestination,
            listOf(PrivacyProfile.FASTEST),
            fallbackEndMeters = 1_000.0,
        )

        assertFalse(plan.isEmpty)
        assertNull(plan.failure)
        val end = plan.provisionalEnd
        assertNotNull(end, "the plan must admit it does not end where asked")
        assertEquals(strandedDestination, end!!.requested)
        assertEquals(500.0, end.distanceMeters, 30.0)
        assertNotNull(end.roadName)
        assertNull(plan.provisionalStart)

        // The route itself stops on the road, beside the destination.
        val routeEnd = plan.routes.single().geometry.last()
        assertTrue(haversineMeters(routeEnd, end.arrivalPoint) < 5.0)
        assertTrue(haversineMeters(routeEnd, Fixtures.position(0, 4)) < 30.0)
    }

    @Test
    fun `a destination beyond the fallback still fails honestly`() {
        val farOff = destinationPoint(Fixtures.position(0, 4), bearing = 180.0, meters = 3_000.0)
        val plan = planner.plan(origin, farOff, listOf(PrivacyProfile.FASTEST), fallbackEndMeters = 1_000.0)
        assertTrue(plan.isEmpty)
        assertEquals(RouteFailure.DESTINATION_UNREACHABLE, plan.failure)
    }

    /** Both ends off the network: both fallbacks apply to the one trip. */
    @Test
    fun `a stranded start and a stranded end are both rescued`() {
        val strandedStart = destinationPoint(Fixtures.position(0, 1), bearing = 180.0, meters = 700.0)
        val plan = planner.plan(
            strandedStart,
            strandedDestination,
            listOf(PrivacyProfile.FASTEST),
            fallbackStartMeters = 3_000.0,
            fallbackEndMeters = 1_000.0,
        )

        assertFalse(plan.isEmpty, "failure was ${plan.failure}")
        assertNotNull(plan.provisionalStart)
        assertNotNull(plan.provisionalEnd)
        assertEquals(700.0, plan.provisionalStart!!.distanceMeters, 30.0)
        assertEquals(500.0, plan.provisionalEnd!!.distanceMeters, 30.0)
    }
}
