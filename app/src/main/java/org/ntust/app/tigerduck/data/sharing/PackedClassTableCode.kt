package org.ntust.app.tigerduck.data.sharing

import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Term
import org.ntust.app.tigerduck.network.CourseService
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.CRC32

/**
 * The class table share code's packed layout, version 1 under [ClassTableShareCode.PREFIX]: the
 * student ID, share time, terms and course codes packed into bits and written in Base36, so the
 * code stays short and in QR alphanumeric mode, and reads offline. Both apps and the share page
 * write and read it the same way; the layout, its reference codec and the shared test codes are
 * the ones agreed with the iOS side for tigerduck-app/tigerduck-app#612 (2026-10-19).
 *
 * Every class table has exactly one code: anything a valid encoder would write differently is
 * malformed.
 */
internal object PackedClassTableCode {

    private const val VERSION = '1'
    private const val A36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private const val MAX_STUDENT_ID = 16
    private const val MAX_TERMS = 24
    private const val MAX_COURSES = 64

    /** Share times are minutes since 2024-01-01 00:00 in Taipei, in 24 bits: to 2055-11-24. */
    private val EPOCH: Instant = Instant.parse("2023-12-31T16:00:00Z")
    private const val TIME_BITS = 24
    private val TAIPEI: ZoneOffset = ZoneOffset.ofHours(8)

    /** A term's last character in a regular term list: `1`, `2` or `H`. */
    private const val KINDS = "12H"
    private const val LETTERS = "BMD"

    /** The heads in NTUST's public course lists for 1121 to 1151 and 114H, frozen for version 1. */
    private val KNOWN_HEADS = (
        "3N 3NG 3T 3TG AC AD AI AS AT BA BB BE CC CE CH CI CS CT CX DT EC EE EN EO EP ES ET FB FE FL FN GD " +
            "GE HC IB IC IM IS MA MB ME MG MI PA PE RD SD SI TB TC TE TM TU TX VE ZA"
        ).split(' ')

    /**
     * A course code's head and tail, by the app's one course-number rule: the head is the first
     * that fits of `3` and two letters, two letters, or `3` and one letter (the NTU and NTNU
     * courses, such as `3N1004701`).
     */
    private val COURSE = CourseService.COURSE_NO

    /** Every head's value: two letters, then `3` and two letters, then `3` and one letter. */
    private const val HEAD_VALUES = 676 * 2 + 26
    private val STUDENT = Regex("([A-Z])([0-9]{3})([0-9]{5})")

    // The tail shapes, in the order the encoder tries them; the last group is the section's serial.
    private val LEVELED = Regex("([0-9])([0-9]{3})([37])([0-9]{2})") // 1001 3 01
    private val LETTERED = Regex("([0-9]{3})([AB])0([0-9]{2})") // 130 A 0 01
    private val GENERAL = Regex("G([0-9]{3})3([0-9]{2})") // G 015 3 01
    private val SHORT = Regex("([0-9]{3})7([0-9]{2})") // 115 7 01
    private val DIGITS = Regex("[0-9]{7}")

    private enum class Shape { LEVELED, LETTERED, GENERAL, SHORT, DIGITS, OTHER }

    private val prefixBytes = (ClassTableShareCode.PREFIX + VERSION).toByteArray(Charsets.US_ASCII)

    // --- Encoding ---

