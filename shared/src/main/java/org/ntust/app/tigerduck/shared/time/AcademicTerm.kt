package org.ntust.app.tigerduck.shared.time

/**
 * A school term as the app writes its code: the ROC year and the term, `1141` for 114 學年度第 1
 * 學期, `114H` for that year's summer term. The one place that reads a term code, puts two in
 * order, and writes one for a person.
 *
 * The code stays a string everywhere it is stored or sent (cache file names, preferences, what
 * the backend gets), so this is a reader and a comparator over those strings, not a field type:
 * never put it on a class Gson reads.
 */
@JvmInline
value class AcademicTerm private constructor(val code: String) : Comparable<AcademicTerm> {

    /** The ROC year, such as 114. */
    val year: Int get() = code.dropLast(1).trim().toInt()

    /** `'1'`, `'2'` or `'H'`. */
    val part: Char get() = code.last()

    /** The label people read: `114-1`, `114-H`. */
    val label: String get() = "$year-$part"

    /**
     * The term before this one: 1142 is preceded by 1141 and 1141 by 1132. A summer term has no
     * rule, as none ever did, so `114H` comes back as itself and a walk back stops there rather than
     * inventing a term.
     */
    fun previous(): AcademicTerm = when (part) {
        '2' -> of(year, '1')
        '1' -> of(year - 1, '2')
        else -> this
    }

    // A year before 100 keeps the spelling it was read in, padded to three characters or not.
    private fun of(year: Int, part: Char): AcademicTerm {
        val padded = if (' ' in code && year < 100) "%02d ".format(year) else "%02d".format(year)
        return AcademicTerm((if (year >= 100) "$year" else padded) + part)
    }

    /** Year, then 1 before 2 before H. */
    override fun compareTo(other: AcademicTerm): Int {
        val byYear = year.compareTo(other.year)
        return if (byYear != 0) byYear else rank(part).compareTo(rank(other.part))
    }

    companion object {
        // A year before 100 is two digits, which the catalogue pads to three characters: `99 1`,
        // and a transcript may leave bare: `991`. Years from 100 are three digits.
        private val FOUR = Regex("""(\d{3}|\d\d ?)([12H])""")

        // The five-character spelling some score pages and fixtures use: year, a 0, the term digit.
        private val FIVE = Regex("""(\d{3})0([12])""")

        private fun rank(part: Char): Int = if (part == 'H') 3 else part - '0'

        /**
         * The term [raw] names, trimmed and upper-cased, with `11402` read as `1142`; null for
         * null, blank or any other shape, never a throw.
         */
        fun parse(raw: String?): AcademicTerm? {
            val text = raw?.trim()?.uppercase() ?: return null
            FOUR.matchEntire(text)?.let { return AcademicTerm(text) }
            return FIVE.matchEntire(text)?.let { AcademicTerm(it.groupValues[1] + it.groupValues[2]) }
        }

        /** The term only when [raw] is already its code as stored: no padding, no lower case, no five-character spelling. */
        fun parseExact(raw: String?): AcademicTerm? = parse(raw)?.takeIf { it.code == raw }

        /**
         * [parse]d label of [code], so `1141` reads `114-1`. A four-character code of another kind
         * (`113A`) gets the same shape; anything else comes back as written.
         */
        fun labelOf(code: String): String =
            parse(code)?.label ?: if (code.length == 4) code.take(3) + "-" + code.last() else code

        /**
         * Two stored codes in term order. A code that is not a term, or two spelled differently
         * but naming one term, fall back on plain string order, so the same inputs always sort
         * the same way. For four-character codes of one shape that is also the plain string order.
         */
        fun compareCodes(a: String, b: String): Int {
            val x = parse(a)
            val y = parse(b)
            val byTerm = if (x != null && y != null) x.compareTo(y) else 0
            return if (byTerm != 0) byTerm else a.compareTo(b)
        }
    }
}
