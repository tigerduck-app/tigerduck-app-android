package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.CourseSyllabusBreakdown.Part
import java.io.IOException

/**
 * A course's syllabus from QueryCourse's `coursedetials`, as the iPhone reads it (tigerduck-app#885):
 * its fields trimmed, the grading and teaching shares drawn from them, and one fetch per term,
 * course and language. Fixtures are made up.
 */
class CourseSyllabusTest {

    private val labels = CourseSyllabusBreakdown.TeachingLabels(
        lecture = "Lecture", groupDiscussion = "Group discussion", caseStudy = "Case study",
        practical = "Practical exercises", other = "Others",
    )

    // Decoding and fields

    @Test
    fun `a syllabus shows its fields trimmed, with the textbook and references together`() {
        val syllabus = CourseSyllabus.decode(GRADED)!!

        assertEquals("期中考：30%\n期末考：40%\n作業：30%", syllabus.grading)
        assertEquals("1.池塘概論\n2.鴨子如何游泳", syllabus.outline)
        assertEquals("講授與實作", syllabus.content)
        assertEquals("鴨子入門，第二版\n\n池塘手冊", syllabus.references)
        assertNull(syllabus.notes)
        assertNull(syllabus.objectives)
        assertNull(syllabus.website)
        assertTrue(syllabus.hasContent(labels))
    }

    @Test
    fun `an alliance course shows its code, its note and the other school's page`() {
        val syllabus = CourseSyllabus.decode(ALLIANCE)!!

        assertEquals("ZZZ0001", syllabus.outline)
        assertEquals("Partner school course", syllabus.content)
        assertEquals("https://example.edu.tw/syllabus?code=ZZZ0001", syllabus.website)
        assertNull(syllabus.grading)
        assertNull(CourseSyllabusBreakdown.teaching(syllabus, labels))
        assertTrue(syllabus.hasContent(labels))
    }

    @Test
    fun `a syllabus with only blank fields has nothing to show`() {
        assertFalse(CourseSyllabus.decode(BLANK)!!.hasContent(labels))
    }

    @Test
    fun `a website that is not http or https is no website`() {
        val syllabus = CourseSyllabus.decode("""[{"CourseURL":"javascript:alert(1)","CourseObject":"x"}]""")!!

        assertNull(syllabus.website)
    }

    @Test
    fun `an empty answer and the exception for an unknown course are no syllabus, and other errors throw`() {
        assertNull(CourseSyllabus.decode("[]"))
        assertNull(CourseSyllabus.decode("""{"ClassName":"System.IndexOutOfRangeException","Message":"Index was outside the bounds of the array."}"""))
        assertThrows(JSONException::class.java) { CourseSyllabus.decode("""{"ClassName":"System.Exception"}""") }
        assertThrows(JSONException::class.java) { CourseSyllabus.decode("<html>") }
    }

    @Test
    fun `a number reads as its digits and a field of another type as blank`() {
        val syllabus = CourseSyllabus.decode(NUMERIC)!!

        assertNull(syllabus.grading)
        assertEquals("3", syllabus.notes)
        assertEquals("Counting ducks.", syllabus.outline)
        assertEquals(
            listOf(Part("Lecture", 60.0), Part("Case study", 12.5), Part("Practical exercises", 27.5)),
            CourseSyllabusBreakdown.teaching(syllabus, labels),
        )
    }

    // Grading

    @Test
    fun `grading lines split at line breaks and list separators`() {
        assertEquals(
            listOf(Part("期中考", 30.0), Part("期末考", 40.0), Part("作業", 30.0)),
            CourseSyllabusBreakdown.grading("期中考 30%\r\n期末考 40%\n作業 30%"),
        )
        assertEquals(
            listOf(Part("Midterm", 30.0), Part("Final", 40.0), Part("Homework", 20.0), Part("Quiz", 5.0), Part("Lab", 5.0)),
            CourseSyllabusBreakdown.grading("Midterm 30%; Final - 40%, Homework=20%，Quiz 5%、Lab (5%)"),
        )
    }

    @Test
    fun `full-width colons and percent signs, decimals and list numbers read as parts`() {
        assertEquals(
            listOf(Part("期中考", 30.0), Part("報告", 12.5), Part("出席", 57.5)),
            CourseSyllabusBreakdown.grading("1.期中考：30％\n2.報告：12.5%\n(3) 出席: 57.5 %"),
        )
    }