    /**
     * The code for [terms], or null for a table it cannot hold. All terms beyond the newest 24 are
     * left out, which only sharing every term can reach. Past any other limit there is no code: a
     * term of more than 64 courses, a student ID of more than 16 characters, a share time outside
     * 2024 to 2055. [terms] are as [ClassTableShareCode.encode] keeps them: distinct, each with
     * course codes of NTUST's shape.
     */
    fun encode(studentId: String, sharedAt: Instant, terms: List<Term>): String? {
        if (studentId.length > MAX_STUDENT_ID) return null
        // In seconds, which no Instant overflows, unlike milliseconds.
        val minutes = Math.floorDiv(sharedAt.epochSecond - EPOCH.epochSecond, 60L)
        if (minutes !in 0 until (1L shl TIME_BITS)) return null
        val order = terms.sortedWith(compareBy<Term>({ year(it.semester) }, { A36.indexOf(it.semester[3]) }).reversed())
            .take(MAX_TERMS)
        if (order.isEmpty() || order.any { it.courseNos.size > MAX_COURSES }) return null

        val w = BitWriter()
        w.put(minutes, TIME_BITS)

        val d = academicYear(sharedAt) - year(order[0].semester)
        val regular = order.all { it.semester[3] in KINDS } && d >= -1
        w.put(if (regular) 0 else 1, 1)
        w.gamma(order.size.toLong())
        if (regular) {
            w.gamma(when (d) { 0L -> 1L; -1L -> 2L; 1L -> 3L; else -> d + 2 })
            w.put(KINDS.indexOf(order[0].semester[3]).toLong(), 2)
            order.zipWithNext { newer, older -> w.gamma((slot(newer.semester) - slot(older.semester)).toLong()) }
        } else {
            order.forEach { w.put(year(it.semester).toLong(), 10); w.put(A36.indexOf(it.semester[3]).toLong(), 6) }
        }

        val oldest = order.minOf { year(it.semester) }
        val student = STUDENT.matchEntire(studentId)
        if (student != null && oldest >= student.groupValues[2].toInt()) {
            w.put(0, 1)
            val letter = student.groupValues[1][0]
            when (val index = LETTERS.indexOf(letter)) {
                -1 -> { w.put((1L shl LETTERS.length) - 1, LETTERS.length); w.put((letter - 'A').toLong(), 5) }
                else -> w.put((1L shl (index + 1)) - 2, index + 1)
            }
            w.gamma((oldest - student.groupValues[2].toInt() + 1).toLong())
            w.put(student.groupValues[3].toLong(), 17)
        } else {
            w.put(1, 1)
            w.gamma(studentId.length.toLong())
            studentId.forEach { w.put(A36.indexOf(it).toLong(), 6) }
        }

        // Each term's courses by head, every head's tails in ascending order.
        val byTerm = order.map { term ->
            term.courseNos.map { COURSE.matchEntire(it)!!.destructured.let { (head, tail) -> head to tail } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, tails) -> tails.sorted() }
        }
        val used = byTerm.flatMap { it.keys }.toSet()
        val known = used.mapNotNull { head -> KNOWN_HEADS.indexOf(head).takeIf { it >= 0 } }.sorted()
        val unknown = used.filter { it !in KNOWN_HEADS }.map(::headValue).sorted()
        w.subset(known, KNOWN_HEADS.size, nonEmpty = false)
        w.gamma(unknown.size + 1L)
        unknown.forEach { w.put(it.toLong(), 11) }
        val heads = known.map { KNOWN_HEADS[it] } + unknown.map(::headText)

        if (order.size > 1 && heads.size > 1) {
            heads.forEach { head -> w.subset(byTerm.indices.filter { head in byTerm[it] }, order.size, nonEmpty = true) }
        }
        for (groups in byTerm) {
            val present = heads.filter { it in groups }
            present.forEach { w.gamma(groups.getValue(it).size.toLong()) }
            present.forEach { head ->
                var level: Int? = null
                groups.getValue(head).forEach { tail -> level = putTail(w, tail, level) }
            }
        }

