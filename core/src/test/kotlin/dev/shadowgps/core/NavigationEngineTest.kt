package dev.shadowgps.core

import dev.shadowgps.core.format.Formatting
import dev.shadowgps.core.format.UnitSystem
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.coordsLengthMeters
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.geo.interpolateAlongCoords
import dev.shadowgps.core.geo.listToCoords
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.nav.Announcement
import dev.shadowgps.core.nav.NavigationConfig
import dev.shadowgps.core.nav.NavigationEngine
import dev.shadowgps.core.nav.NavigationState
import dev.shadowgps.core.nav.PositionFix
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.Route
import dev.shadowgps.core.routing.RoutePlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationEngineTest {

    private val grid = GraphBuilder.build(Fixtures.grid(rows = 4, cols = 6))

    private fun straightRoute(cameras: List<dev.shadowgps.core.detect.Detector> = emptyList()): Route =
        RoutePlanner(grid, cameras)
            .plan(Fixtures.position(0, 0), Fixtures.position(0, 5), listOf(PrivacyProfile.FASTEST))
            .routes.single()

    /** Drives the whole route in [stepMeters] hops, returning the state after each one. */
    private fun drive(
        engine: NavigationEngine,
        route: Route,
        stepMeters: Double = 25.0,
        speedMps: Double = 12.0,
    ): List<NavigationState> {
        val coords = listToCoords(route.geometry)
        val total = coordsLengthMeters(coords)
        val states = ArrayList<NavigationState>()
        var travelled = 0.0
        var time = 0L

        fun fixAt(distance: Double) {
            states.add(
                engine.update(
                    PositionFix(
                        position = interpolateAlongCoords(coords, distance),
                        speedMetersPerSecond = speedMps,
                        accuracyMeters = 5.0,
                        timestampMillis = time,
                    ),
                ),
            )
            time += 2_000
        }

        while (travelled < total) {
            fixAt(travelled)
            travelled += stepMeters
        }
        // Always finish exactly on the destination, so arrival is actually reached.
        fixAt(total)
        return states
    }

    @Test
    fun `progress advances monotonically along the route`() {
        val route = straightRoute()
        val states = drive(NavigationEngine(route), route)

        val progress = states.map { it.distanceAlongRouteMeters }
        assertEquals(progress.sorted(), progress)
        assertTrue(states.first().distanceAlongRouteMeters < 1.0)
        assertEquals(route.distanceMeters, states.last().distanceAlongRouteMeters, 30.0)
    }

    @Test
    fun `remaining distance and time count down to zero`() {
        val route = straightRoute()
        val states = drive(NavigationEngine(route), route)

        assertEquals(route.distanceMeters, states.first().distanceRemainingMeters, 5.0)
        assertEquals(route.durationSeconds, states.first().secondsRemaining, 5.0)
        assertTrue(states.last().distanceRemainingMeters < 30.0)
        assertTrue(states.last().secondsRemaining < 10.0)
    }

    @Test
    fun `arrival is reported at the end and only there`() {
        val route = straightRoute()
        val states = drive(NavigationEngine(route), route)

        assertFalse(states.first().hasArrived)
        assertTrue(states.last().hasArrived)
        assertEquals(
            1,
            states.count { state -> state.announcements.any { it.kind == Announcement.Kind.ARRIVAL } },
            "arrival should be announced exactly once",
        )
    }

    @Test
    fun `a camera ahead is announced once and then drops behind`() {
        val camera = Fixtures.alpr("node/1", Fixtures.midBlock(0, 2, 0, 3))
        val route = straightRoute(listOf(camera))
        assertEquals(1, route.exposure.totalCount)

        val states = drive(NavigationEngine(route), route)

        val warnings = states.flatMap { it.announcements }.filter { it.kind == Announcement.Kind.DETECTOR }
        assertEquals(1, warnings.size, "one camera should produce exactly one warning")
        assertTrue(warnings.single().text.contains("Licence plate reader ahead"), warnings.single().text)

        assertTrue(states.first().detectorsAhead.isNotEmpty())
        assertTrue(states.last().detectorsAhead.isEmpty(), "a passed camera is no longer ahead")
    }

    @Test
    fun `camera warnings can be switched off`() {
        val camera = Fixtures.alpr("node/1", Fixtures.midBlock(0, 2, 0, 3))
        val route = straightRoute(listOf(camera))

        val engine = NavigationEngine(route, NavigationConfig(announceDetectors = false))
        val states = drive(engine, route)

        assertTrue(states.flatMap { it.announcements }.none { it.kind == Announcement.Kind.DETECTOR })
        // Still reported to the UI, just not spoken.
        assertTrue(states.first().detectorsAhead.isNotEmpty())
    }

    @Test
    fun `turn instructions are announced at each configured distance`() {
        val planner = RoutePlanner(grid, emptyList())
        val route = planner.plan(
            Fixtures.position(0, 0),
            Fixtures.position(3, 3),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        val engine = NavigationEngine(route, NavigationConfig(maneuverAnnounceMeters = listOf(200.0, 50.0)))
        val announcements = drive(engine, route).flatMap { it.announcements }
            .filter { it.kind == Announcement.Kind.MANEUVER }

        assertTrue(announcements.isNotEmpty())
        assertTrue(
            announcements.any { it.text.contains("turn ", ignoreCase = true) },
            announcements.joinToString { it.text },
        )
        // Each trigger distance fires at most once per manoeuvre.
        assertEquals(announcements.size, announcements.distinctBy { it.text }.size)
    }

    @Test
    fun `leaving the route is reported after a few fixes`() {
        val route = straightRoute()
        val config = NavigationConfig()
        val engine = NavigationEngine(route, config)
        val coords = listToCoords(route.geometry)

        // Start on the route, then jump 120 m to the side and stay there, still moving.
        engine.update(PositionFix(interpolateAlongCoords(coords, 100.0), accuracyMeters = 5.0, speedMetersPerSecond = 12.0))
        val offRoutePoint = destinationPoint(interpolateAlongCoords(coords, 120.0), bearing = 0.0, meters = 120.0)
        fun strayed() = PositionFix(offRoutePoint, accuracyMeters = 5.0, speedMetersPerSecond = 12.0)

        val first = engine.update(strayed())
        assertFalse(first.isOffRoute, "a single bad fix should not trigger a reroute")

        repeat(config.offRouteFixes - 2) { engine.update(strayed()) }
        val settled = engine.update(strayed())

        assertTrue(settled.isOffRoute)
        assertEquals(120.0, settled.deviationMeters, 10.0)
        assertTrue(settled.announcements.any { it.kind == Announcement.Kind.OFF_ROUTE })
    }

    @Test
    fun `a stationary vehicle is never declared off route`() {
        // A parked phone wanders by tens of metres. Announcing a wrong turn while sitting
        // at lights or in a car park is the most annoying possible false positive, and it
        // used to trigger a full reroute every time.
        val route = straightRoute()
        val engine = NavigationEngine(route)
        val coords = listToCoords(route.geometry)

        engine.update(PositionFix(interpolateAlongCoords(coords, 100.0), accuracyMeters = 5.0))
        val drifted = destinationPoint(interpolateAlongCoords(coords, 100.0), bearing = 0.0, meters = 150.0)

        repeat(8) { engine.update(PositionFix(drifted, accuracyMeters = 5.0, speedMetersPerSecond = 0.2)) }
        val state = engine.update(PositionFix(drifted, accuracyMeters = 5.0, speedMetersPerSecond = 0.2))

        assertFalse(state.isOffRoute)
    }

    @Test
    fun `being beside the centreline is tolerated`() {
        // A dual carriageway, a slip road or a wide junction all put an honestly-positioned
        // vehicle well off the line the router drew.
        val route = straightRoute()
        val engine = NavigationEngine(route)
        val coords = listToCoords(route.geometry)

        engine.update(PositionFix(interpolateAlongCoords(coords, 100.0), accuracyMeters = 8.0, speedMetersPerSecond = 15.0))
        val beside = destinationPoint(interpolateAlongCoords(coords, 200.0), bearing = 0.0, meters = 35.0)

        repeat(6) { engine.update(PositionFix(beside, accuracyMeters = 8.0, speedMetersPerSecond = 15.0)) }
        val state = engine.update(PositionFix(beside, accuracyMeters = 8.0, speedMetersPerSecond = 15.0))

        assertFalse(state.isOffRoute, "35 m from the centreline is normal, not a wrong turn")
    }

    @Test
    fun `the drawn position follows the route rather than the raw fix`() {
        val route = straightRoute()
        val engine = NavigationEngine(route)
        val coords = listToCoords(route.geometry)

        // A fix 25 m off the road, as happens between buildings.
        val actual = interpolateAlongCoords(coords, 300.0)
        val noisy = destinationPoint(actual, bearing = 0.0, meters = 25.0)
        val state = engine.update(PositionFix(noisy, accuracyMeters = 20.0, speedMetersPerSecond = 10.0))

        // The snapped position is on the road, not out where the fix landed.
        assertTrue(
            dev.shadowgps.core.geo.haversineMeters(state.snappedPosition, actual) < 5.0,
            "the drawn position should sit on the route",
        )
        // And it carries the road's direction, so the arrow does not spin.
        assertEquals(90.0, state.routeHeadingDegrees, 2.0)
    }

    @Test
    fun `a poor GPS fix does not count as leaving the route`() {
        val route = straightRoute()
        val engine = NavigationEngine(route)
        val coords = listToCoords(route.geometry)

        engine.update(PositionFix(interpolateAlongCoords(coords, 100.0), accuracyMeters = 5.0))
        val drifted = destinationPoint(interpolateAlongCoords(coords, 110.0), bearing = 0.0, meters = 50.0)

        // A 50 m deviation reported with 60 m accuracy is noise, not a wrong turn.
        repeat(5) { engine.update(PositionFix(drifted, accuracyMeters = 60.0)) }
        val state = engine.update(PositionFix(drifted, accuracyMeters = 60.0))

        assertFalse(state.isOffRoute)
    }

    @Test
    fun `progress does not jump ahead where the route crosses itself`() {
        // A route that doubles back means two points of the line are metres apart; matching
        // against the whole geometry would teleport the driver forward.
        val camera = Fixtures.alpr("node/1", Fixtures.midBlock(0, 2, 0, 3))
        val route = RoutePlanner(grid, listOf(camera))
            .plan(Fixtures.position(0, 0), Fixtures.position(0, 5), listOf(PrivacyProfile.GHOST))
            .routes.single()

        val states = drive(NavigationEngine(route), route, stepMeters = 20.0)
        val progress = states.map { it.distanceAlongRouteMeters }

        assertEquals(progress.sorted(), progress)
        for (i in 1 until progress.size) {
            assertTrue(
                progress[i] - progress[i - 1] < 60.0,
                "progress jumped ${progress[i] - progress[i - 1]} m in one 20 m step",
            )
        }
    }

    @Test
    fun `the current step follows the driver along the route`() {
        val planner = RoutePlanner(grid, emptyList())
        val route = planner.plan(
            Fixtures.position(0, 0),
            Fixtures.position(3, 3),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        val states = drive(NavigationEngine(route), route)
        val indices = states.map { it.currentStepIndex }

        assertEquals(0, indices.first())
        assertEquals(indices.sorted(), indices, "step index must never go backwards")
        assertEquals(route.steps.lastIndex, indices.last())
    }

    @Test
    fun `distance formatting reads naturally in both unit systems`() {
        assertEquals("450 m", Formatting.distance(447.0))
        assertEquals("1.2 km", Formatting.distance(1_240.0))
        assertEquals("12 km", Formatting.distance(12_400.0))
        assertEquals("0.8 mi", Formatting.distance(1_240.0, UnitSystem.IMPERIAL))

        assertEquals("in 400 metres", Formatting.spokenDistance(410.0))
        assertEquals("now", Formatting.spokenDistance(10.0))
        assertEquals("in half a mile", Formatting.spokenDistance(800.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `duration formatting reads naturally`() {
        assertEquals("under a minute", Formatting.duration(20.0))
        assertEquals("8 min", Formatting.duration(480.0))
        assertEquals("1 h 24", Formatting.duration(5_040.0))
        assertEquals("2 h", Formatting.duration(7_200.0))
        assertEquals("+6 min", Formatting.durationDelta(360.0))
        assertEquals("same time", Formatting.durationDelta(10.0))
    }

    @Test
    fun `an empty route does not break the engine`() {
        val route = Route(
            profile = PrivacyProfile.FASTEST,
            geometry = listOf(LatLon(35.99, -78.89), LatLon(35.9901, -78.89)),
            distanceMeters = 11.0,
            durationSeconds = 2.0,
            steps = emptyList(),
            exposure = dev.shadowgps.core.routing.RouteExposure.NONE,
        )

        val state = NavigationEngine(route).update(PositionFix(LatLon(35.99, -78.89)))

        assertEquals(0, state.currentStepIndex)
        assertTrue(state.detectorsAhead.isEmpty())
    }

    @Test
    fun `the manoeuvre after next is reported for a preview`() {
        // Turns arriving in quick succession are exactly when a driver needs warning of
        // the second one, so they take the first in the right lane.
        val planner = RoutePlanner(grid, emptyList())
        val route = planner.plan(
            Fixtures.position(0, 0),
            Fixtures.position(2, 2),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        val states = drive(NavigationEngine(route), route)

        val withPreview = states.filter { it.followingStep != null }
        assertTrue(withPreview.isNotEmpty(), "a route with two manoeuvres should preview the second")

        val sample = withPreview.first()
        assertTrue(sample.metersBetweenManeuvers >= 0.0)
        // The preview really is the step after the one being announced.
        assertEquals(
            route.steps.indexOf(sample.nextStep) + 1,
            route.steps.indexOf(sample.followingStep),
        )
    }

    @Test
    fun `there is nothing to preview on a straight run`() {
        val route = straightRoute()
        val state = NavigationEngine(route).update(
            PositionFix(route.geometry.first(), accuracyMeters = 5.0, speedMetersPerSecond = 10.0),
        )

        // Depart then arrive: the only thing after the next step is the end of the trip.
        assertEquals(null, state.followingStep)
    }
}
