package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.FIRST
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V3
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState

/**
 * The bulletin subscription settings page's saves. On `/v3` each edit is its own request to its
 * rule (POST, PATCH, DELETE), as it always was, and these pin it.
 */
class SubscriptionSettingsViewModelTest {

    @get:Rule val page = SubscriptionPage()

    private fun SubscriptionSettingsViewModel.shown() = state.value.rules.map { it.name to it.enabled }

    @Test
    fun `on v3 the page shows the device's rules with the session's token`() {
        val vm = page.open(V3, "Scholarships", "Lectures")

        assertEquals(listOf("Scholarships" to true, "Lectures" to true), vm.shown())
        assertEquals(listOf(1, 2), vm.state.value.rules.map { it.id })
        val read = page.backend.sent("GET /v3/bulletin-subscriptions").single()
        assertEquals("Bearer $FIRST", read.authorization)
        assertEquals(null, read.body)
    }

    @Test
    fun `on v3 a new rule is posted, and shows once the backend has it`() {
        val vm = page.open(V3, "Scholarships")
        val rule = SubscriptionRule(name = "Lectures", orgs = listOf("office"), tags = listOf("lecture"), mode = "OR")

        page.onMain { vm.upsertRule(rule, replacingIndex = null) }
        page.eventually("the rule is saved") { vm.state.value.saveState == SaveState.Saved }

        val post = page.backend.sent("POST /v3/bulletin-subscriptions").single()
        assertEquals("Bearer $FIRST", post.authorization)
        assertEquals("""{"name":"Lectures","orgs":["office"],"tags":["lecture"],"mode":"OR","enabled":true}""", post.body)
        assertEquals(listOf("Scholarships" to true, "Lectures" to true), vm.shown())
        assertEquals(2, vm.state.value.rules[1].id)
        assertEquals(listOf("Scholarships" to true, "Lectures" to true), page.backend.held(FIRST))
    }

    @Test
    fun `on v3 an edited rule is patched by its id, and replaced where it stood`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        val edited = vm.state.value.rules[0].copy(name = "Grants")

        page.onMain { vm.upsertRule(edited, replacingIndex = 0) }
        page.eventually("the edit is saved") { vm.state.value.saveState == SaveState.Saved }

        val patch = page.backend.sent("PATCH /v3/bulletin-subscriptions/1").single()
        assertEquals("""{"name":"Grants","orgs":[],"tags":["scholarship"],"mode":"AND","enabled":true}""", patch.body)
        assertEquals(listOf("Grants" to true, "Lectures" to true), vm.shown())
        assertEquals(listOf("GET /v3/bulletin-subscriptions", "PATCH /v3/bulletin-subscriptions/1"), page.backend.routes())
    }

    @Test
    fun `on v3 a delete leaves the page at once, and goes to the rule's id`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        val held = page.backend.hold("DELETE /v3/bulletin-subscriptions/1")

        page.onMain { vm.deleteRule(0) }

        assertEquals(listOf("Lectures" to true), vm.shown())
        held.awaitArrival()
        held.release()
        page.eventually("the delete is saved") { vm.state.value.saveState == SaveState.Saved }
        assertEquals(listOf("Lectures" to true), vm.shown())
        assertEquals("", page.backend.sent("DELETE /v3/bulletin-subscriptions/1").single().body)
        assertEquals(listOf("Lectures" to true), page.backend.held(FIRST))
    }

    @Test
    fun `on v3 a refused delete puts the rule back where it stood`() {
        val vm = page.open(V3, "Scholarships", "Lectures")
        page.backend.refuseNext("DELETE /v3/bulletin-subscriptions/1", 503, "{}")

        page.onMain { vm.deleteRule(0) }
        page.eventually("the delete fails") { vm.state.value.saveState is SaveState.Failed }

        assertEquals(listOf("Scholarships" to true, "Lectures" to true), vm.shown())
        assertEquals(listOf(1, 2), vm.state.value.rules.map { it.id })
    }

    @Test
    fun `on v3 a toggle flips at once and is patched`() {
        val vm = page.open(V3, "Scholarships")
        val held = page.backend.hold("PATCH /v3/bulletin-subscriptions/1")

        page.onMain { vm.toggleEnabled(0) }

        assertEquals(listOf("Scholarships" to false), vm.shown())
        held.awaitArrival()
        held.release()
        page.eventually("the toggle is saved") { vm.state.value.saveState == SaveState.Saved }
        assertEquals(false, page.backend.sent("PATCH /v3/bulletin-subscriptions/1").single().json.getBoolean("enabled"))
        assertEquals(listOf("Scholarships" to false), page.backend.held(FIRST))
    }

    /** Each tap is its own PATCH, and the echo of an earlier one never overwrites a later one. */
    @Test
    fun `on v3 two toggles in flight settle on the later one`() {
        val vm = page.open(V3, "Scholarships")
        val first = page.backend.hold("PATCH /v3/bulletin-subscriptions/1")
        val second = page.backend.hold("PATCH /v3/bulletin-subscriptions/1")

        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain { vm.toggleEnabled(0) }
        second.awaitArrival()
        second.release()
        page.eventually("the later toggle is saved") { vm.state.value.saveState == SaveState.Saved }
        first.release()
        page.eventually("the earlier echo is back") { page.backend.held(FIRST) == listOf("Scholarships" to false) }
        page.settle()

        assertEquals(listOf(false, true), page.backend.sent("PATCH /v3/bulletin-subscriptions/1").map { it.json.getBoolean("enabled") })
        assertEquals(listOf("Scholarships" to true), vm.shown())
    }

    @Test
    fun `on v3 a refused toggle puts back what the backend last agreed to`() {
        val vm = page.open(V3, "Scholarships")
        page.backend.refuseNext("PATCH /v3/bulletin-subscriptions/1", 422, """{"detail":[]}""")

        page.onMain { vm.toggleEnabled(0) }
        page.eventually("the toggle fails") { vm.state.value.saveState is SaveState.Failed }

        assertEquals(listOf("Scholarships" to true), vm.shown())
    }

    @Test
    fun `on v3 the default rule is posted from the taxonomy's default tags`() {
        val vm = page.open(V3)
        page.eventually("the taxonomy is in") { vm.state.value.taxonomy != null }

        page.onMain { vm.applyDefaultRules() }
        page.eventually("the default rule is saved") { vm.state.value.saveState == SaveState.Saved }

        val post = page.backend.sent("POST /v3/bulletin-subscriptions").single()
        assertEquals("""{"orgs":[],"tags":["scholarship"],"mode":"OR","enabled":true}""", post.body)
        assertEquals(listOf(null to true), vm.shown())
    }
}
