package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.MainActivityHost
import org.ntust.app.tigerduck.appicon.AppIcon
import org.ntust.app.tigerduck.appicon.LauncherIcons
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The reauth notice opens the app by MainActivityHost's name, not by the launcher's entry: that entry
 * is one app icon's alias ([LauncherIcons]), which a later pick of another icon turns off, and a
 * tap of a notice posted before it would then open nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReauthIntentTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the reauth notice opens MainActivityHost whichever icon is shown`() {
        AppIcon.entries.forEach { icon ->
            LauncherIcons(context).show(icon)

            val intent = context.reauthIntent()

            assertEquals(icon.name, MainActivityHost::class.java.name, intent.component?.className)
            assertNotNull(icon.name, context.packageManager.resolveActivity(intent, 0))
        }
    }
}
