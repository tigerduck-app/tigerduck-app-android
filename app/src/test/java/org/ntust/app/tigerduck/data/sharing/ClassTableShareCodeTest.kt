package org.ntust.app.tigerduck.data.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Format
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Term
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.shared.Course
import java.time.Instant
import kotlin.random.Random

/**
 * The share code formats. Ours is the packed layout both apps agreed for tigerduck-app#612, and its
 * vectors are the iOS side's vectors.txt verbatim, written by its reference codec in Python, an
 * encoder other than either app's. The layout has one code for each class table, so our encoder
 * must write those very codes. The TAT vectors are tigerduck-app's decision 0023's, verbatim.
 */
class ClassTableShareCodeTest {

    private companion object {
        /** 2026-10-13 14:05 in Taipei, the time most vectors were shared. */
        val OCTOBER: Instant = Instant.parse("2026-10-13T06:05:00Z")

        // vectors.txt, in its order.
        const val ONE_TERM = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/1KNTO5SJB234O09283J89PYINHMLGU86XQH3K2WVREW0CHIZS78"
        const val SEVERAL_TERMS_WITH_SUMMER =
            "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/1G7XI6UJ8QNWKLS5E0UHI2L0EHI1B2EDHW5C1YZ7PXCXYT3IPC0N2O"
        const val LONGEST_STUDENT_ID = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/16V0RAQJIRG0R158Q9S0000000000005NB6ZK0K"
        const val UNUSUAL_COURSE_CODES = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/" +
            "1XGGDD7JB234OQP6KRJAJKY0XNM3APH8VOQPLT62AKG4SF7KU24A8E42KE99404OK5DJHPM2O2U"
        const val EXPLICIT_TERMS = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/17BPXMX1JS2NCFYLE9U09283GA1GT3S010"
        const val LARGEST_ALL_TERMS = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/" +
            "1ERD6XSJB23426AEQL89YN8G8ENJHIUYV1CVF8BF86UHR4KLJ88VXJZRFO3KFSX2QHYJYG9BOJOB2XWQLXLS49F409I1Z6BYO1M4LDZQ8K2M" +
            "9EKQFHPO59M1J17JQ34H3P6787KL6Q341Q7OFEJTM8E27AK5G00TL3BMMTZEL3Y448998XL2XOIRY2LM600KJ0WI35C0DQHDS54SM4GCM039" +
            "FF7ZKSN4HJHG602CAB40N3KRK1KL2VRJZD19RQ9OGXE44EOYOV7UZ296NUAL0T57E6GVC4JF60HNGWB4LWT1EQN0CUJHSWI9M"

        const val NEWER = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/2KNTO5SJB234O09283J89PYINHMLGU86XQH3K2WVREW0CHIZS78"
        const val CHANGED = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/1KNTO5SJB234O09283J89PYINHMLGU86XQH3K2WVREW0CHIZA78"
        const val UNKNOWN_TYPE = "HTTPS://SHARE.TIGERDUCK.APP/FRIEND/1ABC"
        const val NOT_A_VERSION = "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/HELLO"

        /** A code as it was written before the move, on tigerduck.app. */
        const val OLD_HOST = "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151." +
            "OMHDMNBQGQYDAMGUOGDLFDDULROWCYV6RYYFMACMBQAA.E2YUDIQ"

        const val TAT2 = "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.267-EE1010002.13.5N-MA1010003"
        const val TAT1 = "HTTPS://NTUST-TAT.WEB.APP/S/TAT11142B11300002CS1010004-PE1010005"

        /** [ONE_TERM]'s content, what follows the type, and [TAT2]'s code after its prefix. */
        const val PACKED = "1KNTO5SJB234O09283J89PYINHMLGU86XQH3K2WVREW0CHIZS78"
        const val TAT2_BODY = "TAT21151B11300001CS1010001.267-EE1010002.13.5N-MA1010003"

        fun code(studentId: String, sharedAt: Instant, vararg terms: Pair<String, String>) = ClassTableShareCode(
            Format.TIGER_DUCK, studentId, sharedAt,
            terms.map { (semester, courses) -> Term(semester, courses.split(' ').sorted()) },
        )

        /** What each vector decodes to, newest term first. */
        val TABLES = mapOf(
            ONE_TERM to code(
                "B00015999", OCTOBER,
                "1151" to "CS2001301 CS2006301 CS2007302 EE2005301 FL1002301 MA2001301 PE1006301 TCG032301",
            ),
            SEVERAL_TERMS_WITH_SUMMER to code(
                "M00015998", Instant.parse("2026-09-01T00:30:00Z"),
                "1151" to "CS5140701 CS5164701",
                "114H" to "CS5001701",
                "1142" to "BA130A001 CS5003701 EE5428701",
                "1141" to "AS5140701 CS5001702",
            ),
            LONGEST_STUDENT_ID to code("X0000000000000AB", Instant.parse("2027-03-02T15:59:00Z"), "1152" to "ME3001301"),
            UNUSUAL_COURSE_CODES to code(
                "D00015997", OCTOBER,
                "1151" to "3CS3003302 3CS3003303 3N1004701 3NG115701 3T5287701 3TG015301 CS1001001 CS1A01B01 ZZ0000099",
            ),
            EXPLICIT_TERMS to code("B00015996", OCTOBER, "1153" to "CS1001301", "1151" to "CS1002301"),
            LARGEST_ALL_TERMS to code(
                "B00015995", OCTOBER,
                "1152" to "3N7113701 FL4994701 PE113B031 TCG028301 TCG116301 TCG126301 TCG132301 TCG141301 TCG147301 TCG151301",
                "1151" to "3NG304701 FL2062301 GD213A001 PE114A041 TC2002701 TCG111301 TCG123301 TCG127301 TCG130301 TCG136302",
                "1142" to "3T6736701 FL3922701 PE139B043 TCG030301 TCG118302 TCG134301 TCG162302 TCG177301 TCG182301 TCG190301",
                "1141" to "FL2003303 PE127A051 PE128A021 TC2001701 TCG100301 TCG103301 TCG113301 TCG120301 TCG161302 TCG180301",
                "1132" to "3TG096701 AT3926701 FL4995701 PE113B052 TC2001701 TC2002701 TC2003701 TC2004702 TC2005701 TC2005702",
                "1131" to "FL3833703 FN6906701 PE114A022 TC2002701 TC2003701 TC2004701 TC2004702 TC2005701 TC2005702 TCG115301",
                "1122" to "3N1142701 3N2763701 AC5715701 FL3801701 MG6905701 PE112B053 TC1004701 TC1005701 TC1010301 TCG132301",
                "1121" to "AD5225701 EC163A001 FL5257701 MB2102301 ME3801303 PE128A011 TC1004701 TC1005701 TC1010301 TCG143302",
            ),
        )

        const val A36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }

