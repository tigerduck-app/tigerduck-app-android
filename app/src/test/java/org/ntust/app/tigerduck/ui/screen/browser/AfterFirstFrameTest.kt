package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The browser makes its WebView after the window's first frame, so that frame is never held back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AfterFirstFrameTest {

    @Test
    fun `the work waits for the window's first frame, then runs once`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        var runs = 0

        afterFirstFrame(activity.window.decorView) { runs++ }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ran before the window drew", 0, runs)

        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, runs)

        activity.window.decorView.invalidate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ran again on a later frame", 1, runs)
    }
}
