package org.ntust.app.tigerduck.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [effectiveCloudSyncEnabled] — the rule behind
 * [AppPreferences.cloudSyncEnabled]: F-Droid ships without Google Play
 * Services and can never run TigerSync's course-sync / server-push
 * pipeline, whatever the user has stored.
 *
 * [AppPreferences] itself can't be constructed here to test the getter
 * end to end — its constructor calls the real `Context.getSharedPreferences`,
 * and this module has neither Robolectric nor a mocking library (same
 * constraint [PushRegistrationServiceOptOutTest] documents). So this
 * exercises the pure rule the getter delegates to, with the flavor passed
 * explicitly rather than read from [org.ntust.app.tigerduck.BuildConfig.FLAVOR]:
 * `app/src/test` is compiled once per flavor (`testPlayDebugUnitTest`,
 * `testFdroidDebugUnitTest`), so a test asserting a literal answer for
 * `BuildConfig.FLAVOR` would fail under whichever variant it wasn't written
 * for. Taking the flavor as a parameter lets one shared test cover both
 * branches regardless of which variant compiled it.
 */
class AppPreferencesTest {

    @Test
    fun `fdroid reads cloud sync as off even when the stored preference is on`() {
        assertFalse(
            "fdroid can never run the sync pipeline, whatever is stored",
            effectiveCloudSyncEnabled(storedValue = true, flavor = "fdroid"),
        )
    }

    @Test
    fun `fdroid reads cloud sync as off when the stored preference is also off`() {
        assertFalse(effectiveCloudSyncEnabled(storedValue = false, flavor = "fdroid"))
    }

    @Test
    fun `play honors a stored true`() {
        assertTrue(effectiveCloudSyncEnabled(storedValue = true, flavor = "play"))
    }

    @Test
    fun `play honors a stored false`() {
        assertFalse(effectiveCloudSyncEnabled(storedValue = false, flavor = "play"))
    }
}