    private inline fun <reified T : ClassTableShareCodeError> assertRefused(code: String): T =
        assertThrows(code, T::class.java) { ClassTableShareCode.decode(code) }

    private fun encode(table: ClassTableShareCode) = ClassTableShareCode.encode(table.studentId!!, table.sharedAt!!, table.terms)

    // --- The shared vectors ---

    @Test
    fun `the shared vectors decode to their tables`() {
        TABLES.forEach { (code, table) -> assertEquals(code, table, ClassTableShareCode.decode(code)) }
    }

    @Test
    fun `our encoder writes the shared vectors exactly, whatever order the terms come in`() {
        TABLES.forEach { (code, table) ->
            assertEquals(code, encode(table))
            assertEquals(code, encode(table.copy(terms = table.terms.reversed().map { it.copy(courseNos = it.courseNos.reversed()) })))
        }
    }

    @Test
    fun `the shared refused codes are refused for their reasons`() {
        assertRefused<ClassTableShareCodeError.UnknownVersion>(NEWER)
        assertRefused<ClassTableShareCodeError.ChecksumMismatch>(CHANGED)
        assertRefused<ClassTableShareCodeError.UnknownType>(UNKNOWN_TYPE)
        assertRefused<ClassTableShareCodeError.Malformed>(NOT_A_VERSION)
    }

    @Test
    fun `a version is a digit, 2 to 9 a newer one's and anything else malformed`() {
        val payload = ONE_TERM.removePrefix(ClassTableShareCode.PREFIX + "1")
        for (version in "23456789") {
            assertRefused<ClassTableShareCodeError.UnknownVersion>(ClassTableShareCode.PREFIX + version + payload)
        }
        for (version in listOf("0", "A", "Z", ".", "")) {
            assertRefused<ClassTableShareCodeError.Malformed>(ClassTableShareCode.PREFIX + version + payload)
        }
        assertRefused<ClassTableShareCodeError.Malformed>(ClassTableShareCode.PREFIX)
        assertRefused<ClassTableShareCodeError.Malformed>(ClassTableShareCode.PREFIX + "1")
    }

