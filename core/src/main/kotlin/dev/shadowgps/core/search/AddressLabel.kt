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

    /** House number and street, in the order people say them. */
    fun street(parts: AddressParts): String? =
        listOfNotNull(parts.houseNumber, parts.road)
            .joinToString(" ")
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * The first line.
     *
     * A business or landmark is named after itself — that is what was typed and what will be
     * recognised on arrival. Everything else is named after its street. A `name` that is
     * really the house number is not a name at all, which is what the letter test catches.
     */
    fun title(parts: AddressParts): String {
        val poi = parts.name?.trim()?.takeIf { candidate ->
            candidate.isNotEmpty() &&
                candidate.any(Char::isLetter) &&
                candidate != parts.houseNumber
        }
        return when {
            poi != null -> poi
            else -> street(parts)
                ?: parts.displayName.substringBefore(",").trim().ifBlank { parts.displayName }
        }
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
