package dev.shadowgps.core.routing

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.angularDifference
import dev.shadowgps.core.geo.bearingDegrees
import dev.shadowgps.core.geo.normalizeBearing
import dev.shadowgps.core.graph.RoadGraph
import kotlinx.serialization.Serializable

/**
 * What the driver wants to stay away from, and how badly.
 *
 * [kindWeights] are relative: an ALPR at weight 1.0 costs the router a full detour budget,
 * a traffic camera at 0.25 costs a quarter of one. Kinds absent from [enabledKinds] are
 * ignored completely rather than down-weighted, so turning a category off really does mean
 * the router stops caring about it.
 */
@Serializable
data class AvoidanceSettings(
    val enabledKinds: Set<DetectorKind> = setOf(DetectorKind.ALPR),
    val kindWeights: Map<DetectorKind, Double> = DEFAULT_WEIGHTS,
    /**
     * How to treat a camera whose facing direction nobody has mapped. Assuming it covers
     * every approach is the cautious reading, and most OSM surveillance nodes have no
     * `direction` tag at all.
     */
    val unknownHeadingIsOmnidirectional: Boolean = true,
) {
    fun weightFor(kind: DetectorKind): Double =
        if (kind !in enabledKinds) 0.0 else kindWeights[kind] ?: 1.0

    fun isEnabled(kind: DetectorKind): Boolean = kind in enabledKinds

    companion object {
        val DEFAULT_WEIGHTS: Map<DetectorKind, Double> = mapOf(
            // A plate reader logs an identifiable vehicle at a place and time, and the
            // record is retained and searchable. That is the thing this app exists for.
            DetectorKind.ALPR to 1.0,
            // Tolling infrastructure reads plates too, but its purpose is billing and
            // drivers usually cannot avoid the road it sits on anyway.
            DetectorKind.TOLL_GANTRY to 0.6,
            // Enforcement cameras only record on a violation.
            DetectorKind.SPEED_CAMERA to 0.35,
            DetectorKind.RED_LIGHT_CAMERA to 0.35,
            // Generic traffic CCTV rarely identifies a vehicle and is everywhere.
            DetectorKind.CCTV to 0.2,
        )

        /** Sensible starting point: dodge plate readers, ignore everything else. */
        val DEFAULT = AvoidanceSettings()

        /** Everything the app knows how to see. */
        val EVERYTHING = AvoidanceSettings(enabledKinds = DetectorKind.entries.toSet())
    }
}

/**
 * How exposed one stretch of road is to one device.
 *
 * The result is a number in `[0, 1]` per detector: 1.0 means driving past it is a clean
 * capture, 0.0 means it cannot see the road at all. Three independent factors multiply
 * together — how close the road passes, whether it falls inside the camera's cone, and
 * which way the vehicle is travelling through it.
 */
class ExposureModel(val settings: AvoidanceSettings = AvoidanceSettings.DEFAULT) {

    /**
     * Coverage of a road point by [detector].
     *
     * @param distanceMeters shortest distance from the detector to the road
     * @param bearingToRoad compass bearing from the detector to that closest point
     * @param travelHeading direction a vehicle is travelling as it passes
     */
    fun coverage(
        detector: Detector,
        distanceMeters: Double,
        bearingToRoad: Double,
        travelHeading: Double,
    ): Double {
        if (distanceMeters > detector.rangeMeters) return 0.0

        val proximity = 1.0 - smoothstep(detector.rangeMeters * NEAR_FIELD_FRACTION, detector.rangeMeters, distanceMeters)
        if (proximity <= 0.0) return 0.0

        return proximity * coneFactor(detector, distanceMeters, bearingToRoad) * approachFactor(detector, travelHeading)
    }

    /** Total penalty weight of passing [detector], already scaled by its kind. */
    fun weight(
        detector: Detector,
        distanceMeters: Double,
        bearingToRoad: Double,
        travelHeading: Double,
    ): Double {
        val kindWeight = settings.weightFor(detector.kind)
        if (kindWeight <= 0.0) return 0.0
        return kindWeight * coverage(detector, distanceMeters, bearingToRoad, travelHeading)
    }

    /**
     * Whether the road falls inside the camera's cone.
     *
     * Directly under a camera the cone stops meaning anything — a pole-mounted unit sees
     * the lane beneath it regardless of where it points — so the gate is relaxed to 1.0
     * inside [ALWAYS_SEEN_METERS].
     */
    private fun coneFactor(detector: Detector, distanceMeters: Double, bearingToRoad: Double): Double {
        if (distanceMeters <= ALWAYS_SEEN_METERS) return 1.0
        if (detector.fovDegrees >= 360.0) return 1.0

        val heading = detector.headingDegrees
            ?: return if (settings.unknownHeadingIsOmnidirectional) 1.0 else UNKNOWN_HEADING_FACTOR

        val offset = angularDifference(bearingToRoad, normalizeBearing(heading))
        val half = detector.fovDegrees / 2.0
        return when {
            offset <= half -> 1.0
            offset <= half + CONE_FALLOFF_DEGREES -> 1.0 - (offset - half) / CONE_FALLOFF_DEGREES
            else -> 0.0
        }
    }

