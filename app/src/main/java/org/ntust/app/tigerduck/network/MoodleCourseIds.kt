// Reading a Moodle `idnumber` and joining it back to the class table.
//
// An idnumber is a term prefix plus an NTUST course number ("1151AS5140701"),
// and every join in the app — the "open in Moodle" button, which courses get
// their homework fetched, which row an assignment lands on — was an exact
// match on it. Two shapes fell through:
//
// - A 合開 (co-listed) course is one Moodle course shared by two departments
//   and carries only ONE idnumber, whichever department Moodle lists first.
//   The second department's code exists nowhere but the `fullname` text, so
//   a student enrolled through it matched nothing.
// - A summer term's prefix ends in a letter ("114h"), which a four-digit check
//   rejected outright, and Moodle spells it lower-case while NTUST's own
//   catalogue spells it "114H".
//
// Mirrors iOS `SDCourse.semesterPrefix(ofMoodleId:)`,
// `MoodleEnrolledCourse.courseNos` and `AppServiceBridge.moodleCourseIdMap`.

package org.ntust.app.tigerduck.network

import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse

object MoodleCourseIds {

    /**
     * The 4-character term prefix of [idnumber], upper-cased, or null when it
     * carries none.
     *
     * A regular term is four digits (`1151`); a summer term's fourth character
     * is a letter. Every term check downstream compares against NTUST's
     * spelling, so the prefix is normalised here, once, rather than at each
     * comparison.
     *
     * ASCII only: `isDigit` / `isLetter` would also accept, say, a CJK
     * character as the term letter, and an idnumber is always ASCII.
     */
    fun semesterPrefix(idnumber: String): String? {
        if (idnumber.length <= 4) return null
        val prefix = idnumber.take(4)
        if (!prefix.take(3).all { it in '0'..'9' }) return null
        val term = prefix[3]
        if (term !in '0'..'9' && term.lowercaseChar() !in 'a'..'z') return null
        return prefix.uppercase()
    }

    /**
     * [idnumber] with its term prefix normalised, so an id spelled by one
     * system is found under a key written by the other: `114hGD3115301` and
     * `114HGD3115301` both become the latter. Anything without a prefix comes
     * back unchanged. Used on both sides of the id map.
     */
    fun normalizedIdnumber(idnumber: String): String =
        semesterPrefix(idnumber)?.let { it + idnumber.drop(4) } ?: idnumber

    /**
     * NTUST course numbers as they appear in a Moodle `fullname`. The digit
     * after the letters keeps an all-caps English word in a bilingual title
     * from reading as a course number; the optional leading `3` is the 進修部
     * form. Matched against whole tokens only — scanning finds `CS3003302`
     * inside `3CS3003302` and would mint an alias for a course that does not
     * exist.
     */
    private val courseNoToken = Regex("3?[A-Z]{2,3}[0-9][A-Z0-9]{5,6}")
    private val tokenSeparator = Regex("[^\\p{L}\\p{N}]+")

    /** See [MoodleEnrolledCourse.courseNos]. */
    internal fun courseNos(course: MoodleEnrolledCourse): List<String> {
        val own = course.courseNo
        if (own.isEmpty()) return emptyList()
        val seen = mutableSetOf(own)
        val aliases = course.fullname.orEmpty()
            .split(tokenSeparator)
            .filter { courseNoToken.matches(it) && seen.add(it) }
        return listOf(own) + aliases
    }

    /**
     * Normalised `idnumber` → Moodle's numeric course id, for every code a
     * course answers to, so a student enrolled through a co-listed course's
     * second code still gets the "open in Moodle" button.
     *
     * Aliases are laid down first and real idnumbers overwrite them, so a
     * course's own code always beats another course's fullname alias. Within
     * the alias pass the first course wins; within the primary pass the last.
     */
    fun idMap(courses: List<MoodleEnrolledCourse>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (course in courses) {
            for (alias in course.idnumbers.drop(1)) {
                map.putIfAbsent(normalizedIdnumber(alias), course.id)
            }
        }
        for (course in courses) {
            val idnumber = course.idnumber?.takeIf { it.isNotEmpty() } ?: continue
            map[normalizedIdnumber(idnumber)] = course.id
        }
        return map
    }

    /**
     * The Moodle courses behind [rosterNos]. Matches on every code a course
     * answers to: the roster holds the student's own department's code, which
     * for a co-listed course need not be the one in its idnumber.
     */
    fun forRoster(
        enrolled: List<MoodleEnrolledCourse>,
        rosterNos: Set<String>,
    ): List<MoodleEnrolledCourse> =
        enrolled.filter { course -> course.courseNos.any { it in rosterNos } }

    /**
     * Which of [course]'s numbers an assignment is filed under: the one the
     * class table holds, so course colour, the Live Update lookup and the
     * backend upload all join to the student's own row. Falls back to the
     * course's own number when the class table has none of them yet.
     */
    fun assignmentCourseNo(course: MoodleEnrolledCourse, localCourseNos: Set<String>): String =
        course.courseNos.firstOrNull { it in localCourseNos } ?: course.courseNo
}
