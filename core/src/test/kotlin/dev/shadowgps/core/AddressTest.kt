package dev.shadowgps.core

import dev.shadowgps.core.search.AddressLabel
import dev.shadowgps.core.search.AddressParts
import dev.shadowgps.core.search.AddressQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `an unstructured result still gets a usable title`() {
        val parts = AddressParts(displayName = "Snowdonia National Park, Gwynedd, Wales")

        assertEquals("Snowdonia National Park", AddressLabel.title(parts))
        assertNull(AddressLabel.locality(parts))
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
}
