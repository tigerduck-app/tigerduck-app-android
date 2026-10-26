package org.ntust.app.tigerduck.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [keepsPreviousDefaultTabs] — which installs keep the pre-2.3.0
 * default bar (Home, Class table, Calendar) now that the default is Home,
 * Class table, Mail. [AppState] can't be constructed here (see
 * `AppStateCloudSyncWriteTest`), so this exercises the rule its init applies.
 */
class AppStateDefaultTabsTest {

    @Test
    fun `an existing untouched bar keeps calendar`() {
        assertTrue(keepsPreviousDefaultTabs(hasCompletedOnboarding = true, hasStoredTabs = false))
    }

    @Test
    fun `a customized bar is left alone`() {
        assertFalse(keepsPreviousDefaultTabs(hasCompletedOnboarding = true, hasStoredTabs = true))
    }

    @Test
    fun `a fresh install gets the new default`() {
        assertFalse(keepsPreviousDefaultTabs(hasCompletedOnboarding = false, hasStoredTabs = false))
    }
}
