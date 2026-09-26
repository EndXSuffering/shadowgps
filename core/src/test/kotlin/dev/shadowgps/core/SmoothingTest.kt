package dev.shadowgps.core

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.nav.POSITION_TIME_CONSTANT_SECONDS
import dev.shadowgps.core.nav.approachBearing
import dev.shadowgps.core.nav.approachPosition
import dev.shadowgps.core.nav.approachValue
import dev.shadowgps.core.nav.smoothingFactor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SmoothingTest {

    @Test
    fun `no time elapsed moves nothing, and no memory jumps straight there`() {
        assertEquals(0.0, smoothingFactor(0.0, 0.3))
        assertEquals(1.0, smoothingFactor(0.016, 0.0))
        assertEquals(1.0, smoothingFactor(0.016, -1.0))
    }

    @Test
    fun `one time constant closes about sixty-three percent of the gap`() {
        val factor = smoothingFactor(0.3, 0.3)
        assertTrue(abs(factor - 0.632) < 0.005, "expected ~0.632, was $factor")
    }

    @Test
    fun `the factor always stays a usable fraction`() {
        for (dt in listOf(0.001, 0.016, 0.033, 0.5, 5.0, 60.0)) {
            val factor = smoothingFactor(dt, POSITION_TIME_CONSTANT_SECONDS)
            assertTrue(factor in 0.0..1.0, "dt=$dt gave $factor")
        }
    }

    /**
     * The property that actually matters on screen: the same elapsed time produces the same
     * motion however it is chopped into frames. Without this the app looks different on a
     * 60 Hz phone and a 120 Hz one, and stutters whenever a frame is dropped.
     */
    @Test
    fun `smoothing does not depend on the frame rate`() {
        fun settle(frames: Int, dt: Double): Double {
            var value = 0.0
            repeat(frames) { value = approachValue(value, 100.0, smoothingFactor(dt, 0.4)) }
            return value
        }

        val at60 = settle(frames = 60, dt = 1.0 / 60)
        val at120 = settle(frames = 120, dt = 1.0 / 120)
        val at24 = settle(frames = 24, dt = 1.0 / 24)

        assertTrue(abs(at60 - at120) < 0.5, "60Hz gave $at60, 120Hz gave $at120")
        assertTrue(abs(at60 - at24) < 0.5, "60Hz gave $at60, 24Hz gave $at24")
    }

    /** The compass-needle bug: 350° to 10° is ten degrees clockwise, not 340 the other way. */
    @Test
    fun `bearings turn the short way round`() {
        val stepped = approachBearing(350.0, 10.0, 0.5)
        assertTrue(stepped > 355.0 || stepped < 5.0, "expected to pass through north, got $stepped")

        assertEquals(355.0, approachBearing(350.0, 10.0, 0.25), 1e-9)
        assertEquals(5.0, approachBearing(10.0, 350.0, 0.25), 1e-9)
    }

    @Test
    fun `a bearing converges on its target without overshooting`() {
        var heading = 350.0
        repeat(100) { heading = approachBearing(heading, 80.0, 0.3) }
        assertEquals(80.0, heading, 0.01)
    }

    @Test
    fun `a full step lands exactly on the target`() {
        assertEquals(42.0, approachBearing(300.0, 42.0, 1.0), 1e-9)
        assertEquals(7.0, approachValue(-3.0, 7.0, 1.0), 1e-9)

        val there = approachPosition(LatLon(29.4, -98.5), LatLon(29.5, -98.4), 1.0)
        assertEquals(29.5, there.lat, 1e-9)
        assertEquals(-98.4, there.lon, 1e-9)
    }

    @Test
    fun `a position converges on the fix`() {
        val target = LatLon(29.4241, -98.4936)
        var shown = LatLon(29.4200, -98.5000)
        repeat(200) { shown = approachPosition(shown, target, 0.25) }
        assertTrue(haversineMeters(shown, target) < 0.01)
    }

    /** Crossing the antimeridian must drift across it, not sweep back through Greenwich. */
    @Test
    fun `longitude takes the short way across the date line`() {
        val stepped = approachPosition(LatLon(0.0, 179.0), LatLon(0.0, -179.0), 0.5)
        assertEquals(180.0, abs(stepped.lon), 1e-9)

        val back = approachPosition(LatLon(0.0, -179.0), LatLon(0.0, 179.0), 0.5)
        assertEquals(180.0, abs(back.lon), 1e-9)
    }

    @Test
    fun `longitude stays in range`() {
        val stepped = approachPosition(LatLon(0.0, 179.9), LatLon(0.0, -179.9), 0.9)
        assertTrue(stepped.lon in -180.0..180.0, "out of range: ${stepped.lon}")
    }
}
