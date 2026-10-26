package org.ntust.app.tigerduck.browser

import android.app.Application
import android.webkit.WebView
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Letting the WebView darken pages, only where it can. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserDarkeningTest {

    @Test
    fun `a WebView without algorithmic darkening is not asked for it, and darkens nothing`() {
        // Asked all the same, it would throw: AndroidX WebKit's call needs the feature.
        val settings = WebView(RuntimeEnvironment.getApplication()).settings

        assertFalse(BrowserColors.allowDarkening(settings, supported = false))
    }
}
