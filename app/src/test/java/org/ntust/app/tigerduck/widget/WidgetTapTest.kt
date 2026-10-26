package org.ntust.app.tigerduck.widget

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.startRouteOf
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What MainActivity opens for a tap on one of the app's home-screen widgets. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetTapTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    /**
     * [intent] as MainActivity receives it from the widget. Glance gives an
     * intent that has no data a `glance-action:` URI of its own, to keep its
     * PendingIntents apart (ApplyAction's createUniqueUri), and leaves one
     * that has data as it is.
     */
    private fun delivered(intent: Intent): Intent = intent.apply {
        if (data == null) data = Uri.parse("glance-action:/CALLBACK?appWidgetId=1&viewId=2&extraData=")
    }

    @Test
    fun `a tap on a class table widget opens the class table`() {
        assertEquals(Screen.ClassTable.route, startRouteOf(delivered(BaseClassTableWidget.tapIntent(context))))
    }

    @Test
    fun `a tap on the Library shortcut opens the Library, through its feature check`() {
        assertEquals(
            LibraryShortcutWidget.ROUTE_SENTINEL,
            startRouteOf(delivered(LibraryShortcutWidget.tapIntent(context))),
        )
    }

    @Test
    fun `each widget opens its tigerduck link in MainActivity`() {
        val classTable = BaseClassTableWidget.tapIntent(context)
        assertEquals(AppLink.ClassTable, AppLink.parse(classTable.data))
        assertEquals(MainActivity::class.java.name, classTable.component?.className)

        val library = LibraryShortcutWidget.tapIntent(context)
        assertEquals(AppLink.Library, AppLink.parse(library.data))
        assertEquals(MainActivity::class.java.name, library.component?.className)
    }
}
