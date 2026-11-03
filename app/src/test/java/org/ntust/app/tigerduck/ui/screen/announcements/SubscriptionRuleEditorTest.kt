package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.FIRST
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V3
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V4
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState

/**
 * The rule editor's Done and Delete act on the rule the editor was opened for, wherever it stands
 * in the list by then: a refusal can put a rule back before it, or take it away. The editor names
 * its rule by its ids (`client_id` on `/v4`, the backend's id on `/v3`), never by its place.
 */
class SubscriptionRuleEditorTest {

    // Pins /v3's requests and rules until Z1 and Z2 delete them: a base ending in /v3 stays there.
    @get:Rule
    val speaksV3 = SpeaksV3()

    @get:Rule val page = SubscriptionPage()

    private fun SubscriptionSettingsViewModel.shown() = state.value.rules.map { it.name to it.enabled }

    /**
     * `[A, B]`; A is deleted and, while that save is out, B's editor opens, B being first by then.
     * The delete is refused and A comes back before B. Done must replace B, not A: replacing A
     * would leave two rules with B's `client_id`, which the backend refuses, and B's edit lost.
     */
    @Test
    fun `on v4 Done after a refused delete replaces the edited rule, not the one put back before it`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val delete = page.backend.hold(PUT)
        page.onMain { vm.deleteRule(0) }
        delete.awaitArrival()
        val edited = vm.state.value.rules[0]
        assertEquals("Lectures", edited.name)
        delete.release(503 to "{}")
        page.awaitSaves(1)
        assertEquals(listOf("Scholarships" to true, "Lectures" to true), vm.shown())

        page.onMain { vm.upsertRule(edited.copy(name = "Talks"), replacingIndex = 0) }
        page.awaitSaves(2)

        assertEquals(SaveState.Saved, vm.state.value.saveState)
        assertEquals(listOf("Scholarships" to true, "Talks" to true), vm.shown())
        assertEquals(listOf("Scholarships" to true, "Talks" to true), page.backend.held(FIRST))
    }

    /** As above on `/v3`: the PATCH went to B's id, and its answer replaces B on the page, not A. */
    @Test
    fun `on v3 Done after a refused delete replaces the edited rule, not the one put back before it`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        val delete = page.backend.hold("DELETE /v3/bulletin-subscriptions/1")
        page.onMain { vm.deleteRule(0) }
        delete.awaitArrival()
        val edited = vm.state.value.rules[0]
        delete.release(503 to "{}")
        page.awaitAnswers(vm, 2)
        assertEquals(listOf("Scholarships" to true, "Lectures" to true), vm.shown())

        page.onMain { vm.upsertRule(edited.copy(name = "Talks"), replacingIndex = 0) }
        page.awaitAnswers(vm, 3)

        assertEquals(listOf("Scholarships" to true, "Talks" to true), vm.shown())
        assertEquals("PATCH /v3/bulletin-subscriptions/2", page.backend.routes().last())
        assertEquals(listOf("Scholarships" to true, "Talks" to true), page.backend.held(FIRST))
    }

    /**
     * A rule is added and its editor opened while that save is out; the save is refused, which
     * takes the rule away. Done puts it back as the student left it, with the same `client_id`:
     * that is the student's latest intent.
     */
    @Test
    fun `on v4 Done on a rule a refusal took away puts it back as the student left it`() {
        val vm = page.open(V4, "Scholarships")
        val add = page.backend.hold(PUT)
        page.onMain { vm.upsertRule(SubscriptionRule(name = "Lectures"), replacingIndex = null) }
        add.awaitArrival()
        val edited = vm.state.value.rules[1]
        add.release(503 to "{}")
        page.awaitSaves(1)
        assertEquals(listOf("Scholarships" to true), vm.shown())

        page.onMain { vm.upsertRule(edited.copy(name = "Talks"), replacingIndex = 1) }
        page.awaitSaves(2)

        assertEquals(listOf("Scholarships" to true, "Talks" to true), vm.shown())
        assertEquals(listOf("Scholarships" to true, "Talks" to true), page.backend.held(FIRST))
        assertEquals(edited.clientId, page.backend.clientIds(FIRST)[1])
    }

    /** A rule added and saved after its editor opened, so it has the backend's id by Done: Done replaces it. */
    @Test
    fun `on v4 Done on a rule saved since its editor opened replaces it`() {
        val vm = page.open(V4, "Scholarships")
        val add = page.backend.hold(PUT)
        page.onMain { vm.upsertRule(SubscriptionRule(name = "Lectures"), replacingIndex = null) }
        add.awaitArrival()
        val edited = vm.state.value.rules[1]
        add.release()
        page.awaitSaves(1)
        assertNotNull("the answer gave the rule the backend's id", vm.state.value.rules[1].id)

        page.onMain { vm.upsertRule(edited.copy(name = "Talks"), replacingIndex = 1) }
        page.awaitSaves(2)

        assertEquals(SaveState.Saved, vm.state.value.saveState)
        assertEquals(listOf("Scholarships" to true, "Talks" to true), vm.shown())
        assertEquals(listOf("Scholarships" to true, "Talks" to true), page.backend.held(FIRST))
    }

    /** As in the first test, but the editor's Delete: it deletes B, not A, which came back before it. */
    @Test
    fun `on v4 the editor's Delete after a refused delete deletes the edited rule`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val delete = page.backend.hold(PUT)
        page.onMain { vm.deleteRule(0) }
        delete.awaitArrival()
        val edited = vm.state.value.rules[0]
        delete.release(503 to "{}")
        page.awaitSaves(1)

        page.onMain { vm.deleteRule(edited) }
        page.awaitSaves(2)

        assertEquals(listOf("Scholarships" to true), vm.shown())
        assertEquals(listOf("Scholarships" to true), page.backend.held(FIRST))
    }

    /** As above on `/v3`: the DELETE goes to B's id. */
    @Test
    fun `on v3 the editor's Delete after a refused delete deletes the edited rule`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        val delete = page.backend.hold("DELETE /v3/bulletin-subscriptions/1")
        page.onMain { vm.deleteRule(0) }
        delete.awaitArrival()
        val edited = vm.state.value.rules[0]
        delete.release(503 to "{}")
        page.awaitAnswers(vm, 2)

        page.onMain { vm.deleteRule(edited) }
        page.awaitAnswers(vm, 3)

        assertEquals("DELETE /v3/bulletin-subscriptions/2", page.backend.routes().last())
        assertEquals(listOf("Scholarships" to true), vm.shown())
        assertEquals(listOf("Scholarships" to true), page.backend.held(FIRST))
    }

    private companion object {
        const val PUT = "PUT /v4/bulletin-subscriptions"
    }
}
