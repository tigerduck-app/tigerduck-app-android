package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MailtoLinkRoutingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `signed in, a confirmed mailto link opens School Mail compose and no other app`() {
        val composed = mutableListOf<String>()
        openBodyLink(context, "mailto:office@mail.ntust.edu.tw?subject=Hi", "inApp", composeMailto = { composed += it })
        assertEquals(listOf("mailto:office@mail.ntust.edu.tw?subject=Hi"), composed)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `with no school account to send from, it goes to another mail app`() {
        openBodyLink(context, "mailto:office@mail.ntust.edu.tw", "inApp", composeMailto = null)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("mailto:office@mail.ntust.edu.tw", started.dataString)
    }
}
