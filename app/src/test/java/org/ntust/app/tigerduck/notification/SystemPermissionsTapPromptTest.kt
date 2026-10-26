// Pins when an "allow notifications" tap may raise the runtime prompt instead
// of opening the settings page.
//
// Once the user has had notifications on and switched them off, or turned the
// prompt down, the OS can answer a request with a silent denial — stock
// Android after two refusals, and HyperOS (POCO C85, HyperOS 3) with
// notifications switched off. A tap that only asked then did nothing at all.

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
// API 33 for the same reason as SystemPermissionsLaunchPromptTest: below it
// POST_NOTIFICATIONS does not exist and nothing here would be tested.
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class SystemPermissionsTapPromptTest {

    private lateinit var context: Application
    private lateinit var systemPermissions: SystemPermissions

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        systemPermissions = SystemPermissions(context)
    }

    @Test
    fun `never granted and never turned down, a tap may prompt`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(systemPermissions.canPromptForNotifications())
    }

    @Test
    fun `switched off after being on, a tap goes to settings`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        systemPermissions.recordCurrentGrants()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(systemPermissions.canPromptForNotifications())
    }

    @Test
    fun `turned down at launch, a tap goes to settings`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        systemPermissions.recordDeclined(AppPermission.NOTIFICATIONS)

        assertFalse(systemPermissions.canPromptForNotifications())
    }

    @Test
    fun `already granted, there is nothing to prompt for`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(systemPermissions.canPromptForNotifications())
    }
}
