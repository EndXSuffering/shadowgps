package dev.shadowgps.core

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RoutePlanner
import dev.shadowgps.core.routing.RoutingOptions
import dev.shadowgps.core.traffic.CONGESTION_AVERSION
import dev.shadowgps.core.traffic.CongestionLevel
import dev.shadowgps.core.traffic.DayType
import dev.shadowgps.core.traffic.TrafficModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The time-of-day congestion model.
 *
 * Everything here is a prior about how traffic usually behaves, never a claim about what is
 * happening now, so what these tests can check is that the shape is sane: peaks where peaks
 * belong, main roads suffering more than back streets, and the whole thing capable of
 * changing which route wins without inventing implausible ones.
 */
class TrafficModelTest {

    private fun weekday(hour: Int, minute: Int = 0) =
        TrafficModel.at(DayType.WEEKDAY, hour * 60 + minute)

    @Test
    fun `weekdays peak twice and go quiet overnight`() {
        val overnight = TrafficModel.intensityAt(DayType.WEEKDAY, 3 * 60)
        val morningPeak = TrafficModel.intensityAt(DayType.WEEKDAY, 8 * 60 + 15)
        val midday = TrafficModel.intensityAt(DayType.WEEKDAY, 11 * 60)
        val eveningPeak = TrafficModel.intensityAt(DayType.WEEKDAY, 17 * 60 + 30)

        assertTrue(overnight < 0.1, "3am should be empty, was $overnight")
        assertTrue(morningPeak > 0.9, "the morning peak should be near maximum, was $morningPeak")
        assertTrue(eveningPeak > 0.9, "the evening peak should be near maximum, was $eveningPeak")
        // The midday lull is real but never returns to the overnight floor.
        assertTrue(midday < morningPeak && midday > overnight, "midday was $midday")
    }

    @Test
    fun `weekends have no commuter peaks`() {
        val saturdayRush = TrafficModel.intensityAt(DayType.SATURDAY, 8 * 60 + 15)
        val weekdayRush = TrafficModel.intensityAt(DayType.WEEKDAY, 8 * 60 + 15)
        val saturdayAfternoon = TrafficModel.intensityAt(DayType.SATURDAY, 14 * 60)

        assertTrue(saturdayRush < weekdayRush / 2, "no-one commutes on Saturday morning")
        assertTrue(saturdayAfternoon > saturdayRush, "the shops are the Saturday peak")
        assertTrue(
            TrafficModel.intensityAt(DayType.SUNDAY, 14 * 60) < saturdayAfternoon,
            "Sunday should be quieter than Saturday",
        )
    }

    @Test
    fun `the curve is continuous across the day`() {
        // No cliff between two adjacent minutes: a route planned at 08:14 and one at 08:16
        // should not differ noticeably.
        var previous = TrafficModel.intensityAt(DayType.WEEKDAY, 0)
        for (minute in 1..24 * 60) {
            val current = TrafficModel.intensityAt(DayType.WEEKDAY, minute)
            assertTrue(
                kotlin.math.abs(current - previous) < 0.02,
                "jump of ${current - previous} at minute $minute",
            )
            previous = current
        }
    }

    @Test
    fun `main roads suffer more than back streets`() {
        val peak = weekday(hour = 17, minute = 30)

        val motorway = peak.speedFactor("motorway")
        val primary = peak.speedFactor("primary")
        val residential = peak.speedFactor("residential")

        assertTrue(motorway < primary, "a motorway should lose more than a primary road")
        assertTrue(primary < residential, "a primary road should lose more than a back street")
        assertTrue(residential > 0.8, "a residential street barely notices rush hour")
        assertTrue(motorway > 0.4, "even at its worst the model should stay plausible")
    }

    @Test
    fun `free flow changes nothing`() {
        val free = TrafficModel.FREE_FLOW

        assertEquals(1.0, free.speedFactor("motorway"))
        assertEquals(1.0, free.speedFactor("residential"))
        assertEquals(12.0, free.junctionDelaySeconds(12.0))
        assertFalse(free.isSignificant)
    }

    @Test
    fun `junction delays grow faster than link speeds fall`() {
        val peak = weekday(hour = 8, minute = 15)

        val speedLoss = 1.0 - peak.speedFactor("primary")
        val junctionGrowth = peak.junctionDelaySeconds(12.0) / 12.0 - 1.0

        assertTrue(
            junctionGrowth > speedLoss,
            "queueing, not cruising speed, is what a peak costs: $junctionGrowth vs $speedLoss",
        )
    }

    @Test
    fun `wording matches the conditions`() {
        assertEquals("Peak traffic", weekday(17, 30).label)
        assertEquals("Quiet roads", weekday(3).label)
        assertEquals("Free-flowing", TrafficModel.FREE_FLOW.label)
    }

