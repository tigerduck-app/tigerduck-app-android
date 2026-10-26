package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What TalkBack says for the course detail's icon buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseDetailA11yTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the Open in Moodle button says what it does`() {
        var opened = false
        composeRule.setContent {
            Column {
                CourseDetailContent(
                    course = Course(courseNo = "CS1001301", courseName = "Calculus"),
                    title = "Calculus",
                    classroom = "TR-313",
                    timeRange = null,
                    assignments = emptyList(),
                    onRename = {},
                    onOpenInMoodle = { opened = true },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(
                RuntimeEnvironment.getApplication().getString(R.string.a11y_course_detail_open_moodle)
            )
            .assertHasClickAction()
            .performClick()

        assertTrue(opened)
    }
}
