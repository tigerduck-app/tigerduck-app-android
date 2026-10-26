package org.ntust.app.tigerduck.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import kotlin.time.Duration.Companion.minutes

/**
 * A short background period costs battery on every phone and a request to
 * the school servers from every install, every period, whether or not anyone
 * is looking. So it is not a setting to reach for: a page refreshes in the
 * background more often than every half hour only when its data goes stale
 * that fast, and it says why here.
 *
 * Adding a page to [FAST_BACKGROUND] is a decision, not a fix for this test.
 */
class RefreshPolicyUsageTest {

    private companion object {
        val FAST = 30.minutes

        /** Pages allowed a background period under [FAST], and why. */
        val FAST_BACKGROUND = mapOf(
            "Home" to "Moodle assignments: a new one, or one just submitted, " +
                "should reach the list and its reminders without the app open",
            "Calendar" to "shows the same Moodle assignments as Home",
        )
    }

    @Test
    fun `only pages that need it refresh in the background more often than every half hour`() {
        val offenders = RefreshPolicies.byPage
            .filter { (page, policy) ->
                val period = policy.background ?: return@filter false
                period < FAST && page !in FAST_BACKGROUND
            }
            .keys
        assertEquals(
            "Background periods under $FAST need an entry in FAST_BACKGROUND, with a reason",
            emptySet<String>(),
            offenders,
        )
    }

    @Test
    fun `pages the worker has no way to refresh ask for no background period`() {
        val offenders = RefreshPolicies.withoutBackgroundRefresh
            .filter { RefreshPolicies.byPage.getValue(it).background != null }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the worker runs as often as the most frequent page asks`() {
        assertEquals(15.minutes, BackgroundSyncWorker.period)
    }
}
