package dev.shadowgps.core.traffic

import dev.shadowgps.core.graph.RoadEdge
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDateTime

/** How a day's traffic is shaped. Commuting is a weekday phenomenon. */
enum class DayType {
    WEEKDAY,
    SATURDAY,
    SUNDAY,
    ;

    companion object {
        fun of(day: DayOfWeek): DayType = when (day) {
            DayOfWeek.SATURDAY -> SATURDAY
            DayOfWeek.SUNDAY -> SUNDAY
            else -> WEEKDAY
        }
    }
}

/**
 * Typical congestion for a time of day.
 *
 * **This is a model, not a measurement.** It knows that a motorway at half past five on a
 * Tuesday is usually slower than at three in the morning, and nothing whatsoever about the
 * lorry that jackknifed twenty minutes ago. Every real-time traffic feed works by
 * continuously collecting where its users are, which is precisely what this app exists to
 * avoid, so what is on offer here is the part that can be had honestly: a prior, applied
 * locally, that makes arrival times realistic and nudges routing away from the roads that
 * predictably seize up.
 *
 * Two things drive it. Roads differ in how badly they suffer — a motorway can lose half its
 * speed at peak while a residential street barely notices, because what collapses is
 * junction throughput and lane capacity on the routes everyone shares. And junction delay
 * grows faster than link speed falls: at peak a signalised junction can take two or three
 * cycles to clear, which is why a route with six sets of lights loses to a longer one with
 * none long before the roads themselves are full.
 */
