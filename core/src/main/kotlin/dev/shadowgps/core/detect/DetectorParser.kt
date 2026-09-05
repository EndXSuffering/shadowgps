package dev.shadowgps.core.detect

import dev.shadowgps.core.osm.OsmElement

/**
 * Turns raw OSM elements into [Detector]s.
 *
 * The tagging this reads is the scheme used by the OSM wiki's `man_made=surveillance`
 * page and by the community ALPR mapping efforts that feed it (DeFlock and friends), where
 * a plate reader is a node tagged:
 *
 * ```
 * man_made=surveillance
 * surveillance:type=ALPR
 * surveillance=public
 * direction=145
 * manufacturer=Flock Safety
 * ```
 *
 * Real data is messier than that, so every rule below has fallbacks, and anything that
 * cannot be confidently placed as roadside equipment is dropped rather than guessed at.
 *
 * Worth stating plainly, because it looks like a missing integration: **DeFlock is not a
 * separate source of cameras.** It does not run a camera database of its own — every marker
 * on its map is an OpenStreetMap node with these tags, fetched through Overpass, and
 * submissions made there go into OpenStreetMap rather than anywhere private. Reading the
 * tags above is reading DeFlock's data, from the same place DeFlock reads it, and it comes
 * with something their map cannot offer: it keeps working with no signal, from a saved
 * region, without asking anybody where the driver is.
 */
object DetectorParser {

    /** Vendors whose hardware is a plate reader regardless of how the node is otherwise tagged. */
    val ALPR_VENDOR_PATTERN = Regex(
        "flock|vigilant|motorola solutions|genetec|rekor|elsag|neology|perceptics|jenoptik|axis alpr|leonardo",
        RegexOption.IGNORE_CASE,
    )

    private val ALPR_TYPE_PATTERN = Regex("alpr|anpr|licence[_ ]?plate|license[_ ]?plate|number[_ ]?plate", RegexOption.IGNORE_CASE)

    /** `surveillance:zone` / `surveillance` values that mean "aimed at a public road". */
    private val ROADSIDE_ZONES = setOf("traffic", "town", "public", "street", "outdoor", "area", "parking")

    private val COMPASS_POINTS = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5,
        "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
        "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
        "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )

    /**
     * Parses one element, or returns null if it is not a road-facing detector.
     *
     * Elements without a position (relations returned without `out center`) are skipped;
     * the enforcement relation's device member is normally present in the same response as
     * its own node, so nothing is lost.
     */
    fun parse(element: OsmElement): Detector? {
        val position = element.position() ?: return null
        val tags = element.tags
        val kind = classify(tags) ?: return null

        val envelope = DetectorEnvelope.forKind(kind)
        val explicitRange = tags["camera:range"]?.let(::parseMeters)
            ?: tags["surveillance:range"]?.let(::parseMeters)
            ?: tags["range"]?.let(::parseMeters)

        val heading = parseDirection(
            element.tag("direction", "camera:direction", "surveillance:direction"),
        )

        return Detector(
            id = element.ref,
            kind = kind,
            position = position,
            headingDegrees = heading,
            rangeMeters = explicitRange?.coerceIn(10.0, 400.0) ?: envelope.rangeMeters,
            fovDegrees = envelope.fovDegrees,
            name = element.tag("name", "ref"),
            operator = element.tag("operator", "surveillance:operator"),
            brand = element.tag("manufacturer", "brand", "camera:manufacturer"),
            mount = element.tag("camera:mount", "support"),
            source = "osm",
        )
    }

    fun parseAll(elements: Iterable<OsmElement>): List<Detector> {
        val byId = LinkedHashMap<String, Detector>()
        for (element in elements) {
            val detector = parse(element) ?: continue
            byId[detector.id] = detector
        }
        return byId.values.toList()
    }

    /**
     * Decides which [DetectorKind] a tag set describes, most specific signal first.
     *
     * Order matters: a Flock unit bolted to a traffic signal is an ALPR, not a red-light
     * camera, and a plate reader mapped only as `man_made=surveillance` with a vendor tag
     * still has to come out as [DetectorKind.ALPR].
     */
    fun classify(tags: Map<String, String>): DetectorKind? {
        fun tag(vararg keys: String): String? {
            for (key in keys) tags[key]?.takeIf { it.isNotBlank() }?.let { return it }
            return null
        }

        val surveillanceType = tag("surveillance:type", "camera:type")
        val vendor = tag("manufacturer", "brand", "camera:manufacturer", "operator")
        val enforcement = tag("enforcement")

        // 1. Anything that says "plate reader", however it says it.
        if (surveillanceType != null && ALPR_TYPE_PATTERN.containsMatchIn(surveillanceType)) return DetectorKind.ALPR
        if (tags["surveillance:zone"]?.let { ALPR_TYPE_PATTERN.containsMatchIn(it) } == true) return DetectorKind.ALPR
        if (vendor != null && ALPR_VENDOR_PATTERN.containsMatchIn(vendor)) return DetectorKind.ALPR
        if (enforcement != null && ALPR_TYPE_PATTERN.containsMatchIn(enforcement)) return DetectorKind.ALPR

        // 2. Traffic enforcement, tagged either on the device or via an enforcement relation.
        if (tags["highway"] == "speed_camera") return DetectorKind.SPEED_CAMERA
        when (enforcement) {
            "maxspeed", "average_speed", "speed" -> return DetectorKind.SPEED_CAMERA
            "traffic_signals" -> return DetectorKind.RED_LIGHT_CAMERA
            "toll" -> return DetectorKind.TOLL_GANTRY
        }
        if (tags["type"] == "enforcement") {
            // An enforcement relation without a recognised subtype is still enforcement.
            return DetectorKind.SPEED_CAMERA
        }

        // 3. Tolling infrastructure reads every plate that passes under it.
        if (tags["highway"] == "toll_gantry" || tags["barrier"] == "toll_booth") return DetectorKind.TOLL_GANTRY

        // 4. Plain video surveillance, but only when it watches a public road. Doorbell
        //    cameras and shop interiors are mapped with the same top-level tag and are not
        //    something a driver can route around.
        if (tags["man_made"] == "surveillance") {
            val zone = tag("surveillance:zone", "surveillance")
            val watchesRoad = zone != null && zone.split(";").any { it.trim().lowercase() in ROADSIDE_ZONES }
            val indoor = tags["surveillance"] == "indoor" || tags["indoor"] == "yes"
            if (watchesRoad && !indoor) return DetectorKind.CCTV
            return null
        }

        return null
    }

    /**
     * Parses a `direction`-style tag into degrees clockwise from north.
     *
     * Accepts a bare number (`145`, `145.5`), a compass point (`NNE`), and the common
     * "two directions" form (`145;325`) where the first value is taken.
     */
    fun parseDirection(raw: String?): Double? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val first = value.split(";", ",").first().trim()

        first.toDoubleOrNull()?.let { degrees ->
            val normalized = degrees % 360.0
            return if (normalized < 0) normalized + 360.0 else normalized
        }

        return COMPASS_POINTS[first.uppercase()]
    }

    /** Parses a distance tag that may carry a unit, returning metres. */
    private fun parseMeters(raw: String): Double? {
        val value = raw.trim().lowercase()
        val number = Regex("[0-9]+(\\.[0-9]+)?").find(value)?.value?.toDoubleOrNull() ?: return null
        return when {
            value.endsWith("km") -> number * 1000.0
            value.endsWith("mi") -> number * 1609.344
            value.endsWith("ft") || value.endsWith("'") -> number * 0.3048
            else -> number
        }
    }
}
