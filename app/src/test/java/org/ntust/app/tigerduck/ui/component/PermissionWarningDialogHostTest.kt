// Drives the warning popup down the path a launch-prompt refusal takes on a
// phone: the system prompt covers the activity (pause), MainActivity's
// callback hands the answer to SystemPermissions.recordLaunchPromptResult,
// and the activity resumes — where the popup's ON_RESUME re-check has to pick
// the refusal up. SystemPermissionsWarningTest pins which permissions are
// listed; this pins that the popup actually shows them, and what each way of
// leaving it does. Without it, a break between the callback and the popup
// would leave the user unable to reach 以後不再提醒 while every list test
// still passed.

package org.ntust.app.tigerduck.ui.component

import android.Manifest
import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
// API 33, the first with POST_NOTIFICATIONS.
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class PermissionWarningDialogHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Application
    private lateinit var systemPermissions: SystemPermissions

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        systemPermissions = SystemPermissions(context)
        composeRule.setContent { PermissionWarningDialogHost(systemPermissions) }
    }

    private fun text(@StringRes id: Int) = context.getString(id)

    private fun popup() = composeRule.onNodeWithText(text(R.string.permission_warning_title))

    /** The launch prompt covers the activity, is answered, and closes. */
    private fun answerLaunchPrompt(granted: Boolean) {
        val scenario = composeRule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.STARTED)
        if (granted) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        systemPermissions.recordLaunchPromptResult(granted)
        scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
    }

    /** The user goes to another app, or the system settings, and comes back. */
    private fun leaveAndReturn() {
        val scenario = composeRule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
    }

    @Test
    fun `a refused launch prompt brings up the popup once the prompt closes`() {
        popup().assertDoesNotExist()

        answerLaunchPrompt(granted = false)

        popup().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.permission_notifications_name)).assertIsDisplayed()
    }

    @Test
    fun `an allowed launch prompt brings up nothing`() {
        answerLaunchPrompt(granted = true)

        popup().assertDoesNotExist()
    }

    @Test
    fun `closing the popup keeps that refusal from coming back on the next resume`() {
        answerLaunchPrompt(granted = false)

        composeRule.onNodeWithText(text(R.string.permission_warning_action_later)).performClick()
        popup().assertDoesNotExist()

        leaveAndReturn()
        popup().assertDoesNotExist()
    }

    @Test
    fun `ticking the mute closes the popup and stops the launch prompt`() {
        answerLaunchPrompt(granted = false)

        composeRule.onNodeWithText(text(R.string.permission_warning_mute_item)).performClick()

        popup().assertDoesNotExist()
        assertFalse(systemPermissions.shouldRequestNotificationsAtLaunch())
    }

    @Test
    fun `go to settings opens the notification page, and coming back with them on clears the popup`() {
        answerLaunchPrompt(granted = false)

        composeRule.onNodeWithText(text(R.string.action_go_to_settings)).performClick()
        assertEquals(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            shadowOf(context).nextStartedActivity?.action,
        )

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        leaveAndReturn()
        popup().assertDoesNotExist()
    }
}
