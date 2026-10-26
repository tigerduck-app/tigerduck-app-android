package org.ntust.app.tigerduck.auth

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.network.SsoLoginError
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a sign-in the school refused says: the school's own message where its page had one, as
 * on the iPhone (https://github.com/tigerduck-app/tigerduck-app-android/issues/163).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class SignInErrorMessageTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a refusal with the school's message shows it`() {
        assertEquals(
            "Sign-in failed: Wrong account or password",
            context.signInErrorMessage(SsoLoginError.LoginFailed("Wrong account or password")),
        )
    }

    @Test
    fun `a refusal without one asks to check the account and password`() {
        assertEquals(
            "Sign-in failed. Please verify your account and password.",
            context.signInErrorMessage(SsoLoginError.LoginFailed()),
        )
    }

    @Test
    fun `no connection still says so`() {
        assertEquals(
            "Unable to connect. Please check your network connection.",
            context.signInErrorMessage(SsoLoginError.NetworkError(java.io.IOException("offline"))),
        )
    }
}
