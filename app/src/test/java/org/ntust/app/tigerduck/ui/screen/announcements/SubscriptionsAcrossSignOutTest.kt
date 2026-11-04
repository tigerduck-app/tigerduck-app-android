package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.FIRST
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.SECOND
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V4
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.LoadState
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState

/**
 * A subscription page the sign-out did not close, as one kept on another tab's back stack: it
 * starts over at the sign-out and at the next sign-in, and reads the next student's rules then
 * (`SubscriptionsFollowSignInTest`). Nothing of the departed student's is sent under the next
 * student's session, and no answer to a save of theirs touches the page, which shows the next
 * student's rules by then. An edit made before the page hears of the sign-out finds it ended.
 */
class SubscriptionsAcrossSignOutTest {

    @get:Rule val page = SubscriptionPage()

    private fun SubscriptionSettingsViewModel.shown() = state.value.rules.map { it.name to it.enabled }

    /** What the page shows: its rules, whether they loaded, and how the last save went. */
    private fun SubscriptionSettingsViewModel.seen() =
        page.onMain { state.value.let { Triple(it.rules, it.loadState, it.saveState) } }

    /** Every request that went with the second student's session. */
    private fun sentAsSecond() = page.backend.sent.filter { it.authorization == "Bearer $SECOND" }.map { it.toString() }

    /** Each rule a PUT of the list sent: its `client_id` and whether it is enabled. */
    private fun SubscriptionBackend.Sent.rules(): List<Pair<String, Boolean>> {
        val rules = json.getJSONArray("rules")
        return (0 until rules.length()).map(rules::getJSONObject).map { it.getString("client_id") to it.getBoolean("enabled") }
    }

    /**
     * The page shows the second student's rules, read under their session with [read], and
     * nothing else went with it.
     */
    private fun SubscriptionSettingsViewModel.showsTheNextStudents(
        rules: List<Pair<String?, Boolean>>,
        read: String = V4_READ,
    ) {
        page.eventually("the page shows the second student's rules") {
            state.value.loadState == LoadState.Loaded && shown() == rules
        }
        assertEquals(listOf(read), sentAsSecond())
    }

    /**
     * After a save of the first student's is answered across the sign-out: the page shows the
     * second student's rules, read at their sign-in, the answer leaves it so, and nothing else
     * went with the second student's session.
     */
    private fun SubscriptionSettingsViewModel.showsTheNextStudentsPastTheAnswer() {
        showsTheNextStudents(listOf("Grants" to true))
        val shownThen = seen()
        // The app's saves' answer to it.
        page.awaitSaves(1)
        assertEquals("the answer left the page alone", shownThen, seen())
        assertEquals(listOf(V4_READ), sentAsSecond())
    }

    @Test
    fun `on v4 a save across a sign-out leaves the next student's page alone`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()

        page.nextStudentSignsIn("Grants")
        put.release()

