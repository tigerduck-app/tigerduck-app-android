package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.emptyclassroom.FakeCour01Transport
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderAtTop
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.title
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import org.ntust.app.tigerduck.util.testSignIn

/** A lazy list's header whose sync dot comes and goes: the room site is only read signed in. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class EmptyClassroomHeaderTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density get() = composeRule.activity.resources.displayMetrics.density
    private fun text(id: Int) = composeRule.activity.getString(id)

    private class Environment(signedIn: Boolean) : FinderEnvironment {
        override var pickedBuilding: String? = null
        override val signedIn = MutableStateFlow(signedIn)
        override val accountChanges get() = signedIn
        override val coursesChanged = MutableStateFlow(0)
        override val calendarChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override fun now(): LocalDateTime = LocalDateTime.of(2026, 10, 14, 10, 5)
        override fun calendar() = AcademicCalendar.EMPTY
        override fun account() = "B00015999"
        override fun signIn() = testSignIn()
        override suspend fun courses() = emptyList<Course>()
        override fun credentials() = ClassroomUsageService.Credentials("B00015999", "not-a-password")
        override fun isCurrent(credentials: ClassroomUsageService.Credentials) = true
        override fun isOffline(error: Throwable) = false
    }

    private fun show(signedIn: Boolean) {
        val service = ClassroomUsageService(FakeCour01Transport(), { _, _ -> true }, {}, { null }, { 0L }, Dispatchers.Unconfined)
        val vm = EmptyClassroomViewModel(service, Environment(signedIn))
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                EmptyClassroomScreen(onOpenSignInSettings = {}, viewModel = vm)
            }
        }
        composeRule.waitForIdle()
    }

    private fun dots() = listOf(R.string.bulletin_push_status_unknown, R.string.sync_status_ok, R.string.sync_status_failed, R.string.sync_status_syncing)
        .sumOf { composeRule.onAllNodes(hasContentDescription(text(it)) and hasClickAction()).fetchSemanticsNodes().size }

    @Test
    fun `signed in, the title sits at the top with the room site's dot`() {
        show(signedIn = true)
        composeRule.title(text(R.string.feature_empty_classroom)).assertIsDisplayed()
        composeRule.assertHeaderAtTop(text(R.string.feature_empty_classroom), density)
        assertEquals(1, dots())
    }

    @Test
    fun `signed out, the title sits there alone`() {
        show(signedIn = false)
        composeRule.title(text(R.string.feature_empty_classroom)).assertIsDisplayed()
        composeRule.assertHeaderAtTop(text(R.string.feature_empty_classroom), density)
        assertEquals(0, dots())
    }
}