    /**
     * Adjusts for which way the vehicle passes the camera.
     *
     * A camera pointing down the road at oncoming traffic gets a clean front-plate read;
     * a vehicle travelling the same way the camera faces is leaving, and is captured from
     * behind, which is a less reliable read on plates mounted only at the front.
     */
    private fun approachFactor(detector: Detector, travelHeading: Double): Double {
        val heading = detector.headingDegrees ?: return 1.0
        val oncoming = normalizeBearing(heading + 180.0)
        return if (angularDifference(travelHeading, oncoming) <= 90.0) 1.0 else DEPARTING_FACTOR
    }

    private fun smoothstep(from: Double, to: Double, x: Double): Double {
        if (to <= from) return if (x >= to) 1.0 else 0.0
        val t = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private companion object {
        /** Inside this fraction of its range, a device is assumed to read perfectly. */
        const val NEAR_FIELD_FRACTION = 0.45

        /** Degrees beyond the nominal cone edge over which coverage fades to nothing. */
        const val CONE_FALLOFF_DEGREES = 25.0

        /** Below this distance the facing direction stops mattering. */
        const val ALWAYS_SEEN_METERS = 12.0

        /** Applied when a camera's direction is unmapped and the cautious reading is off. */
        const val UNKNOWN_HEADING_FACTOR = 0.6

        /** Applied when the vehicle is driving away from the camera rather than into it. */
        const val DEPARTING_FACTOR = 0.8
    }
}

/** One detector's effect on one directed edge. */
data class DetectorHit(
    val detectorIndex: Int,
    /** Kind-scaled coverage in `[0, 1]`. */
    val weight: Double,
    /** Closest approach between the road and the device. */
    val distanceMeters: Double,
    /** Where along the edge that closest approach happens. */
    val alongMeters: Double,
)

/**
 * Precomputed surveillance exposure for every edge of a graph.
 *
 * Built once per trip, then read millions of times by the router's inner loop, so the
 * per-edge total lives in a flat array. The per-detector breakdown is kept only for the
 * edges that actually have one, since most roads see no cameras at all.
 */
class ExposureIndex(
    val graph: RoadGraph,
    val detectors: List<Detector>,
    val model: ExposureModel,
) {
    /** Total penalty weight of traversing each directed edge, indexed by edge index. */
    val edgeWeight: DoubleArray = DoubleArray(graph.edgeCount)

    private val hitsByEdge = HashMap<Int, MutableList<DetectorHit>>()

    init {
        // Iterating detectors rather than edges is the cheap direction: a city has a few
        // hundred cameras but hundreds of thousands of edges, and each camera only reaches
        // the handful of roads inside its range.
        for ((detectorIndex, detector) in detectors.withIndex()) {
            if (!settingsAllow(detector)) continue

            val searchBox = BoundingBox.around(detector.position, detector.rangeMeters)
            for (edgeIndex in graph.edgesIntersecting(searchBox)) {
                val edge = graph.edges[edgeIndex]
                val projection = edge.project(detector.position)
                if (projection.distanceMeters > detector.rangeMeters) continue

                val bearingToRoad = bearingDegrees(detector.position, projection.point)
                val weight = model.weight(
                    detector = detector,
                    distanceMeters = projection.distanceMeters,
                    bearingToRoad = bearingToRoad,
                    travelHeading = projection.headingDegrees,
                )
                if (weight <= 0.0) continue

                edgeWeight[edgeIndex] += weight
                hitsByEdge.getOrPut(edgeIndex) { ArrayList(2) }.add(
                    DetectorHit(
                        detectorIndex = detectorIndex,
                        weight = weight,
                        distanceMeters = projection.distanceMeters,
                        alongMeters = projection.alongMeters,
                    ),
                )
            }
        }
    }

    private fun settingsAllow(detector: Detector): Boolean =
        model.settings.weightFor(detector.kind) > 0.0

    /** Devices covering [edgeIndex], nearest first. Empty for most edges. */
    fun hits(edgeIndex: Int): List<DetectorHit> = hitsByEdge[edgeIndex] ?: emptyList()

    fun detectorAt(hit: DetectorHit): Detector = detectors[hit.detectorIndex]

    /** Edges with any exposure at all — useful for diagnostics and tests. */
    val exposedEdgeCount: Int get() = hitsByEdge.size
}
