package dev.shadowgps.core

import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.routing.AvoidanceSettings
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RouteFailure
import dev.shadowgps.core.routing.RoutePlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutingTest {

    private val grid = GraphBuilder.build(Fixtures.grid(rows = 5, cols = 6))

    private val start = Fixtures.position(0, 0)
    private val end = Fixtures.position(0, 5)

    @Test
    fun `with no cameras the fastest route runs straight down the street`() {
        val planner = RoutePlanner(grid, detectors = emptyList())

        val plan = planner.plan(start, end, listOf(PrivacyProfile.FASTEST))
        val route = plan.routes.single()

        // Five blocks of 200 m, no detour and no turns.
        assertEquals(1_000.0, route.distanceMeters, 5.0)
        assertEquals(0, route.exposure.totalCount)
        assertEquals(2, route.steps.size, "a straight run should be one instruction plus arrival")
    }

    @Test
    fun `a camera on the direct route is reported but not avoided by the fastest profile`() {
        val camera = Fixtures.alpr("node/1", Fixtures.midBlock(0, 2, 0, 3))
        val planner = RoutePlanner(grid, listOf(camera))

        val route = planner.plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertEquals(1_000.0, route.distanceMeters, 5.0)
        assertEquals(1, route.exposure.alprCount)
        assertEquals(camera.id, route.exposure.encounters.single().detector.id)
    }

    @Test
    fun `the ghost profile detours around a camera`() {
        val camera = Fixtures.alpr("node/1", Fixtures.midBlock(0, 2, 0, 3))
        val planner = RoutePlanner(grid, listOf(camera))

        val plan = planner.plan(start, end, listOf(PrivacyProfile.FASTEST, PrivacyProfile.GHOST))
        val fastest = plan.routes.first { it.profile == PrivacyProfile.FASTEST }
        val ghost = plan.routes.first { it.profile == PrivacyProfile.GHOST }

        assertEquals(1, fastest.exposure.totalCount)
        assertEquals(0, ghost.exposure.totalCount, "ghost should pass no cameras at all")
        assertTrue(ghost.distanceMeters > fastest.distanceMeters, "avoiding it has to cost something")
        // One block up, along, and back down: 400 m of detour.
        assertEquals(1_400.0, ghost.distanceMeters, 30.0)
    }

    @Test
    fun `a wall of cameras leaves the shortest way through as the least bad`() {
        // Cameras across every parallel street, so no clean route exists.
        val wall = (0 until 5).map { row ->
            Fixtures.alpr("node/wall-$row", Fixtures.midBlock(row, 2, row, 3))
        }
        val planner = RoutePlanner(grid, wall)

        val ghost = planner.plan(start, end, listOf(PrivacyProfile.GHOST)).routes.single()

        // It cannot get through unseen, so it should stop paying for a detour that buys
        // nothing and take a single camera on the direct line.
        assertEquals(1, ghost.exposure.totalCount)
        assertEquals(1_000.0, ghost.distanceMeters, 60.0)
    }

    @Test
    fun `disabled detector kinds are ignored entirely`() {
        val speedCamera = Fixtures.alpr(
            "node/2",
            Fixtures.midBlock(0, 2, 0, 3),
            kind = DetectorKind.SPEED_CAMERA,
        )

        // Default settings only care about plate readers.
        val defaultPlan = RoutePlanner(grid, listOf(speedCamera), AvoidanceSettings.DEFAULT)
            .plan(start, end, listOf(PrivacyProfile.GHOST)).routes.single()
        assertEquals(1_000.0, defaultPlan.distanceMeters, 5.0)
        assertEquals(0, defaultPlan.exposure.totalCount)

        // Turning the kind on makes the same route avoid it.
        val everything = RoutePlanner(grid, listOf(speedCamera), AvoidanceSettings.EVERYTHING)
            .plan(start, end, listOf(PrivacyProfile.GHOST)).routes.single()
        assertTrue(everything.distanceMeters > 1_000.0)
        assertEquals(0, everything.exposure.totalCount)
    }

    @Test
    fun `a camera facing away from the road does not push the route off it`() {
        // Sitting beside the street but pointed north, away from the east-west traffic.
        val facingAway = Fixtures.alpr(
            "node/3",
            Fixtures.midBlock(0, 2, 0, 3),
            heading = 0.0,
        )
        // Placed 40 m north of the road so the "directly underneath" rule does not apply.
        val offset = dev.shadowgps.core.geo.destinationPoint(facingAway.position, bearing = 0.0, meters = 40.0)
        val planner = RoutePlanner(grid, listOf(facingAway.copy(position = offset)))

        val route = planner.plan(start, end, listOf(PrivacyProfile.GHOST)).routes.single()

        assertEquals(1_000.0, route.distanceMeters, 5.0)
        assertEquals(0, route.exposure.totalCount)
    }

    @Test
    fun `identical routes from different profiles are shown once`() {
        val planner = RoutePlanner(grid, detectors = emptyList())

        val plan = planner.plan(start, end, PrivacyProfile.entries)

        // Nothing to avoid, so every profile agrees and only one route survives.
        assertEquals(1, plan.routes.size)
        assertEquals(PrivacyProfile.FASTEST, plan.routes.single().profile)
    }

    @Test
    fun `an absurd detour is dropped rather than offered`() {
        // A camera the router can only dodge by leaving the grid entirely: it sits on the
        // single street connecting the two halves of a corridor.
        val corridor = GraphBuilder.build(Fixtures.grid(rows = 1, cols = 6))
        val camera = Fixtures.alpr("node/4", Fixtures.midBlock(0, 2, 0, 3))
        val planner = RoutePlanner(corridor, listOf(camera))

        val plan = planner.plan(
            Fixtures.position(0, 0),
            Fixtures.position(0, 5),
            listOf(PrivacyProfile.FASTEST, PrivacyProfile.GHOST),
        )

        // There is no way round, so ghost duplicates the fastest route and is dropped.
        assertEquals(1, plan.routes.size)
        assertEquals(1, plan.routes.single().exposure.totalCount)
    }

    @Test
    fun `routing reports why it failed`() {
        val planner = RoutePlanner(grid, detectors = emptyList())
        val middleOfNowhere = dev.shadowgps.core.geo.LatLon(41.0, -100.0)

        val plan = planner.plan(start, middleOfNowhere)

        assertTrue(plan.isEmpty)
        assertEquals(RouteFailure.DESTINATION_UNREACHABLE, plan.failure)
    }

    @Test
    fun `origin and destination on the same block need no junction`() {
        val planner = RoutePlanner(grid, detectors = emptyList())

        val route = planner.plan(
            Fixtures.midBlock(0, 0, 0, 1),
            Fixtures.position(0, 1),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        assertEquals(100.0, route.distanceMeters, 5.0)
    }

    @Test
    fun `routes start and end at the snapped points`() {
        val planner = RoutePlanner(grid, detectors = emptyList())
        val route = planner.plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertTrue(haversineMeters(route.geometry.first(), start) < 5.0)
        assertTrue(haversineMeters(route.geometry.last(), end) < 5.0)
    }

    @Test
    fun `duration accounts for junction delays`() {
        val withSignals = Fixtures.grid(rows = 5, cols = 6).map { element ->
            if (element.isNode) element.copy(tags = mapOf("highway" to "traffic_signals")) else element
        }
        val signalGrid = GraphBuilder.build(withSignals)

        val plain = RoutePlanner(grid, emptyList())
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()
        val delayed = RoutePlanner(signalGrid, emptyList())
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertEquals(plain.distanceMeters, delayed.distanceMeters, 5.0)
        assertTrue(
            delayed.durationSeconds > plain.durationSeconds + 40,
            "four intermediate signals should cost around 48 s",
        )
    }

    @Test
    fun `the plan exposes the least watched option`() {
        val camera = Fixtures.alpr("node/5", Fixtures.midBlock(0, 2, 0, 3))
        val planner = RoutePlanner(grid, listOf(camera))

        val plan = planner.plan(start, end)

        assertNotNull(plan.fastest)
        assertNull(plan.failure)
        assertEquals(0, plan.leastExposed!!.exposure.totalCount)
        assertFalse(plan.leastExposed!!.profile == PrivacyProfile.FASTEST)
    }
}
