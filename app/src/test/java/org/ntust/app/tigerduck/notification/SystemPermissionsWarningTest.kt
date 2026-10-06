// Pins which permissions the resume-time warning popup lists.
//
// The popup is where the user ticks 以後不再提醒, and that tick is the only
// thing that stops HyperOS raising the notification prompt on every launch.
// So anyone who has said no must reach it — not only someone who once had
// the permission on and turned it off — and the tick must not outlive the
// next time the user turns the permission back on. A refusal is shown once,
// though, not on every return to the foreground.

package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
// API 33 for the same reason as SystemPermissionsLaunchPromptTest: below it
// POST_NOTIFICATIONS does not exist and nothing here would be tested.
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class SystemPermissionsWarningTest {

    private lateinit var context: Application
    private lateinit var systemPermissions: SystemPermissions

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        systemPermissions = SystemPermissions(context)
    }

    private fun grantNotifications() =
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun denyNotifications() =
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun warnsAboutNotifications() =
        AppPermission.NOTIFICATIONS in systemPermissions.revokedOrDeclinedUnmuted()

    @Test
    fun `notifications turned down at launch are warned about though never granted`() {
        // Denied on the onboarding page, then again at launch: never granted.
        denyNotifications()
        systemPermissions.recordLaunchPromptResult(granted = false)

        assertEquals(listOf(AppPermission.NOTIFICATIONS), systemPermissions.revokedOrDeclinedUnmuted())
    }

    @Test
    fun `a launch refusal is warned about once, not again after the popup is closed`() {
        // The popup re-reads its list on every ON_RESUME. Keyed on a stored
        // flag it came back after each trip to another app until muted.
        denyNotifications()
        systemPermissions.recordLaunchPromptResult(granted = false)
        systemPermissions.dismissRefusalWarnings()

        assertFalse(warnsAboutNotifications())
    }

    @Test
    fun `a refusal on an in-app surface does not raise the popup`() {
        // The 通知權限設定 arrival prompt, the onboarding card, the 公告訂閱
        // button: the user is already looking at the permission there.
        denyNotifications()
        systemPermissions.recordDeclined(AppPermission.NOTIFICATIONS)

        assertFalse(warnsAboutNotifications())
    }

    @Test
    fun `notifications never granted and never turned down are not warned about`() {
        // An OS upgrade can introduce the permission ungranted. Nobody asked
        // the user, so there is nothing to warn them about.
        denyNotifications()

        assertFalse(warnsAboutNotifications())
    }

    @Test
    fun `turning notifications back on clears the mute`() {
        grantNotifications()
        systemPermissions.recordCurrentGrants()
        denyNotifications()
        systemPermissions.recordCurrentGrants()
        systemPermissions.setMuted(AppPermission.NOTIFICATIONS, true)
        assertTrue(
            "a mute on a permission that is off stays",
            systemPermissions.isMuted(AppPermission.NOTIFICATIONS),
        )

        grantNotifications()
        systemPermissions.recordCurrentGrants()

        assertFalse(systemPermissions.isMuted(AppPermission.NOTIFICATIONS))
    }

    @Test
    fun `a mute does not silence a revoke after the permission was turned back on`() {
        grantNotifications()
        systemPermissions.recordCurrentGrants()
        denyNotifications()
        systemPermissions.setMuted(AppPermission.NOTIFICATIONS, true)
        assertFalse("muted while off", warnsAboutNotifications())

        // Back on from the system settings, then off again months later.
        grantNotifications()
        systemPermissions.recordCurrentGrants()
        denyNotifications()

        assertEquals(listOf(AppPermission.NOTIFICATIONS), systemPermissions.revokedOrDeclinedUnmuted())
    }

    @Test
    fun `a background-restriction mute survives the OS lifting the restriction`() {
        // Battery managers restrict and unrestrict apps on their own; that is
        // not the user turning the permission back on.
        val activityManager = shadowOf(context.getSystemService(ActivityManager::class.java))
        activityManager.setBackgroundRestricted(true)
        systemPermissions.setMuted(AppPermission.BATTERY_OPTIMIZATION, true)

        activityManager.setBackgroundRestricted(false)
        systemPermissions.recordCurrentGrants()

        assertTrue(systemPermissions.isMuted(AppPermission.BATTERY_OPTIMIZATION))
    }

    // --- the chip grant earlier builds assumed on ColorOS 16 --------------

    private fun chipPrefs() =
        context.getSharedPreferences("chip_grant_test", Context.MODE_PRIVATE)

    @Test
    fun `a chip grant assumed on ColorOS is forgotten so the popup cannot claim it`() {
        // Earlier builds reported the chip granted on every ColorOS 16 phone
        // without asking the platform, and banked it. On a Reno 11 with the
        // switch at its default, off, the popup would then say the chip was
        // "previously enabled".
        val prefs = chipPrefs()
        prefs.edit().putBoolean(GRANTED_CHIP_KEY, true).commit()

        SystemPermissions.forgetInferredChipGrant(prefs, chipWasAssumed = true)

        assertFalse(prefs.getBoolean(GRANTED_CHIP_KEY, false))
    }

    @Test
    fun `a chip grant the platform reported is kept`() {
        // A Pixel or a Galaxy was always asked, so its flag is real.
        val prefs = chipPrefs()
        prefs.edit().putBoolean(GRANTED_CHIP_KEY, true).commit()

        SystemPermissions.forgetInferredChipGrant(prefs, chipWasAssumed = false)

        assertTrue(prefs.getBoolean(GRANTED_CHIP_KEY, false))
    }

    @Test
    fun `the assumed chip grant is forgotten once, not on every launch`() {
        // After the sweep the chip is banked again from a real reading, and a
        // later turn-off of the switch must still be warned about.
        val prefs = chipPrefs()
        SystemPermissions.forgetInferredChipGrant(prefs, chipWasAssumed = true)
        prefs.edit().putBoolean(GRANTED_CHIP_KEY, true).commit()

        SystemPermissions.forgetInferredChipGrant(prefs, chipWasAssumed = true)

        assertTrue(prefs.getBoolean(GRANTED_CHIP_KEY, false))
    }

    private companion object {
        /** The stored key itself, so a rename that strands old flags fails here. */
        const val GRANTED_CHIP_KEY = "granted_PROMOTED_NOTIFICATIONS"
    }
}
