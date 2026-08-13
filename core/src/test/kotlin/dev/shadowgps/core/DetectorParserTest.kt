package dev.shadowgps.core

import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.detect.DetectorParser
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.osm.parseOverpassResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectorParserTest {

    private fun node(tags: Map<String, String>) =
        OsmElement(type = "node", id = 1, lat = 35.99, lon = -78.89, tags = tags)

    @Test
    fun `tagged ALPR is recognised`() {
        val detector = DetectorParser.parse(
            node(
                mapOf(
                    "man_made" to "surveillance",
                    "surveillance:type" to "ALPR",
                    "surveillance" to "public",
                    "direction" to "145",
                    "manufacturer" to "Flock Safety",
                ),
            ),
        )

        assertNotNull(detector)
        assertEquals(DetectorKind.ALPR, detector!!.kind)
        assertEquals(145.0, detector.headingDegrees)
        assertTrue(detector.isKnownAlprVendor)
        assertEquals("node/1", detector.id)
    }

    @Test
    fun `vendor alone identifies a plate reader`() {
        // Plenty of real nodes carry only the base surveillance tag and a manufacturer.
        val detector = DetectorParser.parse(
            node(mapOf("man_made" to "surveillance", "operator" to "Flock Safety")),
        )

        assertEquals(DetectorKind.ALPR, detector?.kind)
    }

    @Test
    fun `alternative plate reader spellings are recognised`() {
        for (value in listOf("ANPR", "alpr", "license_plate", "licence plate", "number_plate")) {
            val detector = DetectorParser.parse(
                node(mapOf("man_made" to "surveillance", "surveillance:type" to value)),
            )
            assertEquals(DetectorKind.ALPR, detector?.kind, "expected $value to parse as an ALPR")
        }
    }

    @Test
    fun `speed and red light cameras are separated`() {
        assertEquals(
            DetectorKind.SPEED_CAMERA,
            DetectorParser.parse(node(mapOf("highway" to "speed_camera")))?.kind,
        )
        assertEquals(
            DetectorKind.SPEED_CAMERA,
            DetectorParser.parse(node(mapOf("enforcement" to "maxspeed")))?.kind,
        )
        assertEquals(
            DetectorKind.RED_LIGHT_CAMERA,
            DetectorParser.parse(node(mapOf("enforcement" to "traffic_signals")))?.kind,
        )
    }

    @Test
    fun `toll infrastructure counts as a plate reader by another name`() {
        assertEquals(
            DetectorKind.TOLL_GANTRY,
            DetectorParser.parse(node(mapOf("highway" to "toll_gantry")))?.kind,
        )
        assertEquals(
            DetectorKind.TOLL_GANTRY,
            DetectorParser.parse(node(mapOf("barrier" to "toll_booth")))?.kind,
        )
    }

    @Test
    fun `road-facing CCTV is kept and private cameras are dropped`() {
        assertEquals(
            DetectorKind.CCTV,
            DetectorParser.parse(
                node(mapOf("man_made" to "surveillance", "surveillance:zone" to "traffic")),
            )?.kind,
        )

        // A shop's own camera is mapped identically apart from the zone, and is not
        // something a driver can route around.
        assertNull(
            DetectorParser.parse(
                node(mapOf("man_made" to "surveillance", "surveillance" to "indoor", "surveillance:zone" to "shop")),
            ),
        )
        assertNull(DetectorParser.parse(node(mapOf("amenity" to "cafe"))))
    }

    @Test
    fun `a Flock unit on a traffic signal is still a plate reader`() {
        val detector = DetectorParser.parse(
            node(
                mapOf(
                    "man_made" to "surveillance",
                    "enforcement" to "traffic_signals",
                    "manufacturer" to "Flock Safety",
                ),
            ),
        )

        assertEquals(DetectorKind.ALPR, detector?.kind)
    }

    @Test
    fun `direction accepts degrees compass points and multi-values`() {
        assertEquals(145.0, DetectorParser.parseDirection("145"))
        assertEquals(145.5, DetectorParser.parseDirection("145.5"))
        assertEquals(22.5, DetectorParser.parseDirection("NNE"))
        assertEquals(270.0, DetectorParser.parseDirection("W"))
        assertEquals(90.0, DetectorParser.parseDirection("90;270"))
        assertEquals(10.0, DetectorParser.parseDirection("370"))
        assertNull(DetectorParser.parseDirection(null))
        assertNull(DetectorParser.parseDirection("sideways"))
    }

    @Test
    fun `explicit range overrides the default envelope`() {
        val detector = DetectorParser.parse(
            node(mapOf("man_made" to "surveillance", "surveillance:type" to "ALPR", "camera:range" to "150")),
        )

        assertEquals(150.0, detector?.rangeMeters)
    }

    @Test
    fun `duplicate elements collapse to one detector`() {
        val elements = listOf(
            node(mapOf("man_made" to "surveillance", "surveillance:type" to "ALPR")),
            node(mapOf("man_made" to "surveillance", "surveillance:type" to "ALPR")),
        )

        assertEquals(1, DetectorParser.parseAll(elements).size)
    }

    @Test
    fun `an Overpass payload parses end to end`() {
        val body = """
            {
              "version": 0.6,
              "generator": "Overpass API",
              "elements": [
                {
                  "type": "node", "id": 11259451234, "lat": 35.9940, "lon": -78.8986,
                  "tags": {
                    "man_made": "surveillance",
                    "surveillance": "public",
                    "surveillance:type": "ALPR",
                    "direction": "270",
                    "manufacturer": "Flock Safety"
                  }
                },
                { "type": "node", "id": 22, "lat": 35.99, "lon": -78.90, "tags": { "highway": "crossing" } }
              ]
            }
        """.trimIndent()

        val detectors = DetectorParser.parseAll(parseOverpassResponse(body).elements)

        assertEquals(1, detectors.size)
        assertEquals(DetectorKind.ALPR, detectors[0].kind)
        assertEquals(270.0, detectors[0].headingDegrees)
    }
}