    @Test
    fun `every one-character change and every cut of a vector is refused`() {
        for (code in TABLES.keys) {
            for (index in ClassTableShareCode.PREFIX.length until code.length) {
                for (replacement in A36) {
                    if (replacement == code[index]) continue
                    val changed = code.substring(0, index) + replacement + code.substring(index + 1)
                    try {
                        ClassTableShareCode.decode(changed)
                        fail("read $changed")
                    } catch (_: ClassTableShareCodeError) {
                    }
                }
            }
            for (length in ClassTableShareCode.PREFIX.length until code.length) {
                try {
                    ClassTableShareCode.decode(code.substring(0, length))
                    fail("read ${code.substring(0, length)}")
                } catch (_: ClassTableShareCodeError) {
                }
            }
        }
    }

    @Test
    fun `an NTU or NTNU course code is a course, and keeps the head the rule gives it`() {
        assertTrue(CourseService.isCourseNo("3N1004701"))
        assertTrue(CourseService.isCourseNo("3T5287701"))
        // 3NG first: the same characters as head 3N with tail G115701.
        val table = code("B00015999", OCTOBER, "1151" to "3N1004701 3NG115701")
        assertEquals(table, ClassTableShareCode.decode(encode(table)!!))
        assertEquals(
            listOf(Term("1151", listOf("3N1010003", "CS1010001"))),
            ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT11151B000159993N1010003-CS1010001").terms,
        )
    }

    // --- One code for each table ---

    /**
     * A code written field by field, as the layout spells them, to make codes no encoder writes.
     * Its own CRC-32 and Base36, not the codec's. [classTable] starts every field of a one-term,
     * one-head table shared [OCTOBER] by B00015999, up to its courses.
     */
    private class RawCode {
        private val bits = StringBuilder()

        fun u(x: Long, k: Int) = apply { if (k > 0) bits.append(x.toString(2).padStart(k, '0')) }

        fun gamma(x: Long) = apply {
            val k = 64 - java.lang.Long.numberOfLeadingZeros(x)
            bits.append("0".repeat(k - 1))
            u(x, k)
        }

        fun chars(text: String) = apply { text.forEach { u(A36.indexOf(it).toLong(), 6) } }

        /** The layout's padding: zero bits up to a 31-bit group, or a multiple of 5. */
        val padding: Int get() = ((32 + bits.length) % 31).let { r -> if (r > 25) 31 - r else Math.floorMod(-r, 5) }

        /** The code, with the layout's padding or [padding] in its place. */
        fun code(padding: ((String) -> String)? = null): String {
            val zeros = "0".repeat(this.padding)
            val data = bits.toString() + (padding?.invoke(zeros) ?: zeros)
            val bytes = (data + "0".repeat(Math.floorMod(-data.length, 8))).chunked(8).map { it.toInt(2).toByte() }
            val crc = java.util.zip.CRC32().apply {
                update((ClassTableShareCode.PREFIX + "1").toByteArray())
                update(bytes.toByteArray())
            }.value.toString(2).padStart(32, '0')
            val text = (crc + data).chunked(31).joinToString("") { group ->
                val digits = if (group.length == 31) 6 else group.length / 5
                group.toLong(2).toString(36).uppercase().padStart(digits, '0')
            }
            return ClassTableShareCode.PREFIX + "1" + text
        }

        companion object {
            /** Minutes from 2024-01-01 00:00 in Taipei to [OCTOBER]. */
            val MINUTES = (OCTOBER.epochSecond - Instant.parse("2023-12-31T16:00:00Z").epochSecond) / 60

            /** Index of a head in the frozen list, for a set of one known head: 56 heads take 6 bits. */
            fun classTable(knownHead: Int, studentId: RawCode.() -> Unit = { u(0, 1).u(0, 1).gamma(116).u(15999, 17) }) =
                RawCode().u(MINUTES, 24)
                    .u(0, 1).gamma(1).gamma(1).u(0, 2) // regular: one term, this year's 1
                    .apply(studentId)
                    .gamma(2).u(knownHead.toLong(), 6) // one known head
                    .gamma(1) // no other head
        }
    }

    private val cs = 16
    private val threeN = 0

    /** CS1001301's tail: the leveled shape, level 1, 001, 3, serial 01. */
    private fun RawCode.leveled(number: Long, first: Boolean = true) = apply {
        u(0, 1)
        if (first) u(0, 1).u(0, 2) else u(0, 1)
        u(number, 10).u(0, 1).gamma(1)
    }