@Serializable
data class TrafficModel(
    /** 0 for empty roads, 1 for the worst of the peak. */
    val intensity: Double,
    val enabled: Boolean = true,
) {
    init {
        require(intensity in 0.0..1.0) { "intensity out of range: $intensity" }
    }

    /** Fraction of free-flow speed a road of this class is assumed to manage. */
    fun speedFactor(highway: String): Double {
        if (!enabled || intensity <= 0.0) return 1.0
        val sensitivity = PEAK_SENSITIVITY[highway] ?: DEFAULT_SENSITIVITY
        return 1.0 - sensitivity * intensity * MAX_SLOWDOWN
    }

    /** Seconds to traverse [edge] under these conditions. */
    fun travelSeconds(edge: RoadEdge): Double = edge.travelSeconds / speedFactor(edge.highway)

    /**
     * Junction delay under these conditions.
     *
     * Scales harder than link speed because queueing, not cruising speed, is what a peak
     * actually costs a driver.
     */
    fun junctionDelaySeconds(freeFlowSeconds: Double): Double {
        if (!enabled || intensity <= 0.0) return freeFlowSeconds
        return freeFlowSeconds * (1.0 + intensity * JUNCTION_PEAK_MULTIPLIER)
    }

    /** Wording for the UI, so the driver knows which end of the model they are on. */
    val label: String
        get() = when {
            !enabled -> "Free-flowing"
            intensity >= 0.85 -> "Peak traffic"
            intensity >= 0.6 -> "Busy"
            intensity >= 0.3 -> "Moderate traffic"
            intensity > 0.05 -> "Light traffic"
            else -> "Quiet roads"
        }

    val isSignificant: Boolean get() = enabled && intensity > 0.05

    companion object {
        /** Traffic ignored entirely: every road at its free-flow speed. */
        val FREE_FLOW = TrafficModel(intensity = 0.0, enabled = false)

        /** Worst-case fraction of speed lost, at full intensity on the worst-hit road. */
        private const val MAX_SLOWDOWN = 0.62

        /** Junction delays at full intensity are this much worse again. */
        private const val JUNCTION_PEAK_MULTIPLIER = 1.2

        private const val DEFAULT_SENSITIVITY = 0.4

        /**
         * How much each road class suffers at peak.
         *
         * Ordered by how much through-traffic a class carries: everyone funnels onto the
         * motorways and main roads at once, while the residential street outside someone's
         * house carries roughly the same handful of cars whatever the hour.
         */
        private val PEAK_SENSITIVITY: Map<String, Double> = mapOf(
            "motorway" to 0.85,
            "motorway_link" to 0.80,
            "trunk" to 0.80,
            "trunk_link" to 0.75,
            "primary" to 0.75,
            "primary_link" to 0.70,
            "secondary" to 0.65,
            "secondary_link" to 0.60,
            "tertiary" to 0.50,
            "tertiary_link" to 0.45,
            "unclassified" to 0.35,
            "residential" to 0.20,
            "living_street" to 0.15,
            "service" to 0.15,
            "road" to 0.40,
        )

        /** Conditions for a departure time. */
        fun at(dayType: DayType, minuteOfDay: Int): TrafficModel =
            TrafficModel(intensity = intensityAt(dayType, minuteOfDay), enabled = true)

        fun at(moment: LocalDateTime): TrafficModel =
            at(DayType.of(moment.dayOfWeek), moment.hour * 60 + moment.minute)

        /**
         * The intensity curve, interpolated between control points.
         *
         * Weekdays have the familiar twin commuter peaks with a midday lull that never
         * quite returns to the overnight floor. Saturday is a single broad afternoon hump
         * of shopping and errands; Sunday the same shape, lower.
         */
        fun intensityAt(dayType: DayType, minuteOfDay: Int): Double {
            val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY)
            val curve = when (dayType) {
                DayType.WEEKDAY -> WEEKDAY_CURVE
                DayType.SATURDAY -> SATURDAY_CURVE
                DayType.SUNDAY -> SUNDAY_CURVE
            }
            return interpolate(curve, minute)
        }

        private const val MINUTES_PER_DAY = 24 * 60

        private fun hm(hour: Int, minute: Int = 0): Int = hour * 60 + minute

        private val WEEKDAY_CURVE: List<Pair<Int, Double>> = listOf(
            hm(0) to 0.0,
            hm(5) to 0.05,
            hm(6, 30) to 0.35,
            hm(7, 30) to 0.90,
            hm(8, 15) to 1.00,
            hm(9, 30) to 0.55,
            hm(11) to 0.40,
            hm(12, 30) to 0.50,
            hm(14) to 0.45,
            hm(15, 30) to 0.70,
            hm(16, 45) to 0.95,
            hm(17, 30) to 1.00,
            hm(18, 30) to 0.75,
            hm(19, 30) to 0.40,
            hm(21) to 0.15,
            hm(23) to 0.05,
            hm(24) to 0.0,
        )

        private val SATURDAY_CURVE: List<Pair<Int, Double>> = listOf(
            hm(0) to 0.0,
            hm(7) to 0.05,
            hm(10) to 0.35,
            hm(12) to 0.50,
            hm(14) to 0.55,
            hm(16) to 0.50,
            hm(18) to 0.35,
            hm(21) to 0.15,
            hm(24) to 0.02,
        )

        private val SUNDAY_CURVE: List<Pair<Int, Double>> = listOf(
            hm(0) to 0.0,
            hm(8) to 0.03,
            hm(11) to 0.20,
            hm(13) to 0.32,
            hm(16) to 0.35,
            hm(18) to 0.25,
            hm(21) to 0.10,
            hm(24) to 0.02,
        )

        private fun interpolate(curve: List<Pair<Int, Double>>, minute: Int): Double {
            if (minute <= curve.first().first) return curve.first().second
            if (minute >= curve.last().first) return curve.last().second

            for (i in 0 until curve.size - 1) {
                val (startMinute, startValue) = curve[i]
                val (endMinute, endValue) = curve[i + 1]
                if (minute in startMinute..endMinute) {
                    val span = (endMinute - startMinute).toDouble()
                    if (span <= 0.0) return endValue
                    val progress = (minute - startMinute) / span
                    return startValue + (endValue - startValue) * progress
                }
            }
            return curve.last().second
        }
    }
}
