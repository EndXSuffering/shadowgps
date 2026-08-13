package dev.shadowgps.core.graph

/**
 * Turns OSM tags into an assumed driving speed.
 *
 * Free-flow speed limits over-estimate real journey times, especially in town, so a class
 * dependent realism factor is applied on top. The absolute numbers matter less than their
 * ratios: the router only ever compares one route against another.
 */
object Speeds {

    /** Assumed speed in km/h when a way carries no usable `maxspeed`. */
    private val DEFAULT_KPH: Map<String, Double> = mapOf(
        "motorway" to 110.0,
        "motorway_link" to 70.0,
        "trunk" to 90.0,
        "trunk_link" to 60.0,
        "primary" to 65.0,
        "primary_link" to 45.0,
        "secondary" to 55.0,
        "secondary_link" to 40.0,
        "tertiary" to 45.0,
        "tertiary_link" to 35.0,
        "unclassified" to 40.0,
        "residential" to 30.0,
        "living_street" to 15.0,
        "service" to 20.0,
        "road" to 30.0,
    )

    /**
     * Fraction of the posted limit actually achieved, accounting for junctions, parked
     * cars and traffic. Motorways run close to the limit; residential streets nowhere near.
     */
    private val REALISM: Map<String, Double> = mapOf(
        "motorway" to 0.95,
        "motorway_link" to 0.85,
        "trunk" to 0.90,
        "trunk_link" to 0.80,
        "primary" to 0.80,
        "primary_link" to 0.75,
        "secondary" to 0.78,
        "secondary_link" to 0.72,
        "tertiary" to 0.75,
        "tertiary_link" to 0.70,
        "unclassified" to 0.70,
        "residential" to 0.65,
        "living_street" to 0.55,
        "service" to 0.55,
        "road" to 0.70,
    )

    private const val FALLBACK_KPH = 30.0
    private const val FALLBACK_REALISM = 0.7

    /** Highest speed the graph will ever produce; used as the A* heuristic's bound. */
    const val MAX_PLAUSIBLE_KPH: Double = 130.0

    /**
     * Parses a `maxspeed` value into km/h.
     *
     * Handles the plain number (implicitly km/h), explicit `mph` and `knots`, the
     * `none`/`walk` keywords, and country-coded implicit limits such as `DE:urban`.
     * Returns null for anything unrecognised so the caller can fall back to defaults.
     */
    fun parseMaxspeedKph(raw: String?): Double? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

        when (value) {
            "none" -> return 130.0
            "walk" -> return 7.0
            "signals", "variable", "unknown" -> return null
        }

        // Country-coded implicit limits, e.g. "DE:urban", "GB:nsl_single".
        if (":" in value) {
            return when (value.substringAfter(":")) {
                "urban", "zone30", "living_street" -> 30.0
                "rural", "nsl_single" -> 80.0
                "motorway", "nsl_dual" -> 110.0
                "trunk" -> 90.0
                else -> null
            }
        }

        val number = Regex("^[0-9]+(\\.[0-9]+)?").find(value)?.value?.toDoubleOrNull() ?: return null
        if (number <= 0) return null
        return when {
            "mph" in value -> number * 1.609344
            "knots" in value -> number * 1.852
            else -> number
        }
    }

    /** Effective driving speed in km/h for a way's tags. */
    fun speedKph(tags: Map<String, String>): Double {
        val highway = tags["highway"] ?: "road"
        val realism = REALISM[highway] ?: FALLBACK_REALISM
        val posted = parseMaxspeedKph(tags["maxspeed"])
            ?: parseMaxspeedKph(tags["maxspeed:forward"])
            ?: parseMaxspeedKph(tags["maxspeed:advisory"])

        var speed = (posted ?: DEFAULT_KPH[highway] ?: FALLBACK_KPH) * realism

        // Surface and access modifiers that make a nominal limit unreachable.
        when (tags["surface"]) {
            "unpaved", "gravel", "dirt", "ground", "sand", "grass", "mud" -> speed = minOf(speed, 25.0)
            "compacted", "fine_gravel" -> speed = minOf(speed, 35.0)
            "cobblestone", "sett", "unhewn_cobblestone" -> speed = minOf(speed, 25.0)
        }
        if (tags["service"] == "parking_aisle" || tags["service"] == "driveway") speed = minOf(speed, 12.0)
        if (tags["traffic_calming"] != null) speed *= 0.8

        return speed.coerceIn(5.0, MAX_PLAUSIBLE_KPH)
    }

    /** Seconds lost to a junction control on a node, before any turn penalty. */
    fun nodeDelaySeconds(tags: Map<String, String>): Double = when {
        tags["highway"] == "traffic_signals" -> 12.0
        tags["highway"] == "stop" -> 6.0
        tags["highway"] == "give_way" -> 3.0
        tags["highway"] == "mini_roundabout" -> 4.0
        tags["barrier"] == "gate" || tags["barrier"] == "lift_gate" -> 10.0
        tags["highway"] == "crossing" -> 1.5
        tags["railway"] == "level_crossing" -> 8.0
        else -> 0.0
    }
}
