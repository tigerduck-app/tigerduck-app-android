package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A sign-in page with no form says what the school said, as a refusal does. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class MoodleSsoErrorTextTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun `a sign-in page with no form reads as the school's refusal`() {
        assertEquals(
            moodleErrorText(resources, MoodleWebserviceError.SsoLoginRejected("Too many attempts")),
            moodleErrorText(resources, MoodleWebserviceError.SsoLoginFormUnavailable("Too many attempts")),
        )
        assertEquals(
            moodleErrorText(resources, MoodleWebserviceError.SsoLoginRejected(null)),
            moodleErrorText(resources, MoodleWebserviceError.SsoLoginFormUnavailable(null)),
        )
    }
}
