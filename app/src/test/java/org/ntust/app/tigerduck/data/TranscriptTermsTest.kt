package org.ntust.app.tigerduck.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.network.NtustScoreParser
import org.ntust.app.tigerduck.shared.Course

/**
 * Which courses a term takes from the transcript, and how an ended term is rebuilt from it. As
 * iOS EnrolledCourseNosTests and the pure half of PastTermRebuildTests (tigerduck-app#890); the
 * course numbers and terms are made up.
 */
class TranscriptTermsTest {

    private val gson = Gson()

    private fun transcript(vararg rows: Row): ScoreReport {
        val cells = rows.mapIndexed { index, row ->
            "<tr><td>${index + 1}</td><td>${row.term}</td><td>${row.code}</td><td>${row.name}</td><td>2</td>" +
                "<td>${row.grade}</td><td>${row.remark}</td><td></td><td>否</td></tr>"
        }
        return NtustScoreParser.parse(
            """
            <html><body><div class="box">
              <div class="box-header"><h2>歷年學業成績列表</h2></div>
              <table>
                <tr><th>#</th><th>學期</th><th>課號</th><th>課名</th><th>學分</th>
                    <th>成績</th><th>備註</th><th>向度</th><th>遠距</th></tr>
                ${cells.joinToString("\n")}
              </table>
            </div></body></html>
            """.trimIndent(),
        )
    }

    private class Row(val term: String, val code: String, val name: String, val grade: String, val remark: String = "")

    private fun report(vararg rows: Triple<String, String, GradeStatus>) = ScoreReport(
        student = "",
        currentTerm = "",
        rankings = emptyList(),
        courses = rows.map { (term, code, status) -> CourseGrade(term = term, code = code, name = code, status = status) },
        creditSummary = CreditSummary.EMPTY,
    )

    private fun course(courseNo: String, times: Boolean = true, moodleId: String? = null, manual: Boolean = false) =
        Course.fromSchedule(
            courseNo, courseNo,
            schedule = if (times) mapOf(2 to listOf("3", "4")) else emptyMap(),
            moodleIdNumber = moodleId,
            isManual = manual,
        )

    private fun looked(courseNo: String, times: Boolean = true, answered: Boolean = true) =
        LookedUpCourse(courseNo, course(courseNo, times), answered)

    // --- EnrolledCourseNosTests ---

    @Test
    fun `a past term built from the transcript takes only its graded and passed classes, NTU and NTNU ones too`() {
        val report = transcript(
            Row("1141", "XA0000001", "Course A", "A"),
            Row("1141", "XB0000002", "Course B", "通過"),
            Row("1141", "XC0000003", "Course C", "", remark = "抵免"),
            Row("1141", "CC1011301", "校定英文能力檢測", "通過"),
            Row("1141", "XE0000005", "Course E", "二次退選"),
            Row("1141", "3N0000010", "Alliance course", "B+"),
            Row("1141", "3T0000011", "Alliance course", "A-"),
            Row("1142", "XJ0000009", "Course J", "A"),
        )

        val term = TranscriptTerms.termCourseNos(
            selection = null,
            transcript = TranscriptTerms.classRows(report, "1141").map { it.code },
            cached = emptyList(),
        )

        assertEquals(listOf("XA0000001", "XB0000002", "3N0000010", "3T0000011"), term?.courseNos)
    }

    @Test
    fun `a transcript only adds, so a term it lists late or in part keeps its cached courses and records no drops`() {
        val term = TranscriptTerms.termCourseNos(
            selection = null, transcript = listOf("XA0000001", "XB0000002"), cached = listOf("XJ0000009", "XA0000001"),
        )

        assertEquals(listOf("XA0000001", "XB0000002", "XJ0000009"), term?.courseNos)
        assertNull(term?.roster)
    }

    @Test
    fun `a class the transcript adds joins its term only with class times, while course selection's and cached ones stay`() {
        val courses = listOf(
            Course.fromSchedule("XA0000001", "Timed", schedule = mapOf(2 to listOf("3", "4"))),
            Course.fromSchedule("XB0000002", "No periods"),
            Course.fromSchedule("XE0000005", "Empty day", schedule = mapOf(3 to emptyList())),
            Course.fromSchedule("XJ0000009", "Cached"),
        )

        val fromTranscript = TranscriptTerms.drawnCourses(courses, fromSelection = false, cached = setOf("XJ0000009"))
        val fromSelection = TranscriptTerms.drawnCourses(courses, fromSelection = true, cached = emptySet())

        assertEquals(listOf("XA0000001", "XJ0000009"), fromTranscript.map { it.courseNo })
        assertEquals(listOf("XA0000001", "XB0000002", "XE0000005", "XJ0000009"), fromSelection.map { it.courseNo })
    }