        vm.showsTheNextStudentsPastTheAnswer()
    }

    @Test
    fun `on v4 a save waiting behind one across a sign-out is never sent`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        page.onMain { vm.toggleEnabled(1) }

        page.nextStudentSignsIn("Grants")
        put.release()

        // The first save's answer is handled, and the waiting save dropped, unsent.
        vm.showsTheNextStudentsPastTheAnswer()
        assertEquals(1, page.saves.probe.dropped.get())
        assertEquals(1, page.savesBegun())
        assertEquals(1, page.backend.sent(V4_SAVE).size)
        assertEquals(listOf("Scholarships" to false, "Lectures" to true), page.backend.held(FIRST))
        assertEquals(listOf("Grants" to true), page.backend.held(SECOND))
    }

    @Test
    fun `on v4 a refused save across a sign-out puts nothing back`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.deleteRule(0) }
        put.awaitArrival()

        page.nextStudentSignsIn("Grants")
        put.release(503 to "{}")

        vm.showsTheNextStudentsPastTheAnswer()
    }

    /**
     * The first student's save is still out when the next student signs in, and the kept page reads
     * their rules, which they edit. The first answer comes back after that: it leaves the page
     * alone, and the next student's save carries their edited list whole. No save ever carries an
     * empty list nobody made.
     */
    @Test
    fun `on v4 a departed student's save answered after the next one's edit leaves that edit whole`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        page.nextStudentSignsIn("Grants", "Talks")
        val secondIds = page.backend.clientIds(SECOND)
        page.eventually("the page reads the second student's rules") {
            vm.state.value.loadState == LoadState.Loaded && vm.shown() == listOf("Grants" to true, "Talks" to true)
        }
        page.onMain { vm.toggleEnabled(1) }

        put.release()
        page.eventually("both saves are answered") { page.backend.events.count { it.startsWith("< $V4_SAVE") } == 2 }

        val puts = page.backend.sent(V4_SAVE)
        assertEquals("a save of an empty list", emptyList<String?>(), puts.filter { it.rules().isEmpty() }.map { it.authorization })
        assertEquals(listOf(listOf(secondIds[0] to true, secondIds[1] to false)), puts.filter { it.authorization == "Bearer $SECOND" }.map { it.rules() })
        page.eventually("the second student's save shows") { vm.state.value.saveState == SaveState.Saved }
        assertEquals(listOf("Grants" to true, "Talks" to false), vm.shown())
        assertEquals(listOf("Grants" to true, "Talks" to false), page.backend.held(SECOND))
        assertEquals(listOf("Scholarships" to false, "Lectures" to true), page.backend.held(FIRST))
    }

    /**
     * The departed student's save is still out when the next student, on the kept page, which read
     * their rules at their sign-in, edits them and reads them again: their save waits behind the
     * departed one's, and the read keeps their newest list.
     */
    @Test
    fun `on v4 a read while the next student's save waits behind a departed one's keeps their newest list`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        page.nextStudentSignsIn("Grants", "Talks")
        page.eventually("the page reads the second student's rules") {
            vm.state.value.loadState == LoadState.Loaded && vm.shown() == listOf("Grants" to true, "Talks" to true)
        }
        page.onMain { vm.toggleEnabled(1) }
        // Every read before this one has been answered, or cut short by the one after it.
        val answered = page.onMain { vm.probe.answersHandled.get() }

        page.onMain { vm.load() }
        page.awaitAnswers(vm, answered + 1)

        assertEquals("the read keeps the newest list", listOf("Grants" to true, "Talks" to false), vm.shown())
        put.release()
        page.eventually("both saves are answered") { page.backend.events.count { it.startsWith("< $V4_SAVE") } == 2 }
        assertEquals(listOf("Grants" to true, "Talks" to false), page.backend.held(SECOND))
    }

    @Test
    fun `on v4 rules read across a sign-out never show`() {
        val read = page.backend.hold(V4_READ)
        val vm = page.open(V4, "Scholarships", shown = false)
        read.awaitArrival()

        page.nextStudentSignsIn("Grants")
        read.release()

        vm.showsTheNextStudents(listOf("Grants" to true), V4_READ)
    }

    /** Made before the page hears of the sign-out, which comes on its thread a moment later. */
    @Test
    fun `on v4 an edit of the rules a sign-out left on the page sends nothing`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val letGo = page.holdMain()
        var left: List<Pair<String?, Boolean>>? = null
        page.queueOnMain {
            vm.toggleEnabled(0)
            vm.deleteRule(1)
            vm.upsertRule(SubscriptionRule(name = "Lectures"), replacingIndex = null)
            left = vm.shown()
        }
        page.nextStudentSignsIn("Grants", heard = false)

        letGo()

        page.eventually("the edits ran") { left != null }
        assertEquals("the first student's rules leave the page at the first edit", emptyList<Pair<String?, Boolean>>(), left)
        page.eventually("the page shows the second student's rules") {
            vm.state.value.loadState == LoadState.Loaded && vm.shown() == listOf("Grants" to true)
        }
        // The edit and the sign-out and sign-in each start the page over, each reading anew.
        assertEquals(listOf(V4_READ), page.backend.routes().drop(1).distinct())
        assertEquals(listOf(V4_READ), sentAsSecond().distinct())
    }

    /** Answered between the sign-out and the next sign-in: it writes nothing, and nothing more is asked. */
    @Test
    fun `on v4 a save answered while no one is signed in leaves the page alone`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        page.signOut()
        page.eventually("the page has started over, with no one to read for") { vm.state.value.loadState is LoadState.Failed }
        val before = vm.seen()

        put.release()

        page.awaitSaves(1)
        assertEquals("the answer left the page alone", before, vm.seen())
        assertEquals(listOf(V4_READ, V4_SAVE), page.backend.routes())
    }

    private companion object {
        const val V4_READ = "GET /v4/bulletin-subscriptions"
        const val V4_SAVE = "PUT /v4/bulletin-subscriptions"
    }
}
