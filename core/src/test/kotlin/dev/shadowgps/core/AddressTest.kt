package dev.shadowgps.core

import dev.shadowgps.core.search.AddressLabel
import dev.shadowgps.core.search.AddressParts
import dev.shadowgps.core.search.AddressQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How a search result reads, and what a geocoder can be asked.
 *
 * These pin the two complaints the old search produced: results titled after a bare house
 * number, and a business address that returned nothing at all because it carried a suite.
 */
class AddressTest {

    // ------------------------------------------------------------------ titles

    @Test
    fun `a plain address is titled after its street, not its house number`() {
        // Nominatim's own name field for a house is the house number, so taking it at face
        // value produced a list of results reading "500", "512", "530".
        val parts = AddressParts(
            name = "500",
            houseNumber = "500",
            road = "Elm Street",
            settlement = "Durham",
            state = "North Carolina",
            postcode = "27701",
            displayName = "500, Elm Street, Durham, North Carolina, 27701, United States",
        )

        assertEquals("500 Elm Street", AddressLabel.title(parts))
    }

    @Test
    fun `a business keeps its own name`() {
        val parts = AddressParts(
            name = "Acme Dental",
            houseNumber = "500",
            road = "Elm Street",
            settlement = "Durham",
            postcode = "27701",
        )

        assertEquals("Acme Dental", AddressLabel.title(parts))
        // The street moves to the line below, which is what tells two branches apart.
        assertEquals("500 Elm Street, Durham, 27701", AddressLabel.locality(parts))
    }

    @Test
    fun `an address does not repeat its street on both lines`() {
        val parts = AddressParts(
            name = "500",
            houseNumber = "500",
            road = "Elm Street",
            settlement = "Durham",
            state = "North Carolina",
            postcode = "27701",
        )

        assertEquals("500 Elm Street", AddressLabel.title(parts))
        assertEquals("Durham, North Carolina, 27701", AddressLabel.locality(parts))
    }

    @Test
    fun `the locality line stops at the postcode`() {
        // display_name carries on through county, country and occasionally a continent.
        val parts = AddressParts(
            name = "Acme Dental",
            houseNumber = "500",
            road = "Elm Street",
            settlement = "Durham",
            state = "North Carolina",
            postcode = "27701",
            displayName = "Acme Dental, 500, Elm Street, Old West Durham, Durham, " +
                "Durham County, North Carolina, 27701, United States",
        )

        val locality = AddressLabel.locality(parts)!!
        assertEquals("500 Elm Street, Durham, North Carolina, 27701", locality)
    }

    @Test
    fun `a house number with no street never stands alone as a title`() {
        // The failure the whole file exists to prevent, reached by a different road: with no
        // street to attach it to, a bare number is not an address and must not be a title.
        val parts = AddressParts(
            name = "500",
            houseNumber = "500",
            displayName = "500, Riverside Estate, Durham",
        )

        assertEquals("500 Riverside Estate", AddressLabel.title(parts))
    }

    @Test
    fun `an unstructured result still gets a usable title`() {
        val parts = AddressParts(displayName = "Snowdonia National Park, Gwynedd, Wales")

        assertEquals("Snowdonia National Park", AddressLabel.title(parts))
        assertNull(AddressLabel.locality(parts))
    }

    @Test
    fun `a house named after its street keeps its number`() {
        // Nominatim calls a house on a road by the road's name, so accepting that as the
        // result's own name titled it "Elm Street" and pushed the number to the line below.
        // Every house on the street then looked identical in the list.
        val parts = AddressParts(
            name = "Elm Street",
            houseNumber = "500",
            road = "Elm Street",
            settlement = "Durham",
            postcode = "27701",
        )

        assertEquals("500 Elm Street", AddressLabel.title(parts))
        assertEquals("Durham, 27701", AddressLabel.locality(parts))
    }

    @Test
    fun `the number is recovered when the structured field is missing`() {
        // The house_number field is not always filled even when display_name leads with it.
        val parts = AddressParts(
            road = "Guess Road",
            settlement = "Durham",
            postcode = "27705",
            displayName = "3806, Guess Road, Durham, Durham County, North Carolina, 27705",
        )

        assertEquals("3806 Guess Road", AddressLabel.title(parts))
    }

    @Test
    fun `a leading word is not mistaken for a house number`() {
        // The recovery only fires when the next part really is this result's street.
        val parts = AddressParts(
            road = "Elm Street",
            settlement = "Durham",
            displayName = "Riverside Business Park, Oak Lane, Durham, North Carolina",
        )

        assertEquals("Elm Street", AddressLabel.title(parts))
    }

    @Test
    fun `a street with no number is titled after the street`() {
        val parts = AddressParts(
            name = "Elm Street",
            road = "Elm Street",
            settlement = "Durham",
            displayName = "Elm Street, Durham, North Carolina",
        )

        assertEquals("Elm Street", AddressLabel.title(parts))
        assertEquals("Durham", AddressLabel.locality(parts))
    }

