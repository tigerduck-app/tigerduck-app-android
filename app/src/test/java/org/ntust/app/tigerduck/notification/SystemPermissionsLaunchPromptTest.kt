// Pins when a cold start may raise the system notification prompt on its own.
//
// Stock Android stops showing that prompt after the user turns it down
// twice, so a launch that asked regardless still looked polite there.
// HyperOS (POCO C85, HyperOS 3) shows it on every launch, so the app's own
// "以後不再提醒" choice is the only thing that can stop it — and the launch
// prompt used to ignore it.

package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.Application
import android.os.Build
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
// The SDK is pinned because, with no merged manifest to read a target from,
// Robolectric falls back to API 23 — where POST_NOTIFICATIONS does not exist,
// every case reads "not applicable", and nothing here is tested.
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class SystemPermissionsLaunchPromptTest {

    private lateinit var context: Application
    private lateinit var systemPermissions: SystemPermissions

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        systemPermissions = SystemPermissions(context)
    }

    @Test
    fun `a denied permission the user has not muted is asked for at launch`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(systemPermissions.shouldRequestNotificationsAtLaunch())
    }

    @Test
    fun `a denied permission the user muted is not asked for at launch`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        systemPermissions.setMuted(AppPermission.NOTIFICATIONS, true)

        assertFalse(systemPermissions.shouldRequestNotificationsAtLaunch())
    }

    @Test
    fun `a granted permission is not asked for at launch`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(systemPermissions.shouldRequestNotificationsAtLaunch())
    }

    // Below API 33 there is no runtime permission to ask for. MainActivity
    // used to return early on the SDK level itself; that check now lives here.
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun `nothing is asked for at launch below API 33`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(systemPermissions.shouldRequestNotificationsAtLaunch())
    }
}