    @Test
    fun `the raw writer writes what the encoder does`() {
        val raw = RawCode.classTable(cs).gamma(1).leveled(1).code()
        assertEquals(ClassTableShareCode.encode("B00015999", OCTOBER, listOf(Term("1151", listOf("CS1001301")))), raw)
    }

    @Test
    fun `a head's tails out of order, or one twice, are malformed`() {
        assertRefused<ClassTableShareCodeError.Malformed>(RawCode.classTable(cs).gamma(2).leveled(2).leveled(1, first = false).code())
        assertRefused<ClassTableShareCodeError.Malformed>(RawCode.classTable(cs).gamma(2).leveled(1).leveled(1, first = false).code())
    }

    @Test
    fun `a head and tail the head rule splits otherwise are malformed`() {
        // 3N with G115701, in the other-tail shape, is 3NG115701, whose head is 3NG.
        val code = RawCode.classTable(threeN).gamma(1).u(0b1111, 4).u(1, 1).chars("G115701").code()
        assertRefused<ClassTableShareCodeError.Malformed>(code)
    }

    @Test
    fun `a tail in a later shape than the first that fits is malformed`() {
        // 1001301 is leveled, and 1234567 is seven digits.
        assertRefused<ClassTableShareCodeError.Malformed>(RawCode.classTable(cs).gamma(1).u(0b1110, 4).u(1_001_301, 24).code())
        assertRefused<ClassTableShareCodeError.Malformed>(RawCode.classTable(cs).gamma(1).u(0b1111, 4).u(1, 1).chars("1234567").code())
    }

    @Test
    fun `terms or a student ID the regular fields can hold are malformed in the explicit ones`() {
        val explicitTerms = RawCode().u(RawCode.MINUTES, 24)
            .u(1, 1).gamma(1).u(115, 10).u(1, 6) // 1151, written explicitly
            .u(0, 1).u(0, 1).gamma(116).u(15999, 17)
            .gamma(2).u(cs.toLong(), 6).gamma(1).gamma(1).leveled(1).code()
        assertRefused<ClassTableShareCodeError.Malformed>(explicitTerms)

        val otherId = RawCode.classTable(cs, studentId = { u(1, 1).gamma(9).chars("B00015999") }).gamma(1).leveled(1).code()
        assertRefused<ClassTableShareCodeError.Malformed>(otherId)
    }

    @Test
    fun `padding of another length, or not zero, is malformed`() {
        val table = RawCode.classTable(cs).gamma(1).leveled(1)
        assertRefused<ClassTableShareCodeError.Malformed>(table.code { it + "0".repeat(31) })

        // The first table of 1, 2, 3... courses whose padding has a bit to set.
        val padded = (1L..8L).map { n ->
            RawCode.classTable(cs).gamma(n).apply { (1L..n).forEach { leveled(it, first = it == 1L) } }
        }.first { it.padding > 0 }
        assertNotNull(ClassTableShareCode.decode(padded.code()))
        assertRefused<ClassTableShareCodeError.Malformed>(padded.code { it.dropLast(1) + "1" })
    }

    // --- Our encoder ---

    @Test
    fun `random tables come back as they went in, in QR alphanumeric characters only`() {
        val random = Random(20261019)
        repeat(600) { round ->
            val table = randomTable(random)
            val code = encode(table)
            assertNotNull("round $round: $table", code)
            assertTrue(code!!, code.removePrefix(ClassTableShareCode.PREFIX).all { it in A36 })
            assertEquals("round $round: $code", table, ClassTableShareCode.decode(code))
        }
    }

    @Test
    fun `terms in any case and order, a repeated term and invalid course codes are written as one table`() {
        val code = ClassTableShareCode.encode(
            studentId = "m00015998",
            sharedAt = Instant.parse("2026-09-01T00:30:42Z"),
            terms = listOf(
                Term("1142", listOf("EE5428701", "ba130a001", "CS5003701", "NOT-A-CODE")),
                Term("114h", listOf("CS5001701")),
                Term("1152", listOf("CS101")),
                Term("1151", listOf("CS5164701", "CS5140701", "CS5164701")),
                Term("1141", listOf("CS5001702", "AS5140701")),
                Term("1151", listOf("PE1010009")),
            ),
        )

        assertEquals(SEVERAL_TERMS_WITH_SUMMER, code)
    }

