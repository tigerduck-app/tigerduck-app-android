package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.network.model.moodle.MoodleMember

/**
 * A student's department, read from their student ID (#697). Moodle's `department` field is of
 * no use: NTUST fills it for the signed-in student alone, and with the degree level. An ID is B for
 * a bachelor's, M for a master's or D for a doctoral student, three digits of entry year, two of
 * department and three of serial; the code is the department, graduate institute or program the
 * student was admitted to, which a student who changed department keeps.
 */
object MoodleDepartment {

    /**
     * The `<code>` of `course_member_department_<code>` by department code, as the school's course
     * department code table names them and the iPhone's `NTUSTDepartment` (tigerduck-app#1030). No 34
     * or 35: a 2019 student list gives 34 to the English-taught program for foreign students, yet the
     * students in its TE courses have 35 in their IDs. No 14 or 41 to 45, which are discontinued.
     */
    private val keysByCode = mapOf(
        "01" to "im", "02" to "et", "03" to "me", "04" to "tx", "05" to "ct", "06" to "ch", "07" to "ee",
        "08" to "ba", "09" to "mi", "10" to "dt", "11" to "ve", "12" to "ac", "13" to "ad", "15" to "cs",
        "16" to "mg", "17" to "fl", "18" to "fn", "19" to "eo", "20" to "tm", "21" to "ma", "22" to "en",
        "23" to "be", "24" to "pa", "25" to "ci", "27" to "ic", "30" to "hc", "31" to "ce", "32" to "ec",
        "33" to "mb", "37" to "gd", "51" to "si", "52" to "ai", "53" to "es", "54" to "as",
    )

    /** Every department the app can name. */
    val keys: Set<String> = keysByCode.values.toSet()

    /**
     * A student ID as NTUST writes it: B, M or D, or lowercase as in a mail address, and eight ASCII
     * digits, and nothing else, so a cut-short ID carries no code. As the iPhone's `NTUSTDepartment`.
     */
    private val studentIdForm = Regex("""[BbMmDd][0-9]{8}""")

    /** The department code of [studentId], its 5th and 6th characters; null for an ID of another form. */
    fun code(studentId: String?): String? {
        if (studentId == null || !studentIdForm.matches(studentId)) return null
        return studentId.substring(4, 6)
    }

    /** The key of the department [studentId] was admitted to; null for a code the app cannot name. */
    fun key(studentId: String?): String? = code(studentId)?.let(keysByCode::get)
}

/** The key of the member's department, from their student ID; null where there is none to show. */
val MoodleMember.departmentKey: String? get() = MoodleDepartment.key(studentId)