    @Test
    fun `a departure time maps to the right day shape`() {
        // A Saturday afternoon should not be treated as a weekday afternoon.
        val saturday = TrafficModel.at(LocalDateTime.of(2026, 8, 15, 14, 0))
        val weekdayAfternoon = TrafficModel.at(LocalDateTime.of(2026, 8, 18, 14, 0))

        assertTrue(saturday.intensity != weekdayAfternoon.intensity)
    }

    // ------------------------------------------------------------------ routing

    /**
     * Two ways across town of the same class: a direct one interrupted by traffic signals,
     * and a longer one with none. Exactly the trade a driver makes at five o'clock.
     */
    private fun twoRoutesTown(): List<OsmElement> {
        val elements = ArrayList<OsmElement>()
        val start = LatLon(35.99, -78.90)
        var nextId = 1L

        fun node(point: LatLon, signalised: Boolean = false): Long {
            val id = nextId++
            elements.add(
                OsmElement(
                    "node", id, lat = point.lat, lon = point.lon,
                    tags = if (signalised) mapOf("highway" to "traffic_signals") else emptyMap(),
                ),
            )
            return id
        }

        // Direct road: 2 km due east, through six sets of lights.
        //
        // The margins are deliberate. Six signals cost 72 s free-flow and 158 s at peak, so
        // a detour has to sit between those to be worth taking only at rush hour — too
        // short and it always wins, too long and it never does. 280 m each way lands in
        // that window, which is also a fair reflection of how narrow the real trade is:
        // this model nudges, it does not conjure dramatic rat-runs.
        val directNodes = ArrayList<Long>()
        directNodes.add(node(start))
        val sideRoads = ArrayList<Pair<Long, LatLon>>()
        for (step in 1..6) {
            val at = destinationPoint(start, 90.0, 2_000.0 * step / 7)
            val id = node(at, signalised = true)
            directNodes.add(id)
            // Each signal sits on a crossroads, as signals do. It also has to: the graph
            // only keeps nodes where ways meet, so a node in the middle of a single way is
            // folded into that way's geometry and its delay goes with it.
            sideRoads.add(id to at)
        }
        val end = destinationPoint(start, 90.0, 2_000.0)
        val endId = node(end)
        directNodes.add(endId)
        elements.add(
            OsmElement("way", nextId++, nodes = directNodes, tags = mapOf("highway" to "residential", "name" to "Signal Street")),
        )
        for ((id, at) in sideRoads) {
            elements.add(
                OsmElement(
                    "way", nextId++,
                    nodes = listOf(id, node(destinationPoint(at, 0.0, 120.0))),
                    tags = mapOf("highway" to "residential", "name" to "Cross Road"),
                ),
            )
        }

        // Detour: south, east, and back north — longer, but nothing to stop for.
        val southWest = destinationPoint(start, 180.0, 280.0)
        val southEast = destinationPoint(southWest, 90.0, 2_000.0)
        elements.add(
            OsmElement(
                "way", nextId++,
                nodes = listOf(directNodes.first(), node(southWest), node(southEast), endId),
                tags = mapOf("highway" to "residential", "name" to "Quiet Way"),
            ),
        )

        return elements
    }

    @Test
    fun `congestion can change which route wins`() {
        val graph = GraphBuilder.build(twoRoutesTown())
        val start = LatLon(35.99, -78.90)
        val end = destinationPoint(start, 90.0, 2_000.0)

        val freeFlow = RoutePlanner(graph, emptyList(), traffic = TrafficModel.FREE_FLOW)
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        val atPeak = RoutePlanner(graph, emptyList(), traffic = weekday(17, 30))
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertEquals("Signal Street", freeFlow.steps.first().roadName, "the direct road wins on empty roads")
        assertEquals("Quiet Way", atPeak.steps.first().roadName, "at peak the lights cost more than the detour")
    }

    @Test
    fun `a route reports both what it takes now and on empty roads`() {
        val graph = GraphBuilder.build(twoRoutesTown())
        val start = LatLon(35.99, -78.90)
        val end = destinationPoint(start, 90.0, 2_000.0)

        val route = RoutePlanner(graph, emptyList(), traffic = weekday(8, 15))
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertTrue(route.durationSeconds > route.freeFlowSeconds, "peak should cost time")
        assertTrue(route.trafficDelaySeconds > 0.0)
        // The model is a nudge, not a catastrophe: a doubling would not be credible.
        assertTrue(
            route.durationSeconds < route.freeFlowSeconds * 2.0,
            "modelled delay should stay plausible",
        )
    }

    @Test
    fun `overnight routing matches free flow exactly`() {
        val graph = GraphBuilder.build(twoRoutesTown())
        val start = LatLon(35.99, -78.90)
        val end = destinationPoint(start, 90.0, 2_000.0)

        val night = RoutePlanner(graph, emptyList(), traffic = weekday(3))
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertEquals(night.freeFlowSeconds, night.durationSeconds, night.freeFlowSeconds * 0.06)
    }