    @Test
    fun `nothing to share writes no code`() {
        assertNull(ClassTableShareCode.encode("B00015999", OCTOBER, emptyList()))
        assertNull(ClassTableShareCode.encode("B00015999", OCTOBER, listOf(Term("1151", listOf("CS101")))))
        assertNull(ClassTableShareCode.encode("B00015999", OCTOBER, listOf(Term("11511", listOf("CS1010001")))))
        assertNull(ClassTableShareCode.encode("B000.5999", OCTOBER, listOf(Term("1151", listOf("CS1010001")))))
        assertNull(ClassTableShareCode.encode("", OCTOBER, listOf(Term("1151", listOf("CS1010001")))))
    }

    @Test
    fun `every term shares its newest 24, and any other table past a limit writes no code`() {
        val terms = (90..114).flatMap { year -> listOf("%03d1".format(year), "%03d2".format(year)) }.take(25)
            .map { Term(it, listOf("CS1001301")) }
        val newest = terms.sortedByDescending { it.semester }.take(24)
        assertEquals(newest, ClassTableShareCode.decode(ClassTableShareCode.encode("B00015999", OCTOBER, terms)!!).terms)

        val courses = (1..65).map { "CS1%03d301".format(it) }
        assertNotNull(ClassTableShareCode.encode("B00015999", OCTOBER, listOf(Term("1151", courses.take(64)))))
        assertNull(ClassTableShareCode.encode("B00015999", OCTOBER, listOf(Term("1151", courses))))

        val one = listOf(Term("1151", listOf("CS1001301")))
        assertNotNull(ClassTableShareCode.encode("X".repeat(16), OCTOBER, one))
        assertNull(ClassTableShareCode.encode("X".repeat(17), OCTOBER, one))

        val epoch = Instant.parse("2023-12-31T16:00:00Z")
        val end = epoch.plusSeconds((1L shl 24) * 60)
        assertNotNull(ClassTableShareCode.encode("B00015999", epoch, one))
        assertNull(ClassTableShareCode.encode("B00015999", epoch.minusSeconds(60), one))
        assertNotNull(ClassTableShareCode.encode("B00015999", end.minusSeconds(60), one))
        assertNull(ClassTableShareCode.encode("B00015999", end, one))
        assertNull(ClassTableShareCode.encode("B00015999", Instant.MAX, one))
        assertNull(ClassTableShareCode.encode("B00015999", Instant.MIN, one))
    }

    // --- Reading ---

    @Test
    fun `a code reads and writes the same under a locale with its own digits`() {
        val before = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                TABLES.forEach { (code, table) ->
                    assertEquals("$tag $code", table, ClassTableShareCode.decode(code))
                    assertEquals("$tag $code", code, encode(table))
                }
            }
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun `a code reads in any letter case, with whitespace around it trimmed as the iOS app trims it`() {
        val table = TABLES.getValue(ONE_TERM)
        assertEquals(table, ClassTableShareCode.decode(" ${ONE_TERM.lowercase()}\n"))
        assertEquals(table, ClassTableShareCode.decode("\u00A0\t$ONE_TERM\u0085\u3000"))
        // Not trimmed, as iOS does not trim it either; the whole code after it still reads, as
        // the end of any text does.
        assertEquals(table, ClassTableShareCode.decode("\uFEFF$ONE_TERM"))
        assertRefused<ClassTableShareCodeError.UnknownPrefix>("\uFEFF")
    }

    // --- Lenient forms (decision record 0023, "Reading a share code"; iOS tigerduck-app#818) ---

