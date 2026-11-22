package org.ntust.app.tigerduck.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.clubs.ClubActivityKind
import org.ntust.app.tigerduck.clubs.OneDay
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.RankingStats
import org.ntust.app.tigerduck.shared.timeline.CourseChange
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.io.File
import java.time.LocalDate

/** The demo account's course changes, transcript and clubs (DemoFeatureDataTests on iOS, tigerduck-app#1049). */
class DemoFeaturesTest {

    private val thursday = LocalDate.parse("2026-11-26")

    private fun shipped(lang: String = DemoFixture.LANG_EN) = DemoFixture.parse(File("src/main/assets/demo.json").readText(), lang)

    @Test
    fun `a weekday's next date counts today in`() {
        assertEquals(thursday, DemoFeatures.nextDate(4, thursday))
        assertEquals(LocalDate.parse("2026-12-02"), DemoFeatures.nextDate(3, thursday))
        assertEquals(LocalDate.parse("2026-11-30"), DemoFeatures.nextDate(1, thursday))
        assertNull(DemoFeatures.nextDate(8, thursday))
    }

    @Test
    fun `the course changes are the feed's body, each row on its coming class day`() {
        val changes = shipped().courseChanges!!
        val feed = CourseChanges.decode(DemoFeatures.courseChangeFeed("1151", changes, thursday))
        assertEquals("1151", feed.term)
        val wednesday = LocalDate.parse("2026-12-02")
        val exam = feed.changes("CS3005301", wednesday)
        assertEquals(setOf(CourseChange.Kind.ASSESSMENT, CourseChange.Kind.ROOM), exam.map { it.kind }.toSet())
        assertEquals("期中考", exam.single { it.kind == CourseChange.Kind.ASSESSMENT }.noteZh)
        assertEquals("Midterm exam", exam.single { it.kind == CourseChange.Kind.ASSESSMENT }.noteEn)
        assertEquals("TR-313", exam.single { it.kind == CourseChange.Kind.ROOM }.room)
        assertEquals(listOf("6", "7"), exam.first().periods)
        assertEquals(listOf(CourseChange.Kind.ONLINE), feed.changes("CS3204301", thursday).map { it.kind })
        assertEquals("Make-up class", feed.changes("EE2004301", thursday).single().noteEn)
        val courseNos = shipped().courses!!.map { it.courseNo }
        assertTrue("every change is a course on the timetable", changes.all { it.courseNo in courseNos })
    }

    @Test
    fun `the transcript places each term back from the current one, with the credits it adds up to`() {
        val fixture = shipped()
        val enrolled = fixture.courses!!.sumOf { it.credits.toDouble() }.toFloat()
        val report = DemoFeatures.scoreReport(fixture.scores!!, "1151", enrolled)!!
        assertEquals("Demo Student", report.student)
        assertEquals("1151", report.currentTerm)
        assertEquals(setOf("1131", "1132", "1141", "1142"), report.rankings.map { it.term }.toSet())
        assertEquals(report.rankings.map { it.term }.toSet(), report.courses.map { it.term }.toSet())
        val last = report.rankings.single { it.term == "1142" }
        assertEquals(RankingStats(12, 22, 3.57), last.semester)
        assertEquals(RankingStats(9, 16, 3.72), last.cumulative)
        assertEquals(58f, report.creditSummary.earned.total)
        assertEquals(12f, report.creditSummary.enrolled.total)
        assertEquals(70f, report.creditSummary.total.inPerson)
        val passed = report.courses.filter { it.grade == "通過" }
        assertTrue(passed.isNotEmpty() && passed.all { it.status == GradeStatus.PASS_FAIL_GRADED })
        assertTrue(report.courses.filter { it.grade != "通過" }.all { it.status == GradeStatus.GRADED })
        assertEquals("numbered within each term from 1", 1, report.courses.first().index)
        assertEquals("微積分（一）", DemoFeatures.scoreReport(shipped(DemoFixture.LANG_ZH).scores!!, "1151", enrolled)!!.courses.first().name)
    }

    @Test
    fun `terms count back past a summer term, and a cumulative rank left out is the term's`() {
        val scores = DemoScores(
            "S",
            listOf(DemoScores.Term(1, RankingStats(1, 2, 4.0), null, emptyList()), DemoScores.Term(2, RankingStats(3, 4, 3.0), null, emptyList())),
        )
        val report = DemoFeatures.scoreReport(scores, "114H", 0f)!!
        assertEquals(listOf("1142", "1141"), report.rankings.map { it.term })
        assertEquals(RankingStats(1, 2, 4.0), report.rankings.first().cumulative)
        assertNull("no current term, no report", DemoFeatures.scoreReport(scores, "", 0f))
    }

    @Test
    fun `the clubs are the directory's cards, and their activities fall on the coming weekday`() {
        val clubs = shipped().clubs!!
        assertEquals(listOf(990001, 990002), clubs.map { it.id })
        assertTrue(clubs.all { it.subscribed })
        val card = DemoFeatures.card(clubs.first())
        assertEquals("開源技術開發研究社", card.name)
        assertEquals("Open Source Technology Development Research Club", card.enName)
        assertEquals("學藝性", card.attribute)
        val meeting = DemoFeatures.activities(clubs.first(), thursday).single()
        assertEquals(OneDay("2026-12-02", 19 * 60, 21 * 60), meeting.oneDay)
        assertEquals("開源技術開發研究社", meeting.clubName)
        assertEquals("Start-of-term general meeting", meeting.name)
        assertEquals("TR-214", meeting.location)
        assertEquals(ClubActivityKind.Meeting, meeting.kind)
        val monday = DemoFeatures.activities(clubs.last(), LocalDate.parse("2026-11-24")).single()
        assertEquals("a Monday activity seen on a Tuesday is next Monday's", "2026-11-30", monday.date)
        assertEquals("期初社員大會", DemoFeatures.activities(shipped(DemoFixture.LANG_ZH).clubs!!.first(), thursday).single().name)
    }
}
