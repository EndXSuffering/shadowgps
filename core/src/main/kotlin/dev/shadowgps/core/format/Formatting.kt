package dev.shadowgps.core.format

import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class UnitSystem { METRIC, IMPERIAL }

/** Shared distance and duration wording, so the map, the route list and the voice agree. */
object Formatting {

    private const val FEET_PER_METER = 3.280839895
    private const val METERS_PER_MILE = 1609.344

    /** Compact distance for on-screen labels, e.g. `450 m`, `1.2 km`, `0.4 mi`. */
    fun distance(meters: Double, units: UnitSystem = UnitSystem.METRIC): String = when (units) {
        UnitSystem.METRIC -> when {
            meters < 950 -> "${roundToNearest(meters, step = if (meters < 100) 10.0 else 50.0)} m"
            meters < 10_000 -> "${trim1((meters / 1000))} km"
            else -> "${(meters / 1000).roundToInt()} km"
        }

        UnitSystem.IMPERIAL -> {
            val feet = meters * FEET_PER_METER
            when {
                feet < 1000 -> "${roundToNearest(feet, step = if (feet < 300) 50.0 else 100.0)} ft"
                meters < 16_000 -> "${trim1(meters / METERS_PER_MILE)} mi"
                else -> "${(meters / METERS_PER_MILE).roundToInt()} mi"
            }
        }
    }

    /** Distance phrased for speech, e.g. `in 400 metres`, `in a quarter of a mile`. */
    fun spokenDistance(meters: Double, units: UnitSystem = UnitSystem.METRIC): String = when (units) {
        UnitSystem.METRIC -> when {
            meters < 30 -> "now"
            meters < 950 -> "in ${roundToNearest(meters, if (meters < 100) 10.0 else 50.0)} metres"
            meters < 1500 -> "in one kilometre"
            else -> "in ${trim1(meters / 1000)} kilometres"
        }

        UnitSystem.IMPERIAL -> {
            val miles = meters / METERS_PER_MILE
            when {
                meters < 30 -> "now"
                miles < 0.1 -> "in ${roundToNearest(meters * FEET_PER_METER, 50.0)} feet"
                miles < 0.2 -> "in a tenth of a mile"
                miles < 0.35 -> "in a quarter of a mile"
                miles < 0.6 -> "in half a mile"
                miles < 1.3 -> "in one mile"
                else -> "in ${trim1(miles)} miles"
            }
        }
    }

    /** Duration for labels, e.g. `8 min`, `1 h 24`. */
    fun duration(seconds: Double): String {
        val total = seconds.roundToLong().coerceAtLeast(0)
        val minutes = (total + 30) / 60
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            else -> {
                val hours = minutes / 60
                val rest = minutes % 60
                if (rest == 0L) "$hours h" else "$hours h ${rest.toString().padStart(2, '0')}"
            }
        }
    }

    /** Signed difference against a baseline, e.g. `+6 min`, `−2 min`, `same time`. */
    fun durationDelta(seconds: Double): String {
        val minutes = (seconds / 60).roundToInt()
        return when {
            minutes == 0 -> "same time"
            minutes > 0 -> "+$minutes min"
            else -> "−${-minutes} min"
        }
    }

    private fun roundToNearest(value: Double, step: Double): Long =
        ((value / step).roundToLong() * step.toLong()).coerceAtLeast(step.toLong())

    private fun trim1(value: Double): String {
        val rounded = (value * 10).roundToLong() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}
