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

/**
 * A subscription page the sign-out did not close, as one kept on another tab's back stack: the
 * rules it shows are the student's who signed out. Nothing of theirs is sent under the next
 * student's session, and no answer to a request of theirs lands on the next student's page.
 * Either finds the page reading the next student's rules instead.
 */
class SubscriptionsAcrossSignOutTest {

    @get:Rule val page = SubscriptionPage()

    private fun SubscriptionSettingsViewModel.shown() = state.value.rules.map { it.name to it.enabled }

    /**
     * The page shows the second student's rules, read under their session with [read], and
     * nothing else went with it. On `/v4` the read may go twice: a save that waited behind one
     * across the sign-out is refused before it is sent, and its refusal starts the page over too.
     */
    private fun SubscriptionSettingsViewModel.showsTheNextStudents(
        rules: List<Pair<String?, Boolean>>,
        read: String = "GET /v3/bulletin-subscriptions",
    ) {
        page.eventually("the page shows the second student's rules") {
            state.value.loadState == LoadState.Loaded && shown() == rules
        }
        val sent = page.backend.sent.filter { it.authorization == "Bearer $SECOND" }.map { it.toString() }
        assertEquals(listOf(read), if (read == V4_READ) sent.distinct() else sent)
    }

    @Test
    fun `on v3 a rule created across a sign-out lands on no page`() {
        val vm = page.open(V3, "Scholarships")
        val post = page.backend.hold("POST /v3/bulletin-subscriptions")
        page.onMain { vm.upsertRule(SubscriptionRule(name = "Lectures"), replacingIndex = null) }
        post.awaitArrival()

        page.nextStudentSignsIn("Grants")
        post.release()

        vm.showsTheNextStudents(listOf("Grants" to true))
    }

    @Test
    fun `on v3 a toggle's echo across a sign-out lands on no page`() {
        val vm = page.open(V3, "Scholarships")
        val patch = page.backend.hold("PATCH /v3/bulletin-subscriptions/1")
        page.onMain { vm.toggleEnabled(0) }
        patch.awaitArrival()

        page.nextStudentSignsIn("Grants")
        patch.release()

        vm.showsTheNextStudents(listOf("Grants" to true))
    }

    @Test
    fun `on v3 a refused delete across a sign-out puts nothing back`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        val delete = page.backend.hold("DELETE /v3/bulletin-subscriptions/1")
        page.onMain { vm.deleteRule(0) }
        delete.awaitArrival()

        page.nextStudentSignsIn("Grants")
        delete.release(503 to "{}")

        vm.showsTheNextStudents(listOf("Grants" to true))
    }

    @Test
    fun `on v3 rules read across a sign-out never show`() {
        val read = page.backend.hold("GET /v3/bulletin-subscriptions")
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
        assertEquals(listOf("GET /v3/bulletin-subscriptions"), page.backend.routes().drop(1).distinct())
    }

    @Test
    fun `on v4 a save across a sign-out lands on no page`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()

        page.nextStudentSignsIn("Grants")
        put.release()

        vm.showsTheNextStudents(listOf("Grants" to true), V4_READ)
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

        vm.showsTheNextStudents(listOf("Grants" to true), V4_READ)
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

        vm.showsTheNextStudents(listOf("Grants" to true), V4_READ)
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

    /** Answered between the sign-out and the next sign-in: nobody's rules show, and the page offers to read again. */
    @Test
    fun `on v4 a save answered while no one is signed in leaves the page empty`() {
        val vm = page.open(V4, "Scholarships")
        val put = page.backend.hold(V4_SAVE)
        page.onMain { vm.toggleEnabled(0) }
        put.awaitArrival()

        page.manager.logout()
        put.release()

        page.eventually("the read under the ended sign-in fails") { vm.state.value.loadState is LoadState.Failed }
        assertEquals(emptyList<Pair<String?, Boolean>>(), vm.shown())
        assertEquals(listOf(V4_READ, V4_SAVE), page.backend.routes())
    }

    private companion object {
        const val V4_READ = "GET /v4/bulletin-subscriptions"
        const val V4_SAVE = "PUT /v4/bulletin-subscriptions"
    }
}