    @Test
    fun `a business named after its street is still a business`() {
        val parts = AddressParts(
            name = "Elm Street Diner",
            houseNumber = "500",
            road = "Elm Street",
            settlement = "Durham",
        )

        assertEquals("Elm Street Diner", AddressLabel.title(parts))
        assertEquals("500 Elm Street, Durham", AddressLabel.locality(parts))
    }

    @Test
    fun `a numbered name that is not the house number is kept`() {
        // Businesses really are called things like "7-Eleven" and "24 Hour Fitness".
        val parts = AddressParts(name = "7-Eleven", houseNumber = "500", road = "Elm Street")

        assertEquals("7-Eleven", AddressLabel.title(parts))
    }

    // ------------------------------------------------------------------ units

    @Test
    fun `a suite is lifted out of the query`() {
        val split = AddressQuery.split("500 Elm Street Suite 200, Durham NC")

        assertEquals("500 Elm Street, Durham NC", split.searchable)
        assertEquals("Suite 200", split.unit)
    }

    @Test
    fun `the common abbreviations are all understood`() {
        assertEquals("Ste 4", AddressQuery.split("12 High St Ste 4").unit)
        assertEquals("Unit 7", AddressQuery.split("12 High St, Unit 7").unit)
        assertEquals("Apt 3B", AddressQuery.split("12 High St Apt 3B").unit)
        assertEquals("Floor 2", AddressQuery.split("12 High St Floor 2").unit)
        assertEquals("Unit 210", AddressQuery.split("12 High St #210").unit)
    }

    @Test
    fun `the address survives the unit being removed`() {
        val split = AddressQuery.split("1600 Pennsylvania Ave NW Suite 100 Washington DC")

        assertEquals("1600 Pennsylvania Ave NW Washington DC", split.searchable)
    }

    @Test
    fun `a state abbreviation is not a floor`() {
        // "FL 33101" is Florida, not the 33101st floor — which is why the keyword list
        // leaves "fl" out entirely and every match has to carry a digit of its own.
        val split = AddressQuery.split("100 Biscayne Blvd, Miami, FL 33101")

        assertEquals("100 Biscayne Blvd, Miami, FL 33101", split.searchable)
        assertNull(split.unit)
    }

    @Test
    fun `a saint is not a suite`() {
        val split = AddressQuery.split("Ste Anne Church, Detroit")

        assertEquals("Ste Anne Church, Detroit", split.searchable)
        assertNull(split.unit)
    }

    @Test
    fun `a house number is not mistaken for a unit`() {
        val split = AddressQuery.split("221B Baker Street, London")

        assertEquals("221B Baker Street, London", split.searchable)
        assertNull(split.unit)
    }

    @Test
    fun `a query that is only a unit is left alone`() {
        // Nothing would be left to search for, and no results at all is worse than bad ones.
        val split = AddressQuery.split("Suite 200")

        assertEquals("Suite 200", split.searchable)
        assertNull(split.unit)
    }

    @Test
    fun `an ordinary query is untouched`() {
        val split = AddressQuery.split("coffee near Duke University")

        assertEquals("coffee near Duke University", split.searchable)
        assertNull(split.unit)
    }

    // -------------------------------------------------------------- typos

    @Test
    fun `a missing space after the house number is put back`() {
        // Reported: the space after the number was missed and the address simply never
        // appeared. Every geocoder reads "8227TX-151" as one nonsense token and finds
        // nothing, so a typo is indistinguishable from an address that does not exist.
        assertEquals(
            "8227 TX-151, San Antonio, TX 78245",
            AddressQuery.split("8227TX-151, San Antonio, TX 78245").searchable,
        )
        assertEquals("500 Elm Street", AddressQuery.split("500Elm Street").searchable)
    }

    @Test
    fun `a lettered house number survives intact`() {
        // "221B" is a house number, not a typo. One trailing letter is a suffix; two or
        // more is a street that lost its space.
        assertEquals("221B Baker Street, London", AddressQuery.split("221B Baker Street, London").searchable)
        assertEquals("100A Main St", AddressQuery.split("100A Main St").searchable)
    }

    @Test
    fun `only the leading number is repaired`() {
        // A number run against a word later in the line is somebody's actual street name or
        // postcode, and none of this app's business.
        assertEquals(
            "500 Elm Street, Durham NC27701",
            AddressQuery.restoreMissingSpace("500 Elm Street, Durham NC27701"),
        )
    }

    @Test
    fun `a missing space and a suite together still work`() {
        val split = AddressQuery.split("8227TX-151 Ste 102, San Antonio, TX 78245")

        assertEquals("8227 TX-151, San Antonio, TX 78245", split.searchable)
        assertEquals("Ste 102", split.unit)
    }

    @Test
    fun `a suite run into its number is understood`() {
        assertEquals("Ste 102", AddressQuery.split("8227 TX-151 Ste102, San Antonio").unit)
    }

