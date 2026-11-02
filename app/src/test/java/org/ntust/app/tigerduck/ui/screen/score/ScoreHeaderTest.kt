package org.ntust.app.tigerduck.ui.screen.score

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderAtTop
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderScrollsAway
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertRowEndsWith
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.title
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scrolling columns' header, which the class table and the library share: the column's first
 * child, ending in the sync dot here.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en-w400dp-h500dp")
class ScoreHeaderTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density get() = composeRule.activity.resources.displayMetrics.density
    private fun text(id: Int) = composeRule.activity.getString(id)

    private val courses = (1..40).map { CourseGrade(term = "1141", code = "CS10$it", name = "Course $it") }

    private fun viewModel() = ScoreViewModel(
        authState = MutableStateFlow(true),
        accountChanges = MutableStateFlow(true),
        storedStudentId = { "B00015999" },
        storedPassword = { "not-a-password" },
        cachedSnapshot = { null },
        fetchReport = { _, _, _ -> ScoreReport.EMPTY.copy(student = "B00015999", courses = courses) },
        isOnline = { true },
        isDemo = { false },
        strings = { "string $it" },
        rosterCourses = { emptyList() },
    )

    @Before
    fun signIn() = ServerStatusTracker.setSignedIn(true)

    @After
    fun signOut() = ServerStatusTracker.setSignedIn(false)

    private fun show() {
        val vm = viewModel()
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) { ScoreScreen(viewModel = vm, signInBanner = {}) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the title and sync dot show at the top of the page`() {
        show()
        composeRule.title(text(R.string.feature_score)).assertIsDisplayed()
        val dot = composeRule.onNodeWithContentDescription(text(R.string.sync_status_ok)).assertIsDisplayed()
        composeRule.assertHeaderAtTop(text(R.string.feature_score), density)
        composeRule.assertRowEndsWith(dot, density)
    }

    @Test
    fun `the header scrolls away with the page`() {
        show()
        composeRule.assertHeaderScrollsAway(text(R.string.feature_score), density)
    }
}
