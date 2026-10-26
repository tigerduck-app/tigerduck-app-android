package org.ntust.app.tigerduck.data.sharing

import org.ntust.app.tigerduck.network.CourseService
import java.time.Instant

/**
 * A class table share code read from a QR code: whose class table it is and the course codes of
 * each term it shares. The iOS app reads and writes the same codes: ours in the packed layout both
 * apps agreed on 2026-10-19 ([PackedClassTableCode]), under the share host ([PREFIX]), and TAT's.
 */
data class ClassTableShareCode(
    val format: Format,
    /** Null only for a TAT code whose sharer left it blank. */
    val studentId: String?,
    /** To the minute; only TigerDuck codes carry it. */
    val sharedAt: Instant?,
    val terms: List<Term>,
) {
    enum class Format {
        TIGER_DUCK,

        /** TAT's code with class times, which the decoder checks and drops. */
        TAT2,

        /** TAT's older code, with no class times. */
        TAT1,
    }

    data class Term(
        /** The app's semester code, such as `1151`, or `114H` for a summer term. */
        val semester: String,
        val courseNos: List<String>,
    )

    companion object {
        private const val SHARE_HOST_NAME = "SHARE.TIGERDUCK.APP"
        private const val SHARE_HOST = "HTTPS://$SHARE_HOST_NAME"

        /**
         * Where every share link lives, in the upper case that keeps a QR code in alphanumeric
         * mode: `HTTPS://SHARE.TIGERDUCK.APP/<TYPE>/<content>`, so a later version can share
         * other things beside class tables. Codes written on tigerduck.app before the move are
         * not read (decided for both apps): that format never reached a release of this app.
         */
        const val SHARE_ROOT = "$SHARE_HOST/"

        /** A class table's share code: the type `CLASSTABLE`, then the packed layout's version and payload. */
        const val PREFIX = SHARE_ROOT + "CLASSTABLE/"

        private const val CLASS_TABLE_TYPE = "/CLASSTABLE/"
        private val TAT_ADDRESS = TAT_PREFIX.removePrefix("HTTPS://")

        /**
         * Reads a code as scanned, typed, pasted or opened as a link, and throws a
         * [ClassTableShareCodeError] for anything else. The forms it takes are in the decision
         * record's "Reading a share code", as the iOS app reads them: percent-encoding decoded,
         * surrounding whitespace and letter case ignored, a query or fragment dropped, with
         * `https://`, `http://` or no scheme and a `/` after the code. Off the share host and
         * TAT's, the last `/` segment is read as the code.
         */
        fun decode(text: String): ClassTableShareCode {
            // A pasted TAT link carries the spaces that pad a short student ID as `%20`.
            val code = (percentDecoded(text) ?: text).trim { it in TRIMMED }.uppercase()
            // No code holds `?` or `#`, so what follows is a query or fragment someone added.
            val link = code.takeWhile { it != '?' && it != '#' }
            val scheme = listOf("HTTPS://", "HTTP://").firstOrNull { link.startsWith(it) }
            val address = link.removePrefix(scheme.orEmpty())
            shareHostPath(address)?.let { path ->
                if (path.startsWith(CLASS_TABLE_TYPE)) {
                    return PackedClassTableCode.decode(PREFIX + withoutTrailingSlash(path.removePrefix(CLASS_TABLE_TYPE)))
                }
                throw if (namesType(path)) ClassTableShareCodeError.UnknownType() else ClassTableShareCodeError.Malformed()
            }
            if (address.startsWith(TAT_ADDRESS)) {
                return decodeTat(TAT_PREFIX + withoutTrailingSlash(address.removePrefix(TAT_ADDRESS)))
            }
            // Off both hosts the code is the last segment, and anything short of a whole code is
            // not one, so a stray QR code or word never shows an error.
            val last = address.split('/').lastOrNull { it.isNotEmpty() }.orEmpty()
            return try {
                if (last.startsWith("TAT")) decodeTat(TAT_PREFIX + last) else PackedClassTableCode.decode(PREFIX + last)
            } catch (_: ClassTableShareCodeError) {
                throw ClassTableShareCodeError.UnknownPrefix()
            }
        }

        private fun withoutTrailingSlash(text: String) = text.removeSuffix("/")

        /** [char]'s value as an ASCII hex digit; null for anything else, fullwidth digits included. */
        private fun asciiHexDigit(char: Char): Int? = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> null
        }

        /** What follows the share host when the host ends there; null for another host, including one that only starts the same. */
        private fun shareHostPath(address: String): String? {
            if (!address.startsWith(SHARE_HOST_NAME)) return null
            val rest = address.substring(SHARE_HOST_NAME.length)
            return rest.takeIf { it.isEmpty() || it.startsWith('/') }
        }

        /** Whether [path], after the share host, starts with a type, as a newer app may write one: letters or digits, then `/`. */
        private fun namesType(path: String): Boolean {
            if (!path.startsWith('/')) return false
            val afterSlash = path.substring(1)
            return '/' in afterSlash && isUpperAlphanumerics(afterSlash.substringBefore('/'))
        }

        /**
         * [text] with its `%XX` escapes decoded as UTF-8, as Foundation's `removingPercentEncoding`
         * does; null when a `%` starts no escape or the bytes are not UTF-8, so the text is read
         * as it came, as on iOS.
         */
        internal fun percentDecoded(text: String): String? {
            if ('%' !in text) return text
            val bytes = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '%') {
                    val high = text.getOrNull(index + 1)?.let(::asciiHexDigit) ?: return null
                    val low = text.getOrNull(index + 2)?.let(::asciiHexDigit) ?: return null
                    bytes.write(high * 16 + low)
                    index += 3
                } else {
                    // A surrogate pair goes whole, so a character outside the BMP keeps its bytes.
                    val end = if (char.isHighSurrogate() && index + 1 < text.length) index + 2 else index + 1
                    bytes.write(text.substring(index, end).toByteArray(Charsets.UTF_8))
                    index = end
                }
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }

        /**
         * Our code for [terms]: one of the user's terms, or every term that has courses. Course
         * codes outside NTUST's shape are left out, and so is a term left without courses; null
         * when no course is left, the student ID cannot be written, or the table is past the
         * layout's limits ([PackedClassTableCode.encode]).
         */
        fun encode(studentId: String, sharedAt: Instant, terms: List<Term>): String? {
            val id = studentId.uppercase()
            if (!isUpperAlphanumerics(id)) return null
            val semesters = mutableSetOf<String>()
            val kept = terms.mapNotNull { term ->
                val semester = term.semester.uppercase()
                val courseNos = term.courseNos.map { it.uppercase() }.filter(CourseService::isCourseNo).toSortedSet().toList()
                if (!isSemester(semester) || courseNos.isEmpty() || !semesters.add(semester)) null
                else Term(semester, courseNos)
            }
            if (kept.isEmpty()) return null
            return PackedClassTableCode.encode(id, sharedAt, kept)
        }

        // --- Shared rules ---

        // A course code's shape is the app's one rule, CourseService.isCourseNo.

        /** The ROC year and the term: `1151`, `1142`, `114H`. */
        internal fun isSemester(text: String): Boolean =
            text.length == 4 && isDigits(text.substring(0, 3)) && isUpperAlphanumerics(text.substring(3))

        /**
         * One term's course codes. An empty or repeated one, which no encoder writes, makes the
         * code malformed; one outside NTUST's shape is an invalid course code.
         */
        internal fun checkedCourseNos(items: List<String>): List<String> {
            if (items.any { it.isEmpty() } || items.toSet().size != items.size) {
                throw ClassTableShareCodeError.Malformed()
            }
            items.firstOrNull { !CourseService.isCourseNo(it) }?.let { throw ClassTableShareCodeError.InvalidCourseCode(it) }
            return items
        }

        internal fun isAscii(text: String): Boolean = text.all { it < '\u0080' }

        internal fun isDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

        internal fun isUpperAlphanumerics(text: String): Boolean =
            text.isNotEmpty() && text.all { it in '0'..'9' || it in 'A'..'Z' }

        /**
         * What Apple's `CharacterSet.whitespacesAndNewlines` holds, which the iOS decoder trims,
         * so a code reads the same on both platforms.
         */
        private const val TRIMMED =
            "\t\n\u000B\u000C\r \u0085\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007" +
                "\u2008\u2009\u200A\u200B\u2028\u2029\u202F\u205F\u3000"
    }
}

/**
 * Why a share code was refused. A scanner skips an [UnknownPrefix]; the import screen asks for a
 * newer app for an [UnknownVersion] or an [UnknownType], and shows one message for the rest,
 * which stay apart for tests and logs.
 */
sealed class ClassTableShareCodeError : Exception() {
    /** Not a share code, such as another app's QR code. */
    class UnknownPrefix : ClassTableShareCodeError()

    /** A share code in a format version this app does not know yet. */
    class UnknownVersion : ClassTableShareCodeError()

    /** A share link of a type this app does not know yet, which a newer one writes. */
    class UnknownType : ClassTableShareCodeError()

    class Malformed : ClassTableShareCodeError()

    class ChecksumMismatch : ClassTableShareCodeError()

    /** A TAT code's course code outside NTUST's shape; every course ours decodes to has that shape. */
    data class InvalidCourseCode(val courseNo: String) : ClassTableShareCodeError()
}
