package dev.shadowgps.core.osm

import dev.shadowgps.core.geo.BoundingBox

/**
 * Overpass QL query builders.
 *
 * Two queries carry the whole app: one pulls the drivable road network for a bounding box,
 * the other pulls every surveillance device in it.
 */
object OverpassQueries {

    /** Highway values that a car can actually drive on. */
    val DRIVABLE_HIGHWAY_VALUES: List<String> = listOf(
        "motorway", "motorway_link",
        "trunk", "trunk_link",
        "primary", "primary_link",
        "secondary", "secondary_link",
        "tertiary", "tertiary_link",
        "unclassified", "residential",
        "living_street", "service", "road",
    )

    private val DRIVABLE_REGEX = "^(${DRIVABLE_HIGHWAY_VALUES.joinToString("|")})$"

    /**
     * The road network inside [box].
     *
     * `out body` gives each way's node id list, `>` recurses down to those nodes and
     * `out skel qt` emits their coordinates. Junction nodes carry tags too (traffic
     * signals, stop signs), which the graph builder turns into crossing penalties.
     */
    fun roadNetwork(box: BoundingBox, timeoutSeconds: Int = 90): String {
        val bbox = box.toOverpassString()
        return """
            [out:json][timeout:$timeoutSeconds];
            (
              way["highway"~"$DRIVABLE_REGEX"]["area"!~"yes"]($bbox);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    /**
     * Every surveillance device inside [box].
     *
     * Casts a deliberately wide net — [dev.shadowgps.core.detect.DetectorParser] decides
     * what each element actually is and drops anything that is not roadside. Relations are
     * included because speed and red-light enforcement is often mapped as an `enforcement`
     * relation whose device member is the camera itself.
     */
    fun surveillance(box: BoundingBox, timeoutSeconds: Int = 60): String {
        val bbox = box.toOverpassString()
        return """
            [out:json][timeout:$timeoutSeconds];
            (
              node["man_made"="surveillance"]($bbox);
              way["man_made"="surveillance"]($bbox);
              node["highway"="speed_camera"]($bbox);
              node["enforcement"]($bbox);
              relation["type"="enforcement"]($bbox);
              node["highway"="toll_gantry"]($bbox);
              node["barrier"="toll_booth"]($bbox);
              way["barrier"="toll_booth"]($bbox);
            );
            out center tags;
        """.trimIndent()
    }
}
