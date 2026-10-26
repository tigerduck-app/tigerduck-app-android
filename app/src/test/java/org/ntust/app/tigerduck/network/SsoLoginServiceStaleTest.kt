package org.ntust.app.tigerduck.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.auth.InMemoryStoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The [SsoLoginService.ensureServiceLogin] overload a caller passes `isStillWanted` to: a login no
 * longer wanted once it holds the lock makes no request at all. The service URL is a closed port
 * on the loopback address, so a login that does go out fails at once instead of reaching anyone.
 */
class SsoLoginServiceStaleTest {

    private val context = JvmContext()
    private val sso = SsoLoginService(
        NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context))),
        InMemoryStoredPasswordLatch(),
    )
    private val closedPort = "http://127.0.0.1:9/classroom_user/classroom_usecondition.aspx"

    @Test
    fun `a login its caller no longer wants returns false without a request`() = runTest {
        assertFalse(sso.ensureServiceLogin(closedPort, "B00000000", "not-a-password", isStillWanted = { false }))
    }

    @Test
    fun `a login still wanted goes out`() = runTest {
        try {
            sso.ensureServiceLogin(closedPort, "B00000000", "not-a-password", isStillWanted = { true })
            fail("expected the login to reach the closed port")
        } catch (_: SsoLoginError.NetworkError) {
        }
    }
}