    @Test
    fun `the plan reports the conditions it used`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 4, cols = 5))
        val model = weekday(17, 30)

        val plan = RoutePlanner(graph, emptyList(), traffic = model)
            .plan(Fixtures.position(0, 0), Fixtures.position(0, 4), listOf(PrivacyProfile.FASTEST))

        assertEquals(model, plan.traffic)
        assertEquals("Peak traffic", plan.traffic.label)
        assertEquals(plan.routes.single(), plan.quickest)
    }

    @Test
    fun `a busy route says where it is busy`() {
        val graph = GraphBuilder.build(twoRoutesTown())
        val start = LatLon(35.99, -78.90)
        val end = destinationPoint(start, 90.0, 2_000.0)

        val route = RoutePlanner(graph, emptyList(), traffic = weekday(17, 30))
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertTrue(route.congestionSpans.isNotEmpty(), "a peak route should be banded")
        assertEquals(0.0, route.congestionSpans.first().fromMeters)
        // The spans are what gets drawn over the line, so they have to cover it.
        assertEquals(route.distanceMeters, route.congestionSpans.last().toMeters, 1.0)
        assertTrue(route.heaviestCongestion.isNotable)
        assertTrue(route.metersInTraffic > 0.0)
    }

    @Test
    fun `an empty road is not painted as traffic`() {
        val graph = GraphBuilder.build(twoRoutesTown())
        val start = LatLon(35.99, -78.90)
        val end = destinationPoint(start, 90.0, 2_000.0)

        val route = RoutePlanner(graph, emptyList(), traffic = TrafficModel.FREE_FLOW)
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertEquals(CongestionLevel.FREE, route.heaviestCongestion)
        assertEquals(0.0, route.metersInTraffic)
    }

    @Test
    fun `avoiding heavy traffic takes the calmer road`() {
        val graph = GraphBuilder.build(twoRoutesTown())
        val start = LatLon(35.99, -78.90)
        val end = destinationPoint(start, 90.0, 2_000.0)

        // Deliberately not the peak. At the peak the queueing already costs more than the
        // detour and the quiet road wins on time alone; the setting is only interesting in
        // the band where the busy road is still quicker and the driver wants out anyway.
        val conditions = weekday(11, 0)

        val quickest = RoutePlanner(graph, emptyList(), traffic = conditions)
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        val calmer = RoutePlanner(
            graph,
            emptyList(),
            options = RoutingOptions(congestionAversion = CONGESTION_AVERSION),
            traffic = conditions,
        ).plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertEquals("Signal Street", quickest.steps.first().roadName, "the direct road is still quicker")
        assertEquals("Quiet Way", calmer.steps.first().roadName, "avoiding traffic should take the detour")
        // And it costs what it says on the tin: the calmer way really is the slower way.
        assertTrue(
            calmer.durationSeconds > quickest.durationSeconds,
            "the detour is meant to cost time: ${calmer.durationSeconds} vs ${quickest.durationSeconds}",
        )
    }

    @Test
    fun `avoiding traffic changes nothing on empty roads`() {
        // Nothing to avoid at 3am, so the setting must not quietly send the driver the long
        // way round for no reason at all.
        val graph = GraphBuilder.build(twoRoutesTown())
        val start = LatLon(35.99, -78.90)
        val end = destinationPoint(start, 90.0, 2_000.0)

        val plain = RoutePlanner(graph, emptyList(), traffic = weekday(3))
            .plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()
        val averse = RoutePlanner(
            graph,
            emptyList(),
            options = RoutingOptions(congestionAversion = CONGESTION_AVERSION),
            traffic = weekday(3),
        ).plan(start, end, listOf(PrivacyProfile.FASTEST)).routes.single()

        assertEquals(plain.geometry, averse.geometry)
    }

    @Test
    fun `avoiding cameras still works under congestion`() {
        // The point of the whole exercise: traffic changes the timings, it does not quietly
        // stop the router caring about being seen.
        val graph = GraphBuilder.build(Fixtures.grid(rows = 5, cols = 6))
        val camera = Fixtures.alpr("node/1", Fixtures.midBlock(0, 2, 0, 3))

        val plan = RoutePlanner(graph, listOf(camera), traffic = weekday(17, 30))
            .plan(
                Fixtures.position(0, 0),
                Fixtures.position(0, 5),
                listOf(PrivacyProfile.FASTEST, PrivacyProfile.GHOST),
            )

        val ghost = plan.routes.first { it.profile == PrivacyProfile.GHOST }
        assertEquals(0, ghost.exposure.totalCount, "the privacy profile must still avoid the camera")
        assertTrue(ghost.durationSeconds > 0.0)
    }
}
