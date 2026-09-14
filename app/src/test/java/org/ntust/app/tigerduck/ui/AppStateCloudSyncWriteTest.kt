package org.ntust.app.tigerduck.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [effectiveCloudSyncWrite] — the rule behind
 * [AppState.cloudSyncEnabled]'s setter. `AppPreferences.cloudSyncEnabled`'s
 * getter already makes every *reader* agree that fdroid can never run the
 * sync pipeline, whatever is stored (see `effectiveCloudSyncEnabled`); this
 * is the matching gate for the *writer*, so a caller that sets
 * [AppState.cloudSyncEnabled] to `true` on fdroid can't make every reader of
 * it see sync as on regardless.
 *
 * [AppState] itself can't be constructed here to test the setter end to
 * end — its constructor needs a real `Context`-backed Hilt graph — so this
 * exercises the pure rule the setter delegates to, with the flavor passed
 * explicitly rather than read from `BuildConfig.FLAVOR`, the same reason
 * `AppPreferencesTest` does.
 */
class AppStateCloudSyncWriteTest {

    @Test
    fun `a request to turn cloud sync on is refused on fdroid`() {
        assertFalse(
            "fdroid can never run the sync pipeline, whatever a caller asks for",
            effectiveCloudSyncWrite(requested = true, flavor = "fdroid"),
        )
    }

    @Test
    fun `a request to turn cloud sync off always takes effect`() {
        assertFalse(effectiveCloudSyncWrite(requested = false, flavor = "fdroid"))
        assertFalse(effectiveCloudSyncWrite(requested = false, flavor = "play"))
    }

    @Test
    fun `play honors a request to turn cloud sync on`() {
        assertTrue(effectiveCloudSyncWrite(requested = true, flavor = "play"))
    }
}