    @Test
    fun `the English proficiency test is matched by its course number, or by its exact name`() {
        fun row(code: String, name: String) =
            CourseGrade(index = 1, term = "1141", code = code, name = name, grade = "通過", status = GradeStatus.PASS_FAIL_GRADED)

        assertTrue(TranscriptTerms.isEnglishProficiencyTest(row("CC1011301", "校定英文能力檢測")))
        assertTrue(TranscriptTerms.isEnglishProficiencyTest(row("CC1011301", "English Proficiency Test")))
        assertTrue(TranscriptTerms.isEnglishProficiencyTest(row("XK0000012", " 校定英文能力檢測 ")))
        assertFalse(TranscriptTerms.isEnglishProficiencyTest(row("XL0000013", "English Proficiency Test Preparation")))
        assertFalse(TranscriptTerms.isEnglishProficiencyTest(row("XM0000014", "校定英文能力檢測輔導")))
    }

    @Test
    fun `a past term whose transcript cannot be fetched, or lists nothing for it, keeps its cached courses`() {
        assertNull(TranscriptTerms.termCourseNos(selection = null, transcript = null, cached = listOf("XA0000001")))
        assertNull(TranscriptTerms.termCourseNos(selection = null, transcript = emptyList(), cached = listOf("XA0000001")))
    }

    @Test
    fun `course selection's answer owns the latest term and is its roster, so a stale transcript adds nothing`() {
        val term = TranscriptTerms.termCourseNos(
            selection = listOf("XA0000001", "XB0000002", "XA0000001"),
            transcript = listOf("XA0000001", "XD0000004"),
            cached = listOf("XA0000001", "XC0000003"),
        )

        assertEquals(listOf("XA0000001", "XB0000002"), term?.courseNos)
        assertEquals(listOf("XA0000001", "XB0000002"), term?.roster)
    }

    @Test
    fun `with course selection unreachable or empty, the latest term falls back to the transcript`() {
        val unreachable = TranscriptTerms.termCourseNos(
            selection = null, transcript = listOf("XE0000005", "", "XE0000005", "XD0000004"), cached = emptyList(),
        )
        assertEquals(listOf("XE0000005", "XD0000004"), unreachable?.courseNos)
        assertEquals(
            listOf("XD0000004"),
            TranscriptTerms.termCourseNos(selection = emptyList(), transcript = listOf("XD0000004"), cached = emptyList())?.courseNos,
        )
    }

    // --- PastTermRebuildTests, the pure half ---

    @Test
    fun `a past term keeps only the transcript's classes with class times, and cached junk, Moodle sites and other rows are dropped`() {
        val report = report(
            Triple("1141", "XA0000001", GradeStatus.GRADED),
            Triple("1141", "XB0000002", GradeStatus.GRADED),
            Triple("1141", "CC1011301", GradeStatus.PASS_FAIL_GRADED),
            Triple("1141", "XD0000004", GradeStatus.WITHDREW),
        )

        val rebuilt = TranscriptTerms.rebuiltPastTerm(
            listOf(looked("XA0000001"), looked("XB0000002", times = false)), report, "1141",
            cached = listOf("XA0000001", "XB0000002", "XC0000003").map { course(it) },
            moodleNos = listOf("XN0000015"),
            waitingAdds = emptySet(),
        )

        assertEquals(listOf("XA0000001"), rebuilt.courses.map { it.courseNo })
        assertEquals(listOf("XA0000001"), rebuilt.roster)
        assertEquals(
            setOf("XB0000002", "XC0000003", "XD0000004", "CC1011301", "XN0000015"),
            CourseRosterMerge.selectionDrops(previous = emptySet(), localPortalNos = rebuilt.dropping, roster = rebuilt.roster),
        )
        assertEquals(setOf("XB0000002", "XC0000003", "XD0000004", "CC1011301", "XN0000015"), rebuilt.dropped)
    }

