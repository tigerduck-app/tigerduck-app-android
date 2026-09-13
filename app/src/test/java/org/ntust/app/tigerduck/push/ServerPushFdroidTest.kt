package org.ntust.app.tigerduck.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [effectiveServerPushOptedOut] — the rule behind
 * [PushRegistrationService.isServerPushOptedOut]: fdroid never registers for
 * push at all, so it must read as opted out regardless of what the user has
 * stored (a fresh install defaults the stored flag to "opted in").
 *
 * [PushRegistrationService] can't be constructed here — [PushApiClient] and
 * [org.ntust.app.tigerduck.auth.AuthTokenManager] make real OkHttp /
 * Android-Keystore calls, and this module has neither Robolectric nor a
 * mocking library (same constraint [PushRegistrationServiceOptOutTest]
 * documents for [applyOptOutIfAccepted]). So this exercises the pure rule
 * the getter delegates to, with the flavor passed explicitly — see
 * [org.ntust.app.tigerduck.data.preferences.effectiveCloudSyncEnabled] for
 * why call sites take it as a parameter instead of reading
 * [org.ntust.app.tigerduck.BuildConfig.FLAVOR] directly.
 */
class ServerPushFdroidTest {

    @Test
    fun `fdroid reads server push as opted out even when the stored preference is opted in`() {
        assertTrue(
            "fdroid never registers for push, whatever is stored",
            effectiveServerPushOptedOut(storedOptOut = false, flavor = "fdroid"),
        )
    }

    @Test
    fun `fdroid reads server push as opted out when the stored preference is also opted out`() {
        assertTrue(effectiveServerPushOptedOut(storedOptOut = true, flavor = "fdroid"))
    }

    @Test
    fun `play honors a stored opt-in`() {
        assertFalse(effectiveServerPushOptedOut(storedOptOut = false, flavor = "play"))
    }

    @Test
    fun `play honors a stored opt-out`() {
        assertTrue(effectiveServerPushOptedOut(storedOptOut = true, flavor = "play"))
    }
}