    // ------------------------------------------------------- structured lookup

    @Test
    fun `a route-numbered address is broken into fields`() {
        // The address that started this: free-text search reads the whole line as the
        // highway and returns stretches of road, miles apart and indistinguishable.
        val split = AddressQuery.split("8227 TX-151 Ste 102, San Antonio, TX 78245")
        assertEquals("8227 TX-151, San Antonio, TX 78245", split.searchable)
        assertEquals("Ste 102", split.unit)

        val fields = AddressQuery.structure(split.searchable)!!
        assertEquals("8227 TX-151", fields.street)
        assertEquals("San Antonio", fields.city)
        assertEquals("TX", fields.state)
        assertEquals("78245", fields.postalCode)
    }

    @Test
    fun `a town with no state still divides up`() {
        val fields = AddressQuery.structure("500 Elm Street, Durham")!!

        assertEquals("500 Elm Street", fields.street)
        assertEquals("Durham", fields.city)
        assertNull(fields.state)
        assertNull(fields.postalCode)
    }

    @Test
    fun `a zip plus four is kept whole`() {
        val fields = AddressQuery.structure("1600 Pennsylvania Ave NW, Washington, DC 20500-0003")!!

        assertEquals("Washington", fields.city)
        assertEquals("DC", fields.state)
        assertEquals("20500-0003", fields.postalCode)
    }

    @Test
    fun `nothing to divide means no structured query`() {
        // Better one free-text search than a structured one built on a guess.
        assertNull(AddressQuery.structure("coffee near Duke University"))
        assertNull(AddressQuery.structure("San Antonio"))
    }

    @Test
    fun `a leading house number is what names a building`() {
        assertTrue(AddressQuery.namesABuilding("8227 TX-151, San Antonio, TX 78245"))
        assertTrue(AddressQuery.namesABuilding("221B Baker Street, London"))
        assertTrue(AddressQuery.namesABuilding("1600 Pennsylvania Ave NW"))
    }

    @Test
    fun `a numbered road is not a building`() {
        // "TX-151" has a digit in it and is a road. Calling that a building would have the
        // app apologising for a missing house number on every route-numbered search.
        assertFalse(AddressQuery.namesABuilding("TX-151, San Antonio, TX"))
        assertFalse(AddressQuery.namesABuilding("Highway 151, San Antonio"))
        assertFalse(AddressQuery.namesABuilding("Duke University, Durham"))
        assertFalse(AddressQuery.namesABuilding("78245"))
    }

    @Test
    fun `the typed postcode is found for ranking`() {
        assertEquals("78245", AddressQuery.postcodeIn("8227 TX-151, San Antonio, TX 78245"))
        assertEquals("27701", AddressQuery.postcodeIn("500 Elm Street, Durham, NC 27701"))
        assertNull(AddressQuery.postcodeIn("500 Elm Street, Durham, NC"))
    }

    // ------------------------------------------------------------------ casing

    @Test
    fun `a shouted address is made readable`() {
        // Authoritative address data comes back in capitals, which is correct and unreadable.
        assertEquals(
            "1600 Pennsylvania Ave NW",
            AddressLabel.readableCase("1600 PENNSYLVANIA AVE NW"),
        )
        assertEquals("San Antonio", AddressLabel.readableCase("SAN ANTONIO"))
    }

    @Test
    fun `a route number keeps its capitals`() {
        // "Tx-151" is not a road anybody has heard of.
        assertEquals("8227 TX-151", AddressLabel.readableCase("8227 TX-151"))
        assertEquals("100 US-90 W", AddressLabel.readableCase("100 US-90 W"))
    }

    @Test
    fun `compass suffixes are left alone`() {
        assertEquals("Elm Street SE", AddressLabel.readableCase("ELM STREET SE"))
        assertEquals("N Main Street", AddressLabel.readableCase("N MAIN STREET"))
    }

    // ------------------------------------------------------------- distinguishing

    @Test
    fun `two stretches of the same road are told apart by district`() {
        // Both are TX-151 in San Antonio 78245. Without the neighbourhood the two rows read
        // identically and there is nothing to choose between them but tapping one.
        val west = AddressParts(
            name = "Northwest Loop",
            road = "Northwest Loop",
            district = "Westwood Village",
            settlement = "San Antonio",
            state = "Texas",
            postcode = "78245",
        )
        val east = west.copy(district = "Valley Hi")

        assertEquals("Westwood Village, San Antonio, Texas, 78245", AddressLabel.locality(west))
        assertEquals("Valley Hi, San Antonio, Texas, 78245", AddressLabel.locality(east))
    }

    @Test
    fun `a district that merely repeats the city is left out`() {
        val parts = AddressParts(
            name = "Acme Dental",
            houseNumber = "500",
            road = "Elm Street",
            district = "Durham",
            settlement = "Durham",
        )

        assertEquals("500 Elm Street, Durham", AddressLabel.locality(parts))
    }
}