    @Test
    fun `our link and TAT's read in any case, with http, https or no scheme, a query, fragment or trailing slash, on any host, or as the code alone`() {
        val ours = ClassTableShareCode.decode(ONE_TERM)
        val lower = PACKED.lowercase()
        for (text in listOf(
            "http://share.tigerduck.app/classtable/$lower",
            "share.tigerduck.app/CLASSTABLE/$PACKED",
            "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/$PACKED/",
            "https://share.tigerduck.app/classtable/$lower?fbclid=IwAR0abc",
            "https://share.tigerduck.app/CLASSTABLE/$PACKED/?from=mail#top",
            "https://share.tigerduck.app/CLASSTABLE/$PACKED#",
            "https://whatever.example.tld/$PACKED",
            "https://whatever.example.tld/CLASSTABLE/$lower/",
            "whatever.example.tld/classtable/$PACKED?x=1",
            "HTTPS://TIGERDUCK.APP/CLASSTABLE/$PACKED",
            "HTTPS://SHARE.TIGERDUCK.APP.EXAMPLE.COM/CLASSTABLE/$PACKED",
            "HTTPS://SHARE.TIGERDUCK.APP@EXAMPLE.COM/CLASSTABLE/$PACKED",
            "HTTPS://SHARE.TIGERDUCK.APP:443/CLASSTABLE/$PACKED",
            PACKED,
            " \n$lower\t",
        )) {
            assertEquals(text, ours, ClassTableShareCode.decode(text))
        }

        val tat = ClassTableShareCode.decode(TAT2)
        for (text in listOf(
            "https://ntust-tat.web.app/s/${TAT2_BODY.lowercase()}",
            "http://ntust-tat.web.app/s/$TAT2_BODY",
            "ntust-tat.web.app/S/$TAT2_BODY/",
            "https://ntust-tat.web.app/s/$TAT2_BODY?openExternalBrowser=1",
            "https://whatever.example.tld/S/$TAT2_BODY",
            "https://whatever.example.tld/${TAT2_BODY.lowercase()}",
            TAT2_BODY,
            " ${TAT2_BODY.lowercase()}\n",
        )) {
            assertEquals(text, tat, ClassTableShareCode.decode(text))
        }

        // A pasted TAT link carries the spaces that pad a short student ID as `%20`.
        val padded = ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT2114HB000     GE1010001.6AB")
        for (text in listOf(
            "https://ntust-tat.web.app/s/TAT2114HB000%20%20%20%20%20GE1010001.6AB",
            "https://whatever.example.tld/s/tat2114hb000%20%20%20%20%20ge1010001.6ab",
            "TAT2114HB000%20%20%20%20%20GE1010001.6AB",
        )) {
            assertEquals(text, padded, ClassTableShareCode.decode(text))
        }
    }

    @Test
    fun `an escape is two ASCII hex digits, as Foundation reads one, or the text is read as it came`() {
        // Fullwidth digits after `%`: no escape, so TAT's code starts with `%` and is malformed.
        assertRefused<ClassTableShareCodeError.Malformed>("https://ntust-tat.web.app/s/%\uFF15\uFF14AT21151B11300001CS1010001")
        assertEquals("T", ClassTableShareCode.percentDecoded("%54"))
        assertEquals("Z", ClassTableShareCode.percentDecoded("%5a"))
        assertNull(ClassTableShareCode.percentDecoded("%\uFF15\uFF14"))
        assertNull(ClassTableShareCode.percentDecoded("%5"))
        assertNull(ClassTableShareCode.percentDecoded("%E0%A4"))
        assertEquals("\u00E9", ClassTableShareCode.percentDecoded("%c3%A9"))
    }

    @Test
    fun `our host and TAT's keep their errors in every form they read in`() {
        assertRefused<ClassTableShareCodeError.UnknownType>("share.tigerduck.app/friend/1abc")
        assertRefused<ClassTableShareCodeError.UnknownType>("http://share.tigerduck.app/friend/")
        assertRefused<ClassTableShareCodeError.UnknownVersion>("http://share.tigerduck.app/classtable/9")
        assertRefused<ClassTableShareCodeError.ChecksumMismatch>(
            "share.tigerduck.app/CLASSTABLE/1KNTO5SJB234O09283J89PYINHMLGU86XQH3K2WVREW0CHIZA78",
        )
        assertRefused<ClassTableShareCodeError.Malformed>("https://share.tigerduck.app/classtable/hello?x=1")
        assertRefused<ClassTableShareCodeError.Malformed>("http://share.tigerduck.app")
        assertRefused<ClassTableShareCodeError.Malformed>("http://ntust-tat.web.app/s/TAT2115")
        assertRefused<ClassTableShareCodeError.UnknownVersion>("ntust-tat.web.app/s/tat31151b11300001cs1010001")
    }

    @Test
    fun `other QR codes, broken codes off our host and TAT's, and words are not share codes`() {
        for (text in listOf(
            "https://example.com/",
            "https://example.com/menu",
            "hello world",
            "100% sure",
            "%E0%A4",
            "12345",
            "1ABC",
            "https://example.com/1ABC",
            "https://example.com/CLASSTABLE/1KNTO5SJB234O09283J89PYINHMLGU86XQH3K2WVREW0CHIZA78",
            "https://example.com/S/TAT2115",
            "TAT2115",
            "TATTOO",
            "TAT31151B11300001CS1010001",
            "SHARE.TIGERDUCK.APP.EXAMPLE.COM/FRIEND/1ABC",
            "ftp://share.tigerduck.app/friend/1abc",
            "https://ntust-tat.web.app/",
            "/",
        )) {
            assertRefused<ClassTableShareCodeError.UnknownPrefix>(text)
        }
    }

