// Pins where a tap on the status-bar chip row lands when the promotion page is
// missing.
//
// MagicOS 10 ships no activity for ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS,
// so on an Honor X6d 5G the row was a tap that opened nothing. Any phone that
// has the page hides the fallback, and that is most phones a developer or CI
// runs on, so the missing page is staged here instead.

package org.ntust.app.tigerduck.notification

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// API 36: below it the chip row has no settings page to open at all.
@Config(application = Application::class, sdk = [Build.VERSION_CODES.BAKLAVA])
class SystemPermissionsSettingsFallbackTest {

    private lateinit var context: Application
    private lateinit var systemPermissions: SystemPermissions

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Otherwise Robolectric "starts" any intent, whether or not an
        // activity would handle it, and no launch can fail.
        shadowOf(context).checkActivities(true)
        systemPermissions = SystemPermissions(context)
    }

    /** Gives [action] a settings activity to resolve to. */
    private fun handled(action: String) {
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(action),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "com.android.settings.Settings"
                }
            },
        )
    }

    private fun nextStarted(): Intent? = shadowOf(context).nextStartedActivity

    @Test
    fun `a missing promotion page falls back to the app's notification page`() {
        handled(Settings.ACTION_APP_NOTIFICATION_SETTINGS)

        assertTrue(systemPermissions.openSettings(AppPermission.PROMOTED_NOTIFICATIONS))

        val started = nextStarted()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started?.action)
        assertEquals(context.packageName, started?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull("one page, not two", nextStarted())
    }

    @Test
    fun `the promotion page opens when it exists`() {
        // ColorOS 16.0.5: this is the page with the switch on it.
        handled(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        handled(Settings.ACTION_APP_NOTIFICATION_SETTINGS)

        assertTrue(systemPermissions.openSettings(AppPermission.PROMOTED_NOTIFICATIONS))

        val started = nextStarted()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, started?.action)
        assertEquals(context.packageName, started?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull("no fallback after a page opened", nextStarted())
    }

    @Test
    fun `with neither page there is nothing to open`() {
        assertFalse(systemPermissions.openSettings(AppPermission.PROMOTED_NOTIFICATIONS))
        assertNull(nextStarted())
    }

    @Test
    fun `only the chip row falls back to the notification page`() {
        // The exact-alarm page missing is not something the notification page
        // can stand in for.
        handled(Settings.ACTION_APP_NOTIFICATION_SETTINGS)

        assertFalse(systemPermissions.openSettings(AppPermission.EXACT_ALARM))
        assertNull(nextStarted())
    }
}
