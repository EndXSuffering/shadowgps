package dev.shadowgps.core.detect

import dev.shadowgps.core.geo.LatLon
import kotlinx.serialization.Serializable

/**
 * The classes of roadside sensor the router knows how to avoid.
 *
 * The list is ordered loosely by how much a privacy-minded driver tends to care: an ALPR
 * records that *you specifically* passed a point at a time and keeps it for months, whereas
 * a traffic CCTV camera usually just watches congestion.
 */
enum class DetectorKind {
    /** Automated licence plate reader: Flock Safety, Motorola Vigilant, Genetec, Rekor, … */
    ALPR,

    /** Speed enforcement camera, fixed or average-speed. */
    SPEED_CAMERA,

    /** Red-light / traffic-signal enforcement camera. */
    RED_LIGHT_CAMERA,

    /** General public-space video surveillance pointed at a road. */
    CCTV,

    /** Toll gantry or booth — reads plates or transponders by design. */
    TOLL_GANTRY,
    ;

    val label: String
        get() = when (this) {
            ALPR -> "Licence plate reader"
            SPEED_CAMERA -> "Speed camera"
            RED_LIGHT_CAMERA -> "Red-light camera"
            CCTV -> "Traffic CCTV"
            TOLL_GANTRY -> "Toll reader"
        }

    val shortLabel: String
        get() = when (this) {
            ALPR -> "ALPR"
            SPEED_CAMERA -> "Speed"
            RED_LIGHT_CAMERA -> "Red light"
            CCTV -> "CCTV"
            TOLL_GANTRY -> "Toll"
        }
}

/**
 * Default sensing envelope per kind.
 *
 * [rangeMeters] is the distance at which the device can still be expected to resolve a
 * plate or a vehicle, and [fovDegrees] the width of the cone it covers when its facing
 * direction is known. These are deliberately conservative round numbers rather than
 * vendor specs: OSM records where a camera is, not what lens it has, and over-estimating
 * range slightly is the safer error for a tool whose job is avoidance.
 */
data class DetectorEnvelope(val rangeMeters: Double, val fovDegrees: Double) {
    companion object {
        fun forKind(kind: DetectorKind): DetectorEnvelope = when (kind) {
            // Fixed ALPRs are typically set to read one or two lanes of traffic passing a
            // pole, which is a short, quite narrow cone.
            DetectorKind.ALPR -> DetectorEnvelope(rangeMeters = 70.0, fovDegrees = 110.0)
            // Speed cameras acquire further out to measure a speed over distance.
            DetectorKind.SPEED_CAMERA -> DetectorEnvelope(rangeMeters = 120.0, fovDegrees = 70.0)
            DetectorKind.RED_LIGHT_CAMERA -> DetectorEnvelope(rangeMeters = 60.0, fovDegrees = 100.0)
            DetectorKind.CCTV -> DetectorEnvelope(rangeMeters = 45.0, fovDegrees = 100.0)
            // A gantry spans the carriageway, so anything passing under it is read.
            DetectorKind.TOLL_GANTRY -> DetectorEnvelope(rangeMeters = 60.0, fovDegrees = 360.0)
        }
    }
}

/**
 * One mapped surveillance device.
 *
 * @property id stable OSM-style identity, e.g. `node/12345678`
 * @property headingDegrees the compass direction the device *faces*, when mapped. Null means
 *   unknown, which the exposure model treats as covering every approach.
 * @property rangeMeters distance at which the device is assumed to still capture a vehicle
 * @property fovDegrees full width of its coverage cone; 360 means omnidirectional
 * @property brand vendor, when tagged — this is how Flock hardware is identified
 */
@Serializable
data class Detector(
    val id: String,
    val kind: DetectorKind,
    val position: LatLon,
    val headingDegrees: Double? = null,
    val rangeMeters: Double,
    val fovDegrees: Double,
    val name: String? = null,
    val operator: String? = null,
    val brand: String? = null,
    val mount: String? = null,
    /** Where the record came from, e.g. `osm` or `user`. */
    val source: String = "osm",
) {
    /** True when the vendor is recognisably a plate-reader manufacturer. */
    val isKnownAlprVendor: Boolean
        get() = brand?.let { DetectorParser.ALPR_VENDOR_PATTERN.containsMatchIn(it) } == true

    /** Short human description used in route summaries and voice prompts. */
    fun describe(): String = when {
        brand != null -> "${brand.trim()} ${kind.shortLabel.lowercase()}"
        operator != null -> "${kind.label} (${operator.trim()})"
        else -> kind.label
    }
}