    @Test
    fun `other QR codes and newer versions are told apart from damaged codes`() {
        val others = listOf(
            "https://example.com/CLASSTABLE/1ABC", "WIFI:S:home;T:WPA;P:secret;;", "",
            "HTTPS://TIGERDUCK.APP/X/1.", "HTTPS://SHARE.TIGERDUCK.APPS/CLASSTABLE/1.",
            "HTTPS://SHARE.TIGERDUCK.APP.EXAMPLE.COM/CLASSTABLE/1.", "HTTPS://SHARE.TIGERDUCK.APP@EXAMPLE.COM/CLASSTABLE/1.",
        )
        for (other in others) {
            assertRefused<ClassTableShareCodeError.UnknownPrefix>(other)
        }
        for (newer in listOf(NEWER, "HTTPS://NTUST-TAT.WEB.APP/S/TAT31151B11300001CS1010001")) {
            assertRefused<ClassTableShareCodeError.UnknownVersion>(newer)
        }
    }

    @Test
    fun `a share link of a type this app does not know is a newer app's, and one with no type is malformed`() {
        for (newer in listOf(UNKNOWN_TYPE, UNKNOWN_TYPE.lowercase(), "HTTPS://SHARE.TIGERDUCK.APP/T2/")) {
            assertRefused<ClassTableShareCodeError.UnknownType>(newer)
        }
        for (code in listOf(
            "HTTPS://SHARE.TIGERDUCK.APP/",
            "HTTPS://SHARE.TIGERDUCK.APP",
            "HTTPS://SHARE.TIGERDUCK.APP?FROM=MAIL",
            "HTTPS://SHARE.TIGERDUCK.APP#TOP",
            "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE",
            "HTTPS://SHARE.TIGERDUCK.APP/FRIEND",
            "HTTPS://SHARE.TIGERDUCK.APP//1ABC",
            "HTTPS://SHARE.TIGERDUCK.APP/FRI-END/1ABC",
        )) {
            assertRefused<ClassTableShareCodeError.Malformed>(code)
        }
    }

    @Test
    fun `a code with the prefix from before the move to the share host is not a share code any more`() {
        assertRefused<ClassTableShareCodeError.UnknownPrefix>(OLD_HOST)
        assertRefused<ClassTableShareCodeError.UnknownPrefix>(OLD_HOST.lowercase())
    }

    @Test
    fun `the decision record's TAT codes decode, version 2 dropping its class times`() {
        assertEquals(
            ClassTableShareCode(
                Format.TAT2, "B11300001", null, listOf(Term("1151", listOf("CS1010001", "EE1010002", "MA1010003"))),
            ),
            ClassTableShareCode.decode(TAT2),
        )
        assertEquals(
            ClassTableShareCode(Format.TAT1, "B11300002", null, listOf(Term("1142", listOf("CS1010004", "PE1010005")))),
            ClassTableShareCode.decode(TAT1),
        )
    }

    @Test
    fun `a TAT course code outside NTUST's shape is refused`() {
        assertEquals(
            "123456789",
            assertRefused<ClassTableShareCodeError.InvalidCourseCode>(
                "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001123456789.12",
            ).courseNo,
        )
    }

    /**
     * A table the layout can hold, reaching each of its branches: NTUST-shaped and other student
     * IDs, regular and explicit term lists, known and other heads, and every tail shape.
     */
    private fun randomTable(random: Random): ClassTableShareCode {
        val sharedAt = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(random.nextLong(0, 30L * 365 * 24 * 60) * 60)
        val academicYear = sharedAt.atOffset(java.time.ZoneOffset.ofHours(8)).let { it.year - 1911 - if (it.monthValue < 8) 1 else 0 }
        val explicit = random.nextInt(5) == 0
        val semesters = mutableSetOf<String>()
        repeat(random.nextInt(1, 9)) {
            val year = if (explicit) random.nextInt(0, 1000) else academicYear + 1 - random.nextInt(0, 6)
            val kind = if (explicit) A36[random.nextInt(36)] else "12H"[random.nextInt(3)]
            if (year >= 0) semesters += "%03d%c".format(year, kind)
        }
        val heads = listOf("CS", "EE", "PE", "TC", "3NG", "ZZ", "3CS", "QX", "3N", "3T", "3Q")
        val terms = semesters.map { semester ->
            val courses = (1..random.nextInt(1, 12)).map { heads[random.nextInt(heads.size)] + randomTail(random) }
            Term(semester, courses.toSortedSet().toList())
        }.sortedWith(compareByDescending<Term> { it.semester.take(3).toInt() }.thenByDescending { A36.indexOf(it.semester[3]) })
        val oldest = terms.minOf { it.semester.take(3).toInt() }
        val studentId = when (random.nextInt(3)) {
            0 -> "%c%03d%05d".format("BMDX"[random.nextInt(4)], random.nextInt(0, oldest + 1), random.nextInt(100_000))
            1 -> (1..random.nextInt(1, 17)).map { A36[random.nextInt(36)] }.joinToString("")
            else -> "B%03d%05d".format(minOf(999, oldest + 1), random.nextInt(100_000))
        }
        return ClassTableShareCode(Format.TIGER_DUCK, studentId, sharedAt, terms)
    }

