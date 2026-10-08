package dev.shadowgps.core

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.routing.DetectorEncounter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EncounterTest {

    private fun encounterAt(alongMeters: Double, rangeMeters: Double) = DetectorEncounter(
        detector = Detector(
            id = "node/1",
            kind = DetectorKind.ALPR,
            position = LatLon(29.4, -98.5),
            rangeMeters = rangeMeters,
            fovDegrees = 110.0,
        ),
        distanceMeters = 5.0,
        alongRouteMeters = alongMeters,
        weight = 1.0,
    )

    @Test
    fun `a device ahead is not behind`() {
        assertFalse(encounterAt(alongMeters = 1_000.0, rangeMeters = 70.0).isBehind(400.0))
    }

    /**
     * The case the map got wrong: drawing level with a plate reader is not the end of being
     * read by it. Many face departing traffic and see the car best as it pulls away.
     */
    @Test
    fun `drawing level with a device is not leaving it behind`() {
        val encounter = encounterAt(alongMeters = 1_000.0, rangeMeters = 70.0)
        assertFalse(encounter.isBehind(1_000.0))
        assertFalse(encounter.isBehind(1_050.0))
        assertTrue(encounter.isBehind(1_071.0))
    }

    @Test
    fun `a device of tiny range still waits out the floor`() {
        val encounter = encounterAt(alongMeters = 1_000.0, rangeMeters = 5.0)
        assertFalse(encounter.isBehind(1_010.0))
        assertTrue(encounter.isBehind(1_000.0 + DetectorEncounter.PASSED_MARGIN_METERS + 0.1))
    }
}