        val dataBits = w.bits.toString() + "0".repeat(padding(32 + w.bits.length))
        return ClassTableShareCode.PREFIX + VERSION + toBase36(crcBits(dataBits) + dataBits)
    }

    /** Writes [tail]; returns the level the head's next leveled tail is written against. */
    private fun putTail(w: BitWriter, tail: String, level: Int?): Int? {
        val (shape, match) = shapeOf(tail)
        val g = match?.groupValues
        when (shape) {
            Shape.LEVELED -> {
                val lv = g!![1].toInt()
                w.put(0, 1)
                when (level) {
                    null -> putLevel(w, lv)
                    lv -> w.put(0, 1)
                    lv - 1 -> w.put(0b10, 2)
                    else -> { w.put(0b11, 2); putLevel(w, lv) }
                }
                w.put(g[2].toLong(), 10)
                w.put("37".indexOf(g[3][0]).toLong(), 1)
                w.gamma(g[4].toLong())
                return lv
            }
            Shape.LETTERED -> {
                w.put(0b100, 3); w.put(g!![1].toLong(), 10); w.put("AB".indexOf(g[2][0]).toLong(), 1); w.gamma(g[3].toLong())
            }
            Shape.GENERAL -> { w.put(0b101, 3); w.put(g!![1].toLong(), 10); w.gamma(g[2].toLong()) }
            Shape.SHORT -> { w.put(0b110, 3); w.put(g!![1].toLong(), 10); w.gamma(g[2].toLong()) }
            Shape.DIGITS -> { w.put(0b1110, 4); w.put(tail.toLong(), 24) }
            Shape.OTHER -> {
                w.put(0b1111, 4)
                w.put((tail.length - 6).toLong(), 1)
                tail.forEach { w.put(A36.indexOf(it).toLong(), 6) }
            }
        }
        return level
    }

    private fun putLevel(w: BitWriter, level: Int) {
        when (level) {
            in 1..4 -> { w.put(0, 1); w.put(level - 1L, 2) }
            5 -> w.put(0b10, 2)
            else -> { w.put(0b11, 2); w.put(level.toLong(), 4) }
        }
    }

    // --- Decoding ---

    /** Reads [code], upper case and trimmed, which starts with [ClassTableShareCode.PREFIX]. */
    fun decode(code: String): ClassTableShareCode {
        val body = code.substring(ClassTableShareCode.PREFIX.length)
        val version = body.firstOrNull()
        if (version != null && version in '2'..'9') throw ClassTableShareCodeError.UnknownVersion()
        if (version != VERSION) throw ClassTableShareCodeError.Malformed()
        val bits = fromBase36(body.substring(1)) ?: throw ClassTableShareCodeError.Malformed()
        if (bits.length < 32) throw ClassTableShareCodeError.Malformed()
        if (crcBits(bits.substring(32)) != bits.substring(0, 32)) throw ClassTableShareCodeError.ChecksumMismatch()

        val r = BitReader(bits)
        r.get(32)
        val sharedAt = EPOCH.plusSeconds(r.get(TIME_BITS) * 60)

        val regular = r.get(1) == 0L
        val count = r.gamma(MAX_TERMS.toLong()).toInt()
        val order = if (regular) {
            val d = when (val c = r.gamma()) { 1L -> 0L; 2L -> -1L; 3L -> 1L; else -> c - 2 }
            val kind = r.get(2)
            if (kind == 3L) malformed()
            var s = (academicYear(sharedAt) - d) * 3 + kind
            val slots = mutableListOf(s)
            repeat(count - 1) { s -= r.gamma(); slots += s }
            if (s < 0) malformed()
            slots.map { "%03d%c".format(Locale.ROOT, it / 3, KINDS[(it % 3).toInt()]) }
        } else {
            val explicit = List(count) {
                val year = r.get(10)
                val kind = r.get(6)
                if (year > 999 || kind >= 36) malformed()
                "%03d%c".format(Locale.ROOT, year, A36[kind.toInt()])
            }
            val keys = explicit.map { year(it) to A36.indexOf(it[3]) }
            if (keys.zipWithNext().any { (newer, older) -> compareValues(newer.first, older.first).let { c -> c < 0 || c == 0 && newer.second <= older.second } }) {
                malformed()
            }
            if (explicit.all { it[3] in KINDS } && academicYear(sharedAt) - year(explicit[0]) >= -1) malformed()
            explicit
        }

        val oldest = order.minOf(::year)
        val studentId = if (r.get(1) == 0L) {
            var i = 0
            while (i < LETTERS.length && r.get(1) == 1L) i++
            val letter = if (i < LETTERS.length) {
                LETTERS[i]
            } else {
                val x = r.get(5)
                if (x > 25 || ('A' + x.toInt()) in LETTERS) malformed()
                'A' + x.toInt()
            }
            val year = oldest - (r.gamma() - 1)
            val serial = r.get(17)
            if (year < 0 || serial > 99_999) malformed()
            "%c%03d%05d".format(Locale.ROOT, letter, year, serial)
        } else {
            val chars = List(r.gamma(MAX_STUDENT_ID.toLong()).toInt()) { r.get(6) }
            if (chars.any { it >= 36 }) malformed()
            chars.joinToString("") { A36[it.toInt()].toString() }.also { id ->
                val m = STUDENT.matchEntire(id)
                if (m != null && oldest >= m.groupValues[2].toInt()) malformed()
            }
        }

        val known = r.subset(KNOWN_HEADS.size, nonEmpty = false)
        // A longer list than there are heads cannot be one of distinct heads.
        val unknownCount = r.gamma() - 1
        if (unknownCount > HEAD_VALUES - KNOWN_HEADS.size) malformed()
        val unknown = List(unknownCount.toInt()) { r.get(11).toInt() }
        if (unknown.any { it >= HEAD_VALUES || headText(it) in KNOWN_HEADS } || unknown != unknown.toSortedSet().toList()) malformed()
        val heads = known.map { KNOWN_HEADS[it] } + unknown.map(::headText)
        if (heads.isEmpty()) malformed()

        val membership = if (order.size > 1 && heads.size > 1) {
            heads.map { r.subset(order.size, nonEmpty = true).toSet() }
        } else {
            heads.map { order.indices.toSet() }
        }
        val seen = mutableSetOf<Int>()
        val terms = order.mapIndexed { t, semester ->
            val present = heads.indices.filter { t in membership[it] }
            if (present.isEmpty()) malformed()
            val counts = present.map { r.gamma(MAX_COURSES.toLong()).toInt() }
            if (counts.sum() > MAX_COURSES) malformed()
            val courseNos = mutableListOf<String>()
            present.zip(counts).forEach { (head, n) ->
                var level: Int? = null
                var last: String? = null
                repeat(n) {
                    val (tail, next) = getTail(r, level)
                    level = next
                    if (last != null && tail <= last) malformed()
                    last = tail
                    // A head and tail the head rule splits otherwise, as 3N with G115701, are another course's.
                    val courseNo = heads[head] + tail
                    if (COURSE.matchEntire(courseNo)?.groupValues?.drop(1) != listOf(heads[head], tail)) malformed()
                    courseNos += courseNo
                }
            }
            seen += present
            Term(semester, courseNos.sorted())
        }
        if (seen.size != heads.size) malformed()
        if (bits.length - r.used != padding(r.used) || bits.substring(r.used).any { it != '0' }) malformed()
        return ClassTableShareCode(ClassTableShareCode.Format.TIGER_DUCK, studentId, sharedAt, terms)
    }

    private fun getTail(r: BitReader, level: Int?): Pair<String, Int?> {
        if (r.get(1) == 0L) {
            val lv = when {
                level == null -> getLevel(r)
                r.get(1) == 0L -> level
                r.get(1) == 0L -> level + 1
                else -> getLevel(r).also { if (it == level || it == level + 1) malformed() }
            }
            val number = r.get(10)
            if (lv > 9 || number > 999) malformed()
            return "%d%03d%c%s".format(Locale.ROOT, lv, number, "37"[r.get(1).toInt()], serial(r)) to lv
        }
        val code = r.get(2)
        if (code < 3) {
            val number = r.get(10)
            if (number > 999) malformed()
            val tail = when (code) {
                0L -> "%03d%c0%s".format(Locale.ROOT, number, "AB"[r.get(1).toInt()], serial(r))
                1L -> "G%03d3%s".format(Locale.ROOT, number, serial(r))
                else -> "%03d7%s".format(Locale.ROOT, number, serial(r))
            }
            return tail to level
        }
        val (tail, expected) = if (r.get(1) == 0L) {
            val number = r.get(24)
            if (number > 9_999_999) malformed()
            "%07d".format(Locale.ROOT, number) to Shape.DIGITS
        } else {
            val chars = List(6 + r.get(1).toInt()) { r.get(6) }
            if (chars.any { it >= 36 }) malformed()
            chars.joinToString("") { A36[it.toInt()].toString() } to Shape.OTHER
        }
        if (shapeOf(tail).first != expected) malformed()
        return tail to level
    }

    private fun getLevel(r: BitReader): Int {
        if (r.get(1) == 0L) return r.get(2).toInt() + 1
        if (r.get(1) == 0L) return 5
        val level = r.get(4).toInt()
        if (level in 1..5 || level > 9) malformed()
        return level
    }

    private fun serial(r: BitReader): String = "%02d".format(Locale.ROOT, r.gamma(99))

    // --- Shared ---

    private fun shapeOf(tail: String): Pair<Shape, MatchResult?> {
        for ((shape, pattern) in listOf(
            Shape.LEVELED to LEVELED, Shape.LETTERED to LETTERED, Shape.GENERAL to GENERAL, Shape.SHORT to SHORT,
        )) {
            val match = pattern.matchEntire(tail)
            if (match != null && match.groupValues.last().toInt() >= 1) return shape to match
        }
        return (if (DIGITS.matches(tail)) Shape.DIGITS else Shape.OTHER) to null
    }

    private fun year(semester: String): Int = semester.substring(0, 3).toInt()

    private fun slot(semester: String): Int = year(semester) * 3 + KINDS.indexOf(semester[3])

    /** The ROC academic year of [at] in Taipei, which starts in August. */
    private fun academicYear(at: Instant): Long {
        val local = at.atOffset(TAIPEI)
        return local.year - 1911L - if (local.monthValue < 8) 1 else 0
    }

    private fun headValue(head: String): Int = when {
        head.length == 2 && head[0] == '3' -> 676 * 2 + (head[1] - 'A')
        else -> (if (head.length == 3) 676 else 0) + (head[head.length - 2] - 'A') * 26 + (head.last() - 'A')
    }

    private fun headText(value: Int): String = when {
        value >= 676 * 2 -> "3" + ('A' + (value - 676 * 2))
        else -> (if (value >= 676) "3" else "") + ('A' + value % 676 / 26) + ('A' + value % 26)
    }

    /** The zero bits that make the last Base36 group 31 bits, or a multiple of 5. */
    private fun padding(bits: Int): Int = (bits % 31).let { r -> if (r > 25) 31 - r else Math.floorMod(-r, 5) }

    /** zlib's CRC-32 of the prefix and version, then [data] zero-filled to whole bytes, as 32 bits. */
    private fun crcBits(data: String): String {
        val bytes = ByteArray((data.length + 7) / 8)
        data.forEachIndexed { i, bit -> if (bit == '1') bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
        val crc = CRC32().apply { update(prefixBytes); update(bytes) }.value
        return crc.toString(2).padStart(32, '0')
    }

    private fun toBase36(bits: String): String = buildString {
        var i = 0
        while (i < bits.length) {
            val k = minOf(31, bits.length - i)
            var group = bits.substring(i, i + k).toLong(2)
            val width = if (k == 31) 6 else k / 5
            val digits = CharArray(width)
            for (d in width - 1 downTo 0) { digits[d] = A36[(group % 36).toInt()]; group /= 36 }
            append(digits)
            i += k
        }
    }

    /** Null for a character outside `0-9A-Z` or a group too large for its bits. */
    private fun fromBase36(text: String): String? = buildString {
        for (start in text.indices step 6) {
            val chunk = text.substring(start, minOf(start + 6, text.length))
            var group = 0L
            for (c in chunk) {
                val digit = A36.indexOf(c)
                if (digit < 0) return null
                group = group * 36 + digit
            }
            val k = if (chunk.length == 6) 31 else 5 * chunk.length
            if (group >= 1L shl k) return null
            append(group.toString(2).padStart(k, '0'))
        }
    }

    private fun malformed(): Nothing = throw ClassTableShareCodeError.Malformed()

    private fun comb(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0
        var result = 1L
        for (i in 0 until minOf(k, n - k)) result = result * (n - i) / (i + 1)
        return result
    }

    private fun bitLength(x: Long): Int = 64 - java.lang.Long.numberOfLeadingZeros(x)

    private class BitWriter {
        val bits = StringBuilder()

        fun put(x: Long, k: Int) {
            require(k == 0 && x == 0L || x >= 0 && (k >= 63 || x < (1L shl k))) { "$x in $k bits" }
            if (k > 0) bits.append(x.toString(2).padStart(k, '0'))
        }

        /** Elias gamma of [x] >= 1. */
        fun gamma(x: Long) {
            require(x >= 1)
            val k = bitLength(x)
            bits.append("0".repeat(k - 1))
            put(x, k)
        }

        /** A sorted subset of 0 until [n]: its size, then its rank among the subsets of that size. */
        fun subset(indices: List<Int>, n: Int, nonEmpty: Boolean) {
            gamma(indices.size + if (nonEmpty) 0L else 1L)
            val rank = indices.withIndex().sumOf { (i, c) -> comb(c, i + 1) }
            put(rank, bitLength(comb(n, indices.size) - 1))
        }
    }

    private class BitReader(private val bits: String) {
        var used = 0
            private set

        fun get(k: Int): Long {
            if (used + k > bits.length) malformed()
            val value = if (k == 0) 0L else bits.substring(used, used + k).toLong(2)
            used += k
            return value
        }

        /** Elias gamma, refused past [limit] or more than 32 leading zeros. */
        fun gamma(limit: Long = 1L shl 32): Long {
            var zeros = 0
            while (get(1) == 0L) {
                if (++zeros > 32) malformed()
            }
            val x = (1L shl zeros) or get(zeros)
            if (x > limit) malformed()
            return x
        }

        fun subset(n: Int, nonEmpty: Boolean): List<Int> {
            val k = if (nonEmpty) gamma(n.toLong()).toInt() else (gamma(n + 1L) - 1).toInt()
            var rank = get(bitLength(comb(n, k) - 1))
            if (rank >= comb(n, k)) malformed()
            val out = mutableListOf<Int>()
            for (i in k downTo 1) {
                var c = i - 1
                while (comb(c + 1, i) <= rank) c++
                rank -= comb(c, i)
                out += c
            }
            return out.reversed()
        }
    }
}
