package org.ntust.app.tigerduck.data.sharing

import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.shared.Course
import java.time.Instant

/**
 * What the share sheet can put in its QR code: the user's terms that have a course NTUST's
 * course query can look up, and the rules for the term picker and the TAT switch. A share code
 * carries course codes only, never names or display preferences; see [ClassTableShareCode].
 */
class ClassTableShare(studentId: String, val terms: List<Term>) {

    data class Term(val semester: String, val courses: List<Course>)

    sealed interface Selection {
        data object AllSemesters : Selection

        data class Semester(val code: String) : Selection
    }

    /** What the sheet shows: the picked term, and whether the TAT switch is on. */
    data class State(val selection: Selection, val tatCompatible: Boolean)

    val studentId: String = studentId.uppercase()

    /** "All semesters", then every term, newest first as [terms] lists them. */
    val options: List<Selection> = listOf(Selection.AllSemesters) + terms.map { Selection.Semester(it.semester) }

    /** The latest term, with the switch off. */
    val initialState: State =
        State(terms.firstOrNull()?.let { Selection.Semester(it.semester) } ?: Selection.AllSemesters, false)

    /**
     * TAT's code holds one term, and only course codes of TAT's length, so picking "All
     * semesters", or a term TAT cannot carry, turns the switch off.
     */
    fun select(state: State, selection: Selection): State =
        State(selection, state.tatCompatible && offersTat(selection))

    /** The switch stays off while the picked term cannot be written as a TAT code. */
    fun setTatCompatible(state: State, on: Boolean): State = state.copy(tatCompatible = on && offersTat(state.selection))

    fun offersTat(selection: Selection): Boolean = tatCode(selection) != null

    /** The text the QR code holds, or null when no code can be written for [state]. */
    fun code(state: State, sharedAt: Instant): String? =
        if (state.tatCompatible) tatCode(state.selection) else ourCode(state.selection, sharedAt)

    private fun ourCode(selection: Selection, sharedAt: Instant): String? {
        val shared = when (selection) {
            Selection.AllSemesters -> terms
            is Selection.Semester -> terms.filter { it.semester == selection.code }
        }
        return ClassTableShareCode.encode(
            studentId,
            sharedAt,
            shared.map { term -> ClassTableShareCode.Term(term.semester, term.courses.map { it.courseNo }) },
        )
    }

    private fun tatCode(selection: Selection): String? {
        val semester = (selection as? Selection.Semester)?.code ?: return null
        val term = terms.firstOrNull { it.semester == semester } ?: return null
        return ClassTableShareCode.encodeTat(studentId, semester, term.courses)
    }

    companion object {
        /**
         * [code] to paste as a link, as the share sheet's Copy link hands it over. The upper case
         * that keeps the QR code small reads oddly in a scheme and host, so those are lower case,
         * and the path is percent-encoded as a URL path: a pasted link would end at the spaces TAT
         * pads a short student ID with. The decoder reads it back as the code (iOS's
         * `ClassTableShareModel.link`).
         */
        fun link(code: String): String {
            val address = code.substringAfter("://")
            val host = address.substringBefore('/')
            return "https://" + host.lowercase(java.util.Locale.ROOT) + encodedPath(address.substring(host.length))
        }

        /** [path] with what a URL path may not hold written as `%XX`, as Foundation's `urlPathAllowed` keeps it. */
        private fun encodedPath(path: String): String = buildString {
            for (byte in path.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (char.isLetterOrDigit() && char < '\u0080' || char in URL_PATH_ALLOWED) {
                    append(char)
                } else {
                    append('%').append("%02X".format(java.util.Locale.ROOT, byte.toInt() and 0xFF))
                }
            }
        }

        private const val URL_PATH_ALLOWED = "!$&'()*+,-./:;=@_~"

        /** Whether a share code can name [studentId], which it writes in upper case. */
        fun canName(studentId: String?): Boolean =
            studentId != null && ClassTableShareCode.isUpperAlphanumerics(studentId.uppercase())

        /**
         * One term's courses as a share code may carry them: those not hidden in that term, with
         * a course code of NTUST's shape, the only ones the importer can look up.
         */
        fun shareable(semester: String, courses: List<Course>, isHidden: (courseNo: String) -> Boolean): List<Course> {
            if (!ClassTableShareCode.isSemester(semester.uppercase())) return emptyList()
            return courses.filter { !isHidden(it.courseNo) && CourseService.isCourseNo(it.courseNo.uppercase()) }
        }
    }
}
