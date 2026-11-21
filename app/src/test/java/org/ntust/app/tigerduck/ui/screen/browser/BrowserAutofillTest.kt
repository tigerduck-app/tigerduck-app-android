package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.view.View
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The phone's autofill stays out of a browser opened at risk, whose pages may be an impostor's. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserAutofillTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `a browser opened at risk keeps the phone's autofill out of its WebView and every field in it`() {
        val web = WebView(context)

        keepAutofillOutAtRisk(web, atRisk = true)

        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, web.importantForAutofill)
        assertFalse(web.isImportantForAutofill)
    }

    @Test
    fun `any other browser's WebView is left as the platform makes it`() {
        val web = WebView(context)
        val made = web.importantForAutofill

        keepAutofillOutAtRisk(web, atRisk = false)

        assertEquals(made, web.importantForAutofill)
    }
}