    @Test
    fun `a class whose lookup failed keeps its cached copy and is never dropped, and one with no copy waits for a later fetch`() {
        val report = report(Triple("1141", "XA0000001", GradeStatus.GRADED), Triple("1141", "XE0000005", GradeStatus.GRADED))

        val rebuilt = TranscriptTerms.rebuiltPastTerm(
            listOf(looked("XA0000001", times = false, answered = false), LookedUpCourse("XE0000005", null, answered = false)),
            report, "1141",
            cached = listOf(course("XA0000001")),
            moodleNos = emptyList(),
            waitingAdds = emptySet(),
        )

        assertEquals(listOf("XA0000001"), rebuilt.courses.map { it.courseNo })
        // The cached copy, with the times the failed lookup did not bring.
        assertEquals(mapOf(2 to listOf("3", "4")), rebuilt.courses.single().schedule)
        assertTrue(
            CourseRosterMerge.selectionDrops(emptySet(), listOf("XA0000001", "XE0000005") + rebuilt.dropping, rebuilt.roster).isEmpty(),
        )
    }

    @Test
    fun `a failed transcript fetch, or one that lists nothing for the term, leaves a past term untouched`() {
        assertNull(TranscriptTerms.termCourseNos(selection = null, transcript = null, cached = emptyList()))
        assertNull(TranscriptTerms.termCourseNos(selection = null, transcript = emptyList(), cached = emptyList()))
    }

    @Test
    fun `a course added by hand here stays out of the drops, and junk merged from the server does not`() {
        val report = report(Triple("1141", "XA0000001", GradeStatus.GRADED))
        val cached = listOf(
            course("XA0000001"),
            // Added by hand here: no Moodle ID.
            course("XF0000006", manual = true),
            // Merged from server rows.
            course("XG0000007", moodleId = "1141XG0000007", manual = true),
            course("XC0000003", moodleId = "1141XC0000003", manual = true),
        )

        val rebuilt = TranscriptTerms.rebuiltPastTerm(
            listOf(looked("XA0000001")), report, "1141", cached,
            moodleNos = listOf("XF0000006", "XC0000003", "XP0000016"),
            // Added by hand, its upload still waiting, and on Moodle too.
            waitingAdds = setOf("XP0000016"),
        )

        assertFalse("XF0000006" in rebuilt.dropping)
        assertFalse("XP0000016" in rebuilt.dropping)
        assertEquals(setOf("XG0000007", "XC0000003"), rebuilt.dropped)
    }

    @Test
    fun `the newest term the transcript lists only adds, being a just-ended term listed in part or one still running`() {
        val justEnded = report(Triple("1141", "XA0000001", GradeStatus.GRADED), Triple("1142", "XE0000005", GradeStatus.PENDING))
        assertFalse(TranscriptTerms.rebuildsFromTranscript(justEnded, "1142", servedBySelection = false))
        assertTrue(TranscriptTerms.rebuildsFromTranscript(justEnded, "1141", servedBySelection = false))

        val running = report(
            Triple("1141", "XA0000001", GradeStatus.GRADED),
            Triple("1142", "XE0000005", GradeStatus.UNKNOWN),
            Triple("1151", "XB0000002", GradeStatus.EXEMPTED),
        )
        assertFalse(TranscriptTerms.rebuildsFromTranscript(running, "1142", servedBySelection = false))
        assertFalse(TranscriptTerms.rebuildsFromTranscript(running, "1141", servedBySelection = true))
        assertFalse(TranscriptTerms.rebuildsFromTranscript(null, "1141", servedBySelection = false))
        val acrossTheHundredthYear = report(Triple("991", "XA0000001", GradeStatus.GRADED), Triple("1001", "XE0000005", GradeStatus.GRADED))
        assertTrue(TranscriptTerms.rebuildsFromTranscript(acrossTheHundredthYear, "991", servedBySelection = false))
    }

    /**
     * Android orders terms by year, then 1 < 2 < H. iOS reads only the digits, so `114H` is 114
     * there, below every four-digit term, and a summer term still running was rebuilt as soon as
     * the transcript listed any earlier one.
     */
    @Test
    fun `a summer term still running is not rebuilt, and the spring before it is once it is listed`() {
        val summer = report(
            Triple("1141", "XA0000001", GradeStatus.GRADED),
            Triple("1142", "XB0000002", GradeStatus.GRADED),
            Triple("114H", "XE0000005", GradeStatus.UNKNOWN),
        )
        assertFalse(TranscriptTerms.rebuildsFromTranscript(summer, "114H", servedBySelection = false))
        assertTrue(TranscriptTerms.rebuildsFromTranscript(summer, "1142", servedBySelection = false))

        val nextYear = report(Triple("114H", "XE0000005", GradeStatus.GRADED), Triple("1151", "XA0000001", GradeStatus.UNKNOWN))
        assertTrue(TranscriptTerms.rebuildsFromTranscript(nextYear, "114H", servedBySelection = false))
    }

