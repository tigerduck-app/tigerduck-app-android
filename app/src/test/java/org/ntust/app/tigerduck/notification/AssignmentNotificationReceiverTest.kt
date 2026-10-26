package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the receiver does with the check's verdict, on the default API 33 with its notification permission. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AssignmentNotificationReceiverTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context)
    private var posted = 0

    @Before
    fun setUp() {
        prefs.notifyAssignments = true
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun remind(verdict: suspend () -> ReminderVerdict) =
        AssignmentNotificationReceiver.remindIfStillDue(context, prefs, verdict) { posted++ }

    @Test
    fun `a reminder still due is posted`() = runTest {
        assertEquals(ReminderVerdict.Show, remind { ReminderVerdict.Show })
        assertEquals(1, posted)
    }

    @Test
    fun `a reminder the check drops is not posted`() = runTest {
        val dropped = ReminderVerdict.Drop(ReminderDropReason.SUBMITTED_ON_MOODLE)

        assertEquals(dropped, remind { dropped })
        assertEquals(0, posted)
    }

    @Test
    fun `a check that runs past its budget is not posted`() = runTest {
        assertEquals(
            ReminderVerdict.Drop(ReminderDropReason.TIMED_OUT),
            remind {
                delay(9_000)
                ReminderVerdict.Show
            },
        )
        assertEquals(0, posted)
    }

    @Test
    fun `reminders turned off while the check ran are not posted`() = runTest {
        val decided = remind {
            prefs.notifyAssignments = false
            ReminderVerdict.Show
        }

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.TURNED_OFF), decided)
        assertEquals(0, posted)
    }

    @Test
    fun `notifications denied while the check ran are not posted`() = runTest {
        val decided = remind {
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            ReminderVerdict.Show
        }

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.TURNED_OFF), decided)
        assertEquals(0, posted)
    }
}
