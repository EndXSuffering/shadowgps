package dev.shadowgps.core

import dev.shadowgps.core.geo.Daylight
import dev.shadowgps.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DaylightTest {

    private val sanAntonio = LatLon(29.4241, -98.4936)
    private val central = ZoneId.of("America/Chicago")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: ZoneId = central): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `midday is light and the small hours are dark`() {
        assertFalse(Daylight.isDark(sanAntonio, at(2026, 6, 21, 13)))
        assertTrue(Daylight.isDark(sanAntonio, at(2026, 6, 21, 2)))
        assertFalse(Daylight.isDark(sanAntonio, at(2026, 12, 21, 12)))
        assertTrue(Daylight.isDark(sanAntonio, at(2026, 12, 21, 23)))
    }

    /**
     * The reason this is computed rather than a fixed clock time: sunset in San Antonio is
     * about 5:40 pm in mid-November and about 8:35 pm in late June.
     */
    @Test
    fun `dusk follows the season`() {
        // November: light at five, dark by half past six.
        assertFalse(Daylight.isDark(sanAntonio, at(2026, 11, 13, 17, 0)))
        assertTrue(Daylight.isDark(sanAntonio, at(2026, 11, 13, 18, 30)))
        // June: still light at eight, dark by half past nine.
        assertFalse(Daylight.isDark(sanAntonio, at(2026, 6, 21, 20, 0)))
        assertTrue(Daylight.isDark(sanAntonio, at(2026, 6, 21, 21, 30)))
    }

    @Test
    fun `the sun is nearly overhead on the equator at an equinox noon`() {
        val utc = ZoneId.of("UTC")
        val elevation = Daylight.solarElevationDegrees(LatLon(0.0, 0.0), at(2026, 3, 20, 12, 7, utc))
        assertTrue(elevation > 88.0, "elevation was $elevation")
    }

    /** Checked against published sunrise for San Antonio on 13 Nov 2026: about 6:54 am. */
    @Test
    fun `the horizon crossing lands within a few minutes of the almanac`() {
        val before = Daylight.solarElevationDegrees(sanAntonio, at(2026, 11, 13, 6, 40))
        val after = Daylight.solarElevationDegrees(sanAntonio, at(2026, 11, 13, 7, 10))
        assertTrue(before < 0.0, "sun already up at 6:40: $before")
        assertTrue(after > 0.0, "sun not yet up at 7:10: $after")
    }

    /**
     * Longyearbyen, far enough north that midwinter noon is properly dark. (Tromsø would
     * not do: at 69.6°N the midwinter sun gets to within three degrees of the horizon at
     * noon, which is twilight, and correctly not counted as dark.)
     */
    @Test
    fun `polar summer never gets dark and polar winter never gets light`() {
        val longyearbyen = LatLon(78.22, 15.65)
        val oslo = ZoneId.of("Europe/Oslo")
        assertEquals(false, Daylight.isDark(longyearbyen, at(2026, 6, 21, 0, 0, oslo)))
        assertEquals(true, Daylight.isDark(longyearbyen, at(2026, 12, 21, 12, 0, oslo)))
    }
}