    @Test
    fun `a term the transcript writes in five characters is the class table's four`() {
        val report = report(Triple("11401", "XA0000001", GradeStatus.GRADED), Triple("11402", "XE0000005", GradeStatus.GRADED))

        assertTrue(TranscriptTerms.rebuildsFromTranscript(report, "1141", servedBySelection = false))
        assertEquals(listOf("XA0000001"), TranscriptTerms.classRows(report, "1141").map { it.code })
    }

    @Test
    fun `a row the course query cannot read keeps its cached copy, while rows the rule leaves out and courses off the transcript are dropped`() {
        val report = report(
            Triple("1141", "XA0000001", GradeStatus.GRADED),
            Triple("1141", "NTU-XX-000", GradeStatus.GRADED),
            Triple("1141", "XE0000005", GradeStatus.WITHDREW),
        )
        val unreadable = TranscriptTerms.unreadableClassRows(report, "1141").map { it.code }

        val rebuilt = TranscriptTerms.rebuiltPastTerm(
            listOf(looked("XA0000001")), report, "1141",
            cached = listOf("XA0000001", "NTU-XX-000", "XE0000005", "XC0000003").map { course(it) },
            moodleNos = emptyList(),
            waitingAdds = emptySet(),
        )

        assertEquals(listOf("NTU-XX-000"), unreadable)
        assertEquals(listOf("XA0000001", "NTU-XX-000"), rebuilt.courses.map { it.courseNo })
        assertEquals(
            setOf("XE0000005", "XC0000003"),
            CourseRosterMerge.selectionDrops(previous = emptySet(), localPortalNos = rebuilt.dropping, roster = rebuilt.roster),
        )
    }

    @Test
    fun `an ended term whose transcript leaves no class to draw is saved empty and drops what it held`() {
        val report = report(
            Triple("1141", "XH0000008", GradeStatus.GRADED),
            Triple("1141", "XE0000005", GradeStatus.WITHDREW),
            Triple("1142", "XA0000001", GradeStatus.GRADED),
        )
        assertTrue(TranscriptTerms.rebuildsFromTranscript(report, "1141", servedBySelection = false))

        val rebuilt = TranscriptTerms.rebuiltPastTerm(
            listOf(looked("XH0000008", times = false)), report, "1141",
            cached = listOf(course("XJ0000009")),
            moodleNos = emptyList(),
            waitingAdds = emptySet(),
        )

        assertTrue(rebuilt.courses.isEmpty())
        assertTrue(rebuilt.roster.isEmpty())
        assertEquals(
            setOf("XJ0000009", "XH0000008", "XE0000005"),
            CourseRosterMerge.selectionDrops(emptySet(), rebuilt.dropping, rebuilt.roster, isFinal = true),
        )
        assertTrue(CourseRosterMerge.selectionDrops(emptySet(), listOf("XJ0000009"), roster = emptyList()).isEmpty())
    }

    @Test
    fun `a term the transcript lists only through exempted rows is not rebuilt`() {
        val report = report(Triple("1141", "XB0000002", GradeStatus.EXEMPTED), Triple("1142", "XA0000001", GradeStatus.GRADED))
        assertFalse(TranscriptTerms.rebuildsFromTranscript(report, "1141", servedBySelection = false))
    }

    /** A cached report read back by Gson keeps a missing field null, whatever its declared type. */
    @Test
    fun `rows a cached report holds without a term or code are skipped`() {
        val broken = gson.fromJson(
            """{"courses":[{"name":"No code"},{"term":"1141"},{"term":"1141","code":"XA0000001","status":"GRADED"},
               {"term":"1142","code":"XE0000005"},null]}""",
            ScoreReport::class.java,
        )

        assertEquals(listOf("XA0000001"), TranscriptTerms.classRows(broken, "1141").map { it.code })
        assertTrue(TranscriptTerms.unreadableClassRows(broken, "1141").isEmpty())
        assertTrue(TranscriptTerms.rebuildsFromTranscript(broken, "1141", servedBySelection = false))
        val noRows = gson.fromJson("{}", ScoreReport::class.java)
        assertFalse(TranscriptTerms.rebuildsFromTranscript(noRows, "1141", servedBySelection = false))
        assertTrue(TranscriptTerms.classRows(noRows, "1141").isEmpty())
    }
}