    @Test
    fun `a part without a percentage, a range, a label too long or no label leaves the text as written`() {
        assertNull(CourseSyllabusBreakdown.grading("期中考 30%\n課堂參與"))
        assertNull(CourseSyllabusBreakdown.grading("期中考 30%以上"))
        assertNull(CourseSyllabusBreakdown.grading("100%"))
        assertNull(CourseSyllabusBreakdown.grading("期中考 30%\nMidterm 40-60%"))
        assertNull(CourseSyllabusBreakdown.grading("Final 20%～30%\n作業 50%"))
        assertNull(CourseSyllabusBreakdown.grading("  \n "))
        val twenty = "鴨".repeat(20)
        assertEquals(listOf(Part(twenty, 100.0)), CourseSyllabusBreakdown.grading("$twenty 100%"))
        assertNull(CourseSyllabusBreakdown.grading("${twenty}子 100%"))
    }

    @Test
    fun `a decimal with no leading digit, or a range with a dash or a bracket, leaves the text as written`() {
        assertNull(CourseSyllabusBreakdown.grading("Quiz .5%\nFinal 99.5%"))
        assertNull(CourseSyllabusBreakdown.grading("期中考 30%\nFinal 40–60%"))
        assertNull(CourseSyllabusBreakdown.grading("期中考 30%\nFinal 40 - (60%)"))
        assertEquals(
            listOf(Part("Others", null, detail = "Pond visit 10–20%")),
            CourseSyllabusBreakdown.teaching(teaching(lecture = "0", other = "Pond visit 10–20%"), labels),
        )
    }

    // Teaching

    @Test
    fun `an Instruction value is trimmed and loses its percent sign, and anything else counts as 0`() {
        assertEquals(60.0, CourseSyllabusBreakdown.share(" 60 "), 0.0)
        assertEquals(20.0, CourseSyllabusBreakdown.share("20%"), 0.0)
        assertEquals(12.5, CourseSyllabusBreakdown.share("12.5 ％"), 0.0)
        assertEquals(0.0, CourseSyllabusBreakdown.share(""), 0.0)
        assertEquals(0.0, CourseSyllabusBreakdown.share(null), 0.0)
        assertEquals(0.0, CourseSyllabusBreakdown.share("half"), 0.0)
        assertEquals(0.0, CourseSyllabusBreakdown.share("-5"), 0.0)
        assertEquals(0.0, CourseSyllabusBreakdown.share("nan"), 0.0)
        assertEquals(0.0, CourseSyllabusBreakdown.share("NaN"), 0.0)
        assertEquals(0.0, CourseSyllabusBreakdown.share("Infinity"), 0.0)
    }

    @Test
    fun `teaching methods above 0 show in order`() {
        assertEquals(
            listOf(Part("Lecture", 60.0), Part("Case study", 20.0), Part("Practical exercises", 20.0)),
            CourseSyllabusBreakdown.teaching(CourseSyllabus.decode(GRADED)!!, labels),
        )
    }

    @Test
    fun `Others joins the bar when its text ends in a percentage, and is a row of text otherwise`() {
        assertEquals(
            listOf(Part("Lecture", 80.0), Part("Others", 20.0, detail = "Pond visit")),
            CourseSyllabusBreakdown.teaching(teaching(lecture = "80", other = "Pond visit 20%"), labels),
        )
        assertEquals(
            listOf(Part("Others", null, detail = "Pond visit")),
            CourseSyllabusBreakdown.teaching(teaching(lecture = "0", other = " Pond visit "), labels),
        )
        assertNull(CourseSyllabusBreakdown.teaching(teaching(lecture = "0", other = " "), labels))
        assertEquals(
            listOf(Part("Others", null, detail = "Pond visit 10-20%")),
            CourseSyllabusBreakdown.teaching(teaching(lecture = "0", other = "Pond visit 10-20%"), labels),
        )
        val prose = "Ducks swim across the pond. ".repeat(8) + "10%"
        assertEquals(
            listOf(Part("Others", null, detail = prose)),
            CourseSyllabusBreakdown.teaching(teaching(lecture = "0", other = prose), labels),
        )
    }

