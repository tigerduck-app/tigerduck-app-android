package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.FIRST
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.SECOND
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V3
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V4
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.LoadState
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState

/**
 * A subscription page the sign-out did not close, as one kept on another tab's back stack: the
 * rules it shows are the student's who signed out. Nothing of theirs is sent under the next
 * student's session, and no answer to a save of theirs touches the page, which may show the next
 * student's rules by then. An edit of the rules the sign-out left, and a read answered after it,
 * find the page reading the next student's rules instead.
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
        read: String = V3_READ,
    ) {
        page.eventually("the page shows the second student's rules") {
            state.value.loadState == LoadState.Loaded && shown() == rules
        }
        assertEquals(listOf(read), sentAsSecond())
    }

    /**
     * After a save of the first student's is answered across the sign-out ([before] is the page
     * as it stood when the save went): the page is as it was, nothing went with the second
     * student's session, and the next edit finds the rules the first student's and reads the
     * second's.
     */
    private fun SubscriptionSettingsViewModel.leftAloneThenStartsOver(
        before: Triple<List<SubscriptionRule>, LoadState, SaveState>,
        answers: Int,
        read: String,
    ) {
        page.awaitAnswers(this, answers)
        assertEquals("the answer left the page alone", before, seen())
        assertEquals(emptyList<String>(), sentAsSecond())

        page.onMain { toggleEnabled(0) }

        showsTheNextStudents(listOf("Grants" to true), read)
    }

    @Test
    fun `on v3 a rule created across a sign-out leaves the page alone`() {
        val vm = page.open(V3, "Scholarships")
        val post = page.backend.hold("POST /v3/bulletin-subscriptions")
        page.onMain { vm.upsertRule(SubscriptionRule(name = "Lectures"), replacingIndex = null) }
        post.awaitArrival()
        val before = vm.seen()

        page.nextStudentSignsIn("Grants")
        post.release()

        vm.leftAloneThenStartsOver(before, answers = 2, V3_READ)
    }

    @Test
    fun `on v3 a toggle's echo across a sign-out leaves the page alone`() {
        val vm = page.open(V3, "Scholarships")
        val patch = page.backend.hold("PATCH /v3/bulletin-subscriptions/1")
        page.onMain { vm.toggleEnabled(0) }
        patch.awaitArrival()
        val before = vm.seen()

        page.nextStudentSignsIn("Grants")
        patch.release()

        vm.leftAloneThenStartsOver(before, answers = 2, V3_READ)
    }

    @Test
    fun `on v3 a refused delete across a sign-out puts nothing back`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        val delete = page.backend.hold("DELETE /v3/bulletin-subscriptions/1")
        page.onMain { vm.deleteRule(0) }
        delete.awaitArrival()
        val before = vm.seen()

        page.nextStudentSignsIn("Grants")
        delete.release(503 to "{}")

        vm.leftAloneThenStartsOver(before, answers = 2, V3_READ)
    }

    @Test
    fun `on v3 rules read across a sign-out never show`() {
        val read = page.backend.hold(V3_READ)
        val vm = page.open(V3, "Scholarships", shown = false)
        read.awaitArrival()

        page.nextStudentSignsIn("Grants")
        read.release()

        vm.showsTheNextStudents(listOf("Grants" to true))
    }

    @Test
    fun `on v3 an edit of the rules a sign-out left on the page sends nothing`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        page.nextStudentSignsIn("Grants")

        val left = page.onMain {
            vm.toggleEnabled(0)
            vm.deleteRule(1)
            vm.upsertRule(SubscriptionRule(name = "Lectures"), replacingIndex = null)
            vm.shown()
        }

        assertEquals("the first student's rules leave the page at the first edit", emptyList<Pair<String?, Boolean>>(), left)
        vm.showsTheNextStudents(listOf("Grants" to true))
        assertEquals(listOf(V3_READ), page.backend.routes().drop(1).distinct())
    }

    @Test
    fun `on v4 a save across a sign-out leaves the page alone`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        val before = vm.seen()

        page.nextStudentSignsIn("Grants")
        put.release()

        vm.leftAloneThenStartsOver(before, answers = 2, V4_READ)
    }

    @Test
    fun `on v4 a save waiting behind one across a sign-out is never sent`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        page.onMain { vm.toggleEnabled(1) }
        val before = vm.seen()

        page.nextStudentSignsIn("Grants")
        put.release()

        // The read, the first save's answer, and the waiting save, refused before it is sent.
        vm.leftAloneThenStartsOver(before, answers = 3, V4_READ)
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
        val before = vm.seen()

        page.nextStudentSignsIn("Grants")
        put.release(503 to "{}")

        vm.leftAloneThenStartsOver(before, answers = 2, V4_READ)
    }

    /**
     * The first student's save is still out when the next student signs in, edits the kept page,
     * which reads their rules instead, and edits those. The first answer comes back after that:
     * it leaves the page alone, and the next student's save carries their edited list whole.
     * No save ever carries an empty list nobody made.
     */
    @Test
    fun `on v4 a departed student's save answered after the next one's edit leaves that edit whole`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        page.nextStudentSignsIn("Grants", "Talks")
        val secondIds = page.backend.clientIds(SECOND)
        page.onMain { vm.toggleEnabled(0) }
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

    @Test
    fun `on v4 rules read across a sign-out never show`() {
        val read = page.backend.hold(V4_READ)
        val vm = page.open(V4, "Scholarships", shown = false)
        read.awaitArrival()

        page.nextStudentSignsIn("Grants")
        read.release()

        vm.showsTheNextStudents(listOf("Grants" to true), V4_READ)
    }

    @Test
    fun `on v4 an edit of the rules a sign-out left on the page sends nothing`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        page.nextStudentSignsIn("Grants")

        val left = page.onMain {
            vm.toggleEnabled(0)
            vm.deleteRule(1)
            vm.upsertRule(SubscriptionRule(name = "Lectures"), replacingIndex = null)
            vm.shown()
        }

        assertEquals("the first student's rules leave the page at the first edit", emptyList<Pair<String?, Boolean>>(), left)
        vm.showsTheNextStudents(listOf("Grants" to true), V4_READ)
        assertEquals(listOf(V4_READ), page.backend.routes().drop(1).distinct())
    }

    /** Answered between the sign-out and the next sign-in: it writes nothing, and nothing more is asked. */
    @Test
    fun `on v4 a save answered while no one is signed in leaves the page alone`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()
        val before = vm.seen()

        page.manager.logout()
        put.release()

        page.awaitAnswers(vm, 2)
        assertEquals("the answer left the page alone", before, vm.seen())
        assertEquals(listOf(V4_READ, V4_SAVE), page.backend.routes())
    }

    private companion object {
        const val V3_READ = "GET /v3/bulletin-subscriptions"
        const val V4_READ = "GET /v4/bulletin-subscriptions"
        const val V4_SAVE = "PUT /v4/bulletin-subscriptions"
    }
}