    private fun randomTail(random: Random): String {
        fun digits(n: Int) = (1..n).map { '0' + random.nextInt(10) }.joinToString("")
        fun serial() = "%02d".format(random.nextInt(1, 100))
        return when (random.nextInt(7)) {
            0, 1 -> "${random.nextInt(10)}${digits(3)}${"37"[random.nextInt(2)]}${serial()}"
            2 -> "${digits(3)}${"AB"[random.nextInt(2)]}0${serial()}"
            3 -> "G${digits(3)}3${serial()}"
            4 -> "${digits(3)}7${serial()}"
            5 -> digits(7)
            else -> (1..random.nextInt(6, 8)).map { A36[random.nextInt(36)] }.joinToString("")
        }
    }

    // --- TAT ---

    @Test
    fun `a TAT version 2 code may pad or leave out its student ID`() {
        val padded = ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT2114HB113     GE1010001.6AB")
        assertEquals("B113", padded.studentId)
        assertEquals(listOf(Term("114H", listOf("GE1010001"))), padded.terms)

        val blank = ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT21151         ")
        assertNull(blank.studentId)
        assertEquals(listOf(Term("1151", emptyList())), blank.terms)
    }

    @Test
    fun `a TAT code with a weekday, period or course code length TAT never writes is malformed`() {
        for (code in listOf(
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.83",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.3Z",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.3",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B113000013CS1010001",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001-",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001-CS1010001",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT2115",
            "HTTPS://NTUST-TAT.WEB.APP/S/XYZ21151B11300001CS1010001",
        )) {
            assertRefused<ClassTableShareCodeError.Malformed>(code)
        }
    }

    @Test
    fun `the TAT encoder writes each course's weekdays and periods in TAT's letters, and the code decodes`() {
        val courses = listOf(
            Course.fromSchedule("CS1010001", "A", schedule = mapOf(2 to listOf("8", "7"))),
            Course.fromSchedule("EE1010002", "B", schedule = mapOf(5 to listOf("5"), 1 to listOf("3"))),
            Course.fromSchedule("GE1010003", "C", schedule = mapOf(3 to listOf("10", "A", "9"), 6 to listOf("D"))),
            Course.fromSchedule("MA1010004", "D"),
            Course.fromSchedule("3CS1010005", "E", schedule = mapOf(1 to listOf("1"))),
            Course.fromSchedule("CS1010001", "A again", schedule = mapOf(4 to listOf("1"))),
            Course.fromSchedule("PE1010006", "F", schedule = mapOf(8 to listOf("1"), 4 to listOf("X"))),
        )

        val code = ClassTableShareCode.encodeTat("B11300001", "1151", courses)!!

        // A TAT code holds no compressed bytes, so its text is fixed by the decision record.
        assertEquals(
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001" +
                "CS1010001.267-EE1010002.13.5N-GE1010003.389A.6D-MA1010004-PE1010006",
            code,
        )
        assertEquals(
            ClassTableShareCode(
                Format.TAT2, "B11300001", null,
                listOf(Term("1151", listOf("CS1010001", "EE1010002", "GE1010003", "MA1010004", "PE1010006"))),
            ),
            ClassTableShareCode.decode(code),
        )
        assertEquals("B113", ClassTableShareCode.decode(ClassTableShareCode.encodeTat("b113", "1151", courses)!!).studentId)
        assertNull(ClassTableShareCode.encodeTat("B113000011", "1151", courses))
        assertNull(ClassTableShareCode.encodeTat("B11300001", "1151", listOf(courses[4])))
    }
}
