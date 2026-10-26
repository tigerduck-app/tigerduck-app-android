package org.ntust.app.tigerduck.widget

import android.app.Application
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The language a widget draws its strings in, on a phone set to Traditional
 * Chinese below API 33, where an in-app language reaches Activities only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.R], qualifiers = "zh-rTW")
class WidgetContextTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `a widget draws in the language the app runs in, not the phone's`() {
        assertEquals("下一堂課", app.getString(R.string.widget_next_class))

        assertEquals("Next class", widgetContext(app, "en").getString(R.string.widget_next_class))
    }

    @Test
    fun `a widget following the system draws in the phone's language`() {
        assertEquals(
            "下一堂課",
            widgetContext(app, AppLanguageManager.SYSTEM).getString(R.string.widget_next_class),
        )
    }
}
