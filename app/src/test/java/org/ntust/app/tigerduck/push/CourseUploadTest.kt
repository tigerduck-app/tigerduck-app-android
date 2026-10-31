package org.ntust.app.tigerduck.push

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.shared.Course

/**
 * Which of a fetch's uploads goes as its term's whole roster (`roster_terms`), on which the
 * backend drops the school courses the upload leaves out (backend 3.10.1,
 * tigerduck-app-android#595). As the iPhone's `CourseRefreshUploadTests` (tigerduck-app#914).
 * Course numbers are made up.
 */
class CourseUploadTest {

    private fun fetched(no: String) = Course(courseNo = no, courseName = no, moodleIdNumber = "$TERM$no")

    private fun addedHere(no: String) = Course(courseNo = no, courseName = no, isManual = true)

    private fun mergedCopy(no: String) = Course(courseNo = no, courseName = no, isManual = true, moodleIdNumber = "$TERM$no")

    private fun upload(courses: List<Course>, roster: List<String>?, hidden: Set<String> = emptySet()) =
        CourseUpload.of(courses, TERM, roster, hidden)

    @Test
    fun `course selection's answer goes as its term's roster`() {
        val sent = upload(listOf(fetched(CS1), fetched(CS2)), roster = listOf(CS1, CS2))

        assertEquals(listOf(TERM), sent.rosterTerms)
        assertEquals(listOf(CS1, CS2), sent.courses.map { it.courseNo })
    }

    @Test
    fun `the transcript, the cache or a hand-added refresh, which course selection did not answer, is no roster`() {
        val sent = upload(listOf(fetched(CS1), fetched(CS2), mergedCopy(CS3)), roster = null)

        assertEquals(emptyList<String>(), sent.rosterTerms)
        assertEquals(listOf(CS1, CS2, CS3), sent.courses.map { it.courseNo })
    }

    @Test
    fun `a roster may leave out a course hidden here, but not one whose lookup failed`() {
        val roster = listOf(CS1, CS2, CS3)

        assertEquals(listOf(TERM), upload(listOf(fetched(CS1), fetched(CS2)), roster, hidden = setOf(CS3)).rosterTerms)
        assertEquals(emptyList<String>(), upload(listOf(fetched(CS1), fetched(CS2)), roster).rosterTerms)
    }

    @Test
    fun `a past term rebuilt from the final transcript goes as its roster, once every class's lookup answered`() {
        val report = ScoreReport(
            student = "",
            currentTerm = "",
            rankings = emptyList(),
            courses = listOf(
                CourseGrade(term = TERM, code = CS1, name = CS1, status = GradeStatus.GRADED),
                CourseGrade(term = TERM, code = CS2, name = CS2, status = GradeStatus.GRADED),
            ),
            creditSummary = CreditSummary.EMPTY,
        )
        val timed = { no: String -> Course.fromSchedule(no, no, schedule = mapOf(2 to listOf("3", "4"))) }
        fun rebuilt(vararg looked: LookedUpCourse) =
            TranscriptTerms.rebuiltPastTerm(looked.toList(), report, TERM, cached = emptyList(), moodleNos = emptyList(), waitingAdds = emptySet())

        val answered = rebuilt(LookedUpCourse(CS1, timed(CS1), answered = true), LookedUpCourse(CS2, timed(CS2), answered = true))
        val waiting = rebuilt(LookedUpCourse(CS1, timed(CS1), answered = true), LookedUpCourse(CS2, null, answered = false))

        assertEquals(listOf(TERM), upload(answered.courses, answered.roster).rosterTerms)
        assertEquals(emptyList<String>(), upload(waiting.courses, waiting.roster).rosterTerms)
    }

    /**
     * The backend enrolls every course a roster lists. A copy merged down from the server that
     * course selection's answer does not list was dropped here or never enrolled: listed, it
     * would be enrolled again, and its reminders with it. A course added here by hand stays, as
     * the backend never drops a hand-added one.
     */
    @Test
    fun `a roster leaves out the merged copies it does not list, and keeps a course added here`() {
        val courses = listOf(fetched(CS1), mergedCopy(CS2), mergedCopy(CS3), addedHere(CS4))

        val roster = upload(courses, roster = listOf(CS1, CS2))
        val plain = upload(courses, roster = null)

        assertEquals(listOf(CS1, CS2, CS4), roster.courses.map { it.courseNo })
        assertEquals(listOf(CS1, CS2, CS3, CS4), plain.courses.map { it.courseNo })
    }

    private companion object {
        const val TERM = "1151"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
        const val CS3 = "XC0000003"
        const val CS4 = "XD0000004"
    }
}
