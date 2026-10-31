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

    // Only a year before 100 gets here, from stepping back across 100, and it has no padded spelling.
    private fun of(year: Int, part: Char) = AcademicTerm("$year$part")

    /** Year, then 1 before 2 before H. */
    override fun compareTo(other: AcademicTerm): Int {
        val byYear = year.compareTo(other.year)
        return if (byYear != 0) byYear else rank(part).compareTo(rank(other.part))
    }

    companion object {
        // Years from 100 are three digits; the catalogue writes a year before 100 padded to three
        // characters, `99 1`. [0-9], not \d: on Android \d also matches digits of other scripts.
        private val FOUR = Regex("""([0-9]{3}|[0-9]{2} )([12H])""")

        // The five-character spelling some score pages and fixtures use: year, a 0, the term digit.
        private val FIVE = Regex("""([0-9]{3})0([12])""")

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
         * The label of a stored code of any kind: all but the last character, a dash, the last, so
         * `1141` reads `114-1` and `113A` reads `113-A`. It does not read the code as a term, so
         * a padded or odd code keeps its own characters.
         */
        fun labelOf(code: String): String = code.dropLast(1) + "-" + code.takeLast(1)

        /** [compareCodes], newest first. */
        val newestFirst: Comparator<String> = Comparator { a, b -> compareCodes(b, a) }

        /**
         * Two stored codes in term order. A code that is not a term, or two spelled differently
         * but naming one term, fall back on plain string order, so the same inputs always sort
         * the same way. For four-character codes of one shape that is also the plain string order.
         *
         * Not a general order: it holds only for the shapes its one caller passes (four-character
         * codes of one spelling). Across spellings it can cycle: `1141 < 11402 < 1140A < 1141`. So
         * the score sorts, which can see the five-character form, must not reuse it.
         */
        fun compareCodes(a: String, b: String): Int {
            val x = parse(a)
            val y = parse(b)
            val byTerm = if (x != null && y != null) x.compareTo(y) else 0
            return if (byTerm != 0) byTerm else a.compareTo(b)
        }
    }
}
