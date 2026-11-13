package dev.shadowgps.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whether it is dark out, worked out from where and when rather than looked up.
 *
 * For switching the map to its night colours by itself. A fixed clock time would be wrong
 * for half the year — sunset in San Antonio moves by more than two hours between June and
 * December — and asking a server would mean telling one where the driver is, so the sun's
 * position is computed here from the standard low-precision formulas. They are good to a
 * small fraction of a degree, which is far finer than the question needs.
 */
object Daylight {

    /**
     * How far below the horizon the sun has to be before it counts as dark.
     *
     * A little past sunset rather than at it: the sky stays bright for a while after the sun
     * goes, and a map that turns dark the instant it does is dark too early.
     */
    const val DARK_BELOW_DEGREES = -4.0

    fun isDark(position: LatLon, epochMillis: Long): Boolean =
        solarElevationDegrees(position, epochMillis) < DARK_BELOW_DEGREES

    /** Angle of the sun above the horizon at [position], negative when it is below. */
    fun solarElevationDegrees(position: LatLon, epochMillis: Long): Double {
        // Days since noon UTC on 1 January 2000, the epoch the formulas are written against.
        val days = epochMillis / MILLIS_PER_DAY - DAYS_FROM_1970_TO_J2000

        val meanLongitude = normalizeBearing(280.460 + 0.9856474 * days)
        val meanAnomaly = normalizeBearing(357.528 + 0.9856003 * days) * DEG
        val eclipticLongitude =
            (meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly)) * DEG
        val obliquity = (23.439 - 0.0000004 * days) * DEG

        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))

        // Sidereal time at Greenwich, in degrees, then at the observer's own longitude.
        val greenwich = normalizeBearing((18.697374558 + 24.06570982441908 * days) * 15.0)
        val hourAngle = (greenwich + position.lon) * DEG - rightAscension

        val latitude = position.lat * DEG
        val sine = sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle)
        return asin(sine.coerceIn(-1.0, 1.0)) / DEG
    }

    private const val DEG = Math.PI / 180.0
    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val DAYS_FROM_1970_TO_J2000 = 10_957.5
}
