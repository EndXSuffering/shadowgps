package dev.shadowgps.core.search

/**
 * The pieces of an address, as a geocoder hands them over.
 *
 * Deliberately plain: this lives in core so the labelling rules can be tested without a
 * geocoder, a network or an Android device.
 */
data class AddressParts(
    /** The geocoder's own name for the result. Often the house number on a plain address. */
    val name: String? = null,
    val houseNumber: String? = null,
    val road: String? = null,
    /** City, town, village or suburb — whichever is the most specific one available. */
    val settlement: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    /** The whole thing on one line, as a fallback when nothing is structured. */
    val displayName: String = "",
)

/**
 * Turns an address into the two lines a search result actually needs.
 *
 * The rule this exists to enforce: **a result is titled after something a person would
 * recognise**. Nominatim's `name` field is the house number on a plain address result, so
 * taking it at face value titled a result "500" and pushed the street into the small print
 * — which is what made a list of results almost impossible to tell apart.
 */
object AddressLabel {

    /**
     * House number and street, in the order people say them.
     *
     * Null without a street, deliberately: a house number on its own is not an address, and
     * letting one stand as a title is the exact failure this whole file exists to prevent.
     */
    fun street(parts: AddressParts): String? {
        val road = parts.road?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return listOfNotNull(houseNumber(parts), road).joinToString(" ")
    }

    /**
     * The house number, from the structured field or recovered from the one-line address.
     *
     * Nominatim does not always fill `house_number` even when its own display name leads
     * with the number, and a result offered as "Elm Street" to somebody who typed "500 Elm
     * Street" is not the address they asked for — it is the whole road.
     */
    private fun houseNumber(parts: AddressParts): String? {
        parts.houseNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val road = parts.road ?: return null
        val head = parts.displayName.substringBefore(",").trim()
        if (head.isEmpty() || head.length > MAX_HOUSE_NUMBER_LENGTH || head.none(Char::isDigit)) return null

        // Only when the very next part is the street this result sits on. Otherwise the
        // leading chunk is a name, a postcode, or something else entirely.
        val next = parts.displayName.substringAfter(",", "").substringBefore(",").trim()
        return head.takeIf { next.equals(road, ignoreCase = true) }
    }

    /** Longer than this and the leading chunk of an address is not a house number. */
    private const val MAX_HOUSE_NUMBER_LENGTH = 10

    /**
     * The first line.
     *
     * A business or landmark is named after itself — that is what was typed and what will be
     * recognised on arrival. Everything else is named after its street, house number and
     * all. Two kinds of `name` are not names at all and have to be turned down, because
     * accepting either loses the number: the house number itself, which is what Nominatim
     * calls an address point, and the street name, which is what it calls a house sitting on
     * a road. Both would leave the driver picking between several identical-looking streets.
     */
    fun title(parts: AddressParts): String {
        val poi = parts.name?.trim()?.takeIf { candidate ->
            candidate.isNotEmpty() &&
                candidate.any(Char::isLetter) &&
                candidate != parts.houseNumber &&
                !candidate.equals(parts.road, ignoreCase = true)
        }
        return when {
            poi != null -> poi
            else -> street(parts) ?: fromDisplayName(parts.displayName)
        }
    }

    /**
     * Last resort, when the geocoder returned nothing structured at all.
     *
     * Runs on to the second part when the first is only a number: that is where the street
     * name will be, and stopping at the first comma is how a whole result came to be called
     * "500" in the first place.
     */
    private fun fromDisplayName(displayName: String): String {
        val chunks = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (chunks.isEmpty()) return displayName
        val head = chunks.first()
        if (head.any(Char::isLetter) || chunks.size < 2) return head
        return "$head ${chunks[1]}"
    }

    /**
     * The second line: where it is, skipping anything the title already said.
     *
     * Ends at the postcode. The geocoder's own one-liner carries on through county, country
     * and occasionally a continent, all of it true and none of it any use to someone
     * deciding which of five results is the one they meant.
     */
    fun locality(parts: AddressParts, title: String = title(parts)): String? {
        val line = ArrayList<String>(4)
        street(parts)?.takeIf { it != title }?.let { line.add(it) }
        parts.settlement?.takeIf { it != title && it.isNotBlank() }?.let { line.add(it) }
        parts.state?.takeIf { it.isNotBlank() }?.let { line.add(it) }
        parts.postcode?.takeIf { it.isNotBlank() }?.let { line.add(it) }
        return line.distinct().joinToString(", ").takeIf { it.isNotBlank() }
    }
}

/**
 * Separates an address from the unit inside it.
 *
 * Suite, unit and floor numbers are almost never in OpenStreetMap — the building gets
 * mapped, the tenancies inside it do not — and leaving one in the query is enough to turn a
 * perfectly findable address into no results whatsoever. That is what makes searching for a
 * business address frustrating: the part a person is most sure of is the part that breaks
 * it. The unit is lifted out before the query goes anywhere and shown back beside the
 * result, since it is exactly what the driver needs at the far end of the journey.
 */
object AddressQuery {

    /** A query split into the part a geocoder can match and the part it cannot. */
    data class Split(val searchable: String, val unit: String?)

    private val KEYWORDS =
        "suite|ste|unit|apt|apartment|flat|room|floor|bldg|building"

    /**
     * Matches a unit keyword, or a bare `#`, followed by a short token.
     *
     * The token has to carry a digit before anything is stripped, which is what keeps
     * "Ste Anne" a saint and "Miami, FL 33101" in Florida rather than on the 33101st floor.
     */
    private val UNIT = Regex(
        """(?:^|[\s,])(?:($KEYWORDS)\.?\s*#?\s*|#\s*)([A-Za-z0-9][A-Za-z0-9\-]{0,5})(?=[\s,]|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun split(raw: String): Split {
        var unit: String? = null

        val stripped = UNIT.replace(raw) { match ->
            val value = match.groupValues[2]
            if (value.none(Char::isDigit)) return@replace match.value

            if (unit == null) {
                val keyword = match.groupValues[1].ifBlank { "Unit" }
                unit = "${keyword.replaceFirstChar(Char::uppercaseChar)} $value"
            }
            // Preserve whatever separated this from the previous word, so "500 Elm St
            // Suite 200" does not come back as "500 Elm StSuite".
            if (match.value.firstOrNull()?.isWhitespace() == true) " " else ""
        }

        val tidied = stripped
            // Removing "Suite 200" from "…Street Suite 200, Durham" leaves the separator it
            // was standing on stranded in front of the comma.
            .replace(Regex("""\s+(?=,)"""), "")
            .replace(Regex("""\s*,\s*(?=,)"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim(',')
            .trim()

        // Refusing to search at all would be worse than searching for the unit number, so a
        // query that was nothing but a unit is handed back untouched.
        return if (tidied.isBlank()) Split(raw.trim(), null) else Split(tidied, unit)
    }
}
