package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.util.replaceIosArg
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A malformed answer is named on screen by what this app wrote of it, never by what it quotes
 * of the server: a sign-in page's address and the start of its HTML are for the log alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class MoodleMalformedErrorTextTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    private fun expected(detail: String) =
        resources.getString(R.string.error_moodle_malformed_response_format).replaceIosArg(1, detail)

    @Test
    fun `an unexpected sign-in page shows neither its address nor its HTML`() {
        val text = moodleErrorText(
            resources,
            MoodleWebserviceError.MalformedResponse(
                "Unexpected OIDC page: https://sso.example.test/account/notice — body preview=<!DOCTYPE html><title>Notice</title>",
            ),
        )
        assertFalse(text, "https://" in text)
        assertFalse(text, "<!DOCTYPE" in text)
        assertEquals(expected("Unexpected OIDC page"), text)
    }

    @Test
    fun `a detail that quotes nothing is shown whole`() {
        assertEquals(
            expected("SSO login form missing anti-forgery token"),
            moodleErrorText(resources, MoodleWebserviceError.MalformedResponse("SSO login form missing anti-forgery token")),
        )
    }
}
