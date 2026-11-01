package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.CourseSyllabus
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What a course's syllabus page shows, as the iPhone's (tigerduck-app#885). The syllabus is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseSyllabusContentTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()

    private fun show(syllabus: CourseSyllabus) {
        composeRule.setContent { MaterialTheme { CourseSyllabusContent(syllabus, browserPreference = "") } }
        composeRule.waitForIdle()
    }

    private fun count(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun syllabus(
        grading: String? = null,
        outline: String? = null,
        content: String? = null,
        textbook: String? = null,
        url: String? = null,
        lecture: String? = null,
        practical: String? = null,
    ) = CourseSyllabus(
        courseGrading = grading, courseObject = outline, courseContent = content, courseTextbook = textbook,
        courseRefbook = null, courseNote = null, coreAbility = null, courseUrl = url,
        instruction1 = lecture, instruction2 = null, instruction3 = null, instruction4 = practical, instructionOther = null,
    )

    @Test
    fun `grading and teaching methods show as shares, each with its percentage`() {
        show(syllabus(grading = "期中考：30%\n期末考：40%\n作業：30%", lecture = "60", practical = "40"))

        assertEquals(1, count("期中考"))
        assertEquals(2, count(percentText(30.0)))
        assertEquals(1, count(context.getString(R.string.course_detail_syllabus_lecture)))
        assertEquals(1, count(percentText(60.0)))
        assertEquals(1, count(context.getString(R.string.course_detail_syllabus_method)))
    }

    @Test
    fun `grading that is not a list of shares shows as written`() {
        show(syllabus(grading = "期中考 30%\n課堂參與"))

        assertEquals(1, count("期中考 30%\n課堂參與"))
    }

    @Test
    fun `the details open on a tap, and a section left blank does not show`() {
        show(syllabus(outline = "1.池塘概論", content = "講授與實作", textbook = "鴨子入門"))

        assertEquals(1, count("1.池塘概論"))
        assertEquals(0, count("講授與實作"))
        assertEquals(0, count(context.getString(R.string.course_detail_syllabus_grading)))
        assertEquals(0, count(context.getString(R.string.course_detail_syllabus_notes)))

        composeRule.onNodeWithText(context.getString(R.string.course_detail_syllabus_content)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, count("講授與實作"))
        assertEquals(0, count("鴨子入門"))
    }

    @Test
    fun `a course with a website shows a row for it, and one without does not`() {
        show(syllabus(outline = "ZZZ0001", url = "https://example.edu.tw/syllabus?code=ZZZ0001"))

        assertEquals(1, count(context.getString(R.string.course_detail_syllabus_website)))
    }
}