    private fun teaching(lecture: String, other: String) = CourseSyllabus(
        courseGrading = null, courseObject = null, courseContent = null, courseTextbook = null,
        courseRefbook = null, courseNote = null, coreAbility = null, courseUrl = null,
        instruction1 = lecture, instruction2 = "", instruction3 = null, instruction4 = "0", instructionOther = other,
    )

    // The kept fetch

    @Test
    fun `a syllabus is fetched once per term, course and language, even when there is none`() = runTest {
        var calls = 0
        val store = CourseSyllabusStore(backgroundScope) { _, _, _ -> calls++; "[]" }

        assertNull(store.syllabus("1151", "ZZ1001301", "zh"))
        assertNull(store.syllabus("1151", "ZZ1001301", "zh"))
        assertEquals(1, calls)

        store.syllabus("1151", "ZZ1001301", "en")
        assertEquals(2, calls)
    }

    @Test
    fun `callers asking at once for one syllabus share its fetch`() = runTest {
        var calls = 0
        val answer = CompletableDeferred<String>()
        val store = CourseSyllabusStore(backgroundScope) { _, _, _ -> calls++; answer.await() }

        val first = async { store.syllabus("1151", "ZZ1001301", "zh") }
        val second = async { store.syllabus("1151", "ZZ1001301", "zh") }
        runCurrent()
        answer.complete(GRADED)

        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `a failed fetch is not kept`() = runTest {
        var fail = true
        val store = CourseSyllabusStore(backgroundScope) { _, _, _ -> if (fail) throw IOException("offline") else GRADED }

        assertTrue(runCatching { store.syllabus("1151", "ZZ1001301", "zh") }.exceptionOrNull() is IOException)

        fail = false
        assertEquals("講授與實作", store.syllabus("1151", "ZZ1001301", "zh")?.content)
    }

    /** A failed fetch whose callers have all left is let go of all the same, for the next to fetch again. */
    @Test
    fun `a fetch that fails after its callers left is not kept`() = runTest {
        var calls = 0
        val answer = CompletableDeferred<String>()
        val store = CourseSyllabusStore(backgroundScope) { _, _, _ ->
            calls++
            if (calls == 1) {
                answer.await()
                throw IOException("offline")
            }
            GRADED
        }
        val leaving = async { store.syllabus("1151", "ZZ1001301", "zh") }
        runCurrent()
        leaving.cancel()
        answer.complete("")
        runCurrent()

        assertEquals("講授與實作", store.syllabus("1151", "ZZ1001301", "zh")?.content)
        assertEquals(2, calls)
    }

    private companion object {
        const val GRADED = """[{"Semester":"1151","CourseNo":"ZZ1001301","CourseName":"鴨子程式設計","CoreAbility":"  ","CourseURL":"",""" +
            """"CourseObject":"1.池塘概論\r\n2.鴨子如何游泳\r\n","CourseContent":"講授與實作\r\n","CourseTextbook":"鴨子入門，第二版",""" +
            """"CourseRefbook":" 池塘手冊 ","CourseNote":null,"CourseGrading":"期中考：30%\r\n期末考：40%\r\n作業：30%",""" +
            """"Instruction_1":"60","Instruction_2":"0","Instruction_3":" 20 ","Instruction_4":"20%","Instruction_other":""}]"""
        const val ALLIANCE = """[{"Semester":"1151","CourseNo":"3N0000701","CourseName":"Pond Studies","CoreAbility":"",""" +
            """"CourseURL":"https://example.edu.tw/syllabus?code=ZZZ0001","CourseObject":"ZZZ0001","CourseContent":"Partner school course",""" +
            """"CourseTextbook":null,"CourseRefbook":null,"CourseNote":null,"CourseGrading":null}]"""
        const val BLANK = """[{"Semester":"1151","CourseNo":"ZZ1002301","CoreAbility":"","CourseURL":" ","CourseObject":null,""" +
            """"CourseContent":"\r\n","CourseTextbook":null,"CourseRefbook":null,"CourseNote":null,"CourseGrading":null}]"""
        const val NUMERIC = """[{"Semester":"1151","CourseNo":"ZZ1003301","CourseGrading":true,"CourseObject":"Counting ducks.",""" +
            """"CourseContent":null,"CourseNote":3,"CoreAbility":"","CourseURL":"","Instruction_1":60,"Instruction_2":0,""" +
            """"Instruction_3":12.5,"Instruction_4":"27.5","Instruction_other":null}]"""
    }
}
