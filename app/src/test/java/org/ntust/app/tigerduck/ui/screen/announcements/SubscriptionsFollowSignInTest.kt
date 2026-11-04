package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.SECOND
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V4
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.LoadState
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState

/**
 * PR G's parked window: a subscription page the sign-out did not close, as one kept on another
 * tab's back stack, belonged to the departed student until its next edit or read. It showed their
 * rules, a result of theirs the page heard of after the next sign-in could still show, and a read
 * of theirs that failed showed as the next student's load failure. The page follows
 * `AccountSession.changes` now, and starts over at each sign-out and sign-in. The app's saves run
 * on their own thread here, as on the phone, so the page hears of an answer a moment after it
 * lands.
 */
class SubscriptionsFollowSignInTest {

    @get:Rule val page = SubscriptionPage(savesOnTheirOwnThread = true)

    private fun SubscriptionSettingsViewModel.shown() = state.value.rules.map { it.name to it.enabled }

    /** Every request that went with the second student's session. */
    private fun sentAsSecond() = page.backend.sent.filter { it.authorization == "Bearer $SECOND" }.map { it.toString() }

    /** The page shows the second student's rules, read under their session, and nothing else went with it. */
    private fun SubscriptionSettingsViewModel.showsTheNextStudents(rules: List<Pair<String?, Boolean>>) {
        page.eventually("the page shows the second student's rules") {
            state.value.loadState == LoadState.Loaded && shown() == rules
        }
        assertEquals(listOf(V4_READ), sentAsSecond().distinct())
    }

    @Test
    fun `the departed student's rules leave the page at the sign-out, and the next student's show at the sign-in`() {
        val vm = page.open(V4, "Scholarships", "Lectures")

        page.signOut()

        page.eventually("the departed student's rules leave the page") { vm.shown().isEmpty() }
        page.secondSignsIn("Grants")
        vm.showsTheNextStudents(listOf("Grants" to true))
    }

    @Test
    fun `a save's answer the page hears of after the next sign-in never shows`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        // The save lands for the first student while the page's thread is busy, which hears of it
        // only after the next sign-in.
        val letGo = page.holdMain()
        put.release()
        waitFor("the save's answer lands") { page.saves.probe.answersHandled.get() == 1 }
        page.nextStudentSignsIn("Grants", heard = false)
        val shown = page.shownFromNow(vm)

        letGo()
        page.onMain { }

        assertEquals(
            "the departed student's saved rules never show after the sign-in",
            emptyList<List<Pair<String?, Boolean>>>(),
            shown.filter { it.saveState == SaveState.Saved }.map { state -> state.rules.map { it.name to it.enabled } },
        )
        vm.showsTheNextStudents(listOf("Grants" to true))
    }

    @Test
    fun `a read of the departed student's that fails after the next sign-in is not the next student's failure`() {
        val vm = page.open(V4, "Scholarships")
        val read = page.backend.hold(V4_READ)
        page.onMain { vm.load() }
        read.awaitArrival()

        page.nextStudentSignsIn("Grants")
        read.release(503 to """{"error":"internal_error","message":"down"}""")

        vm.showsTheNextStudents(listOf("Grants" to true))
        page.onMain { }
        assertEquals(LoadState.Loaded, vm.state.value.loadState)
    }

    /** Waits on the test's thread, the page's being held, until [condition] holds. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(5)
        }
    }

    private companion object {
        const val V4_READ = "GET /v4/bulletin-subscriptions"
        const val V4_SAVE = "PUT /v4/bulletin-subscriptions"
    }
}
