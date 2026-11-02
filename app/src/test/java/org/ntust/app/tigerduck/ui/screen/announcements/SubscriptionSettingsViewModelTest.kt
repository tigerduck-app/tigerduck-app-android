package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.FIRST
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V3
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V4
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState
import java.util.UUID

/**
 * The bulletin subscription settings page's saves. On `/v3` each edit is its own request to its
 * rule (POST, PATCH, DELETE), as it always was, and these pin it. On `/v4` every edit saves the
 * whole list in one PUT, each rule by the `client_id` this device made for it (`docs/api-v4.md`,
 * "Subscriptions"), one save at a time (iOS `sync-order`, "One send per channel at a time").
 */
class SubscriptionSettingsViewModelTest {

    @get:Rule val page = SubscriptionPage()

    private fun SubscriptionSettingsViewModel.shown() = state.value.rules.map { it.name to it.enabled }

    /** What each PUT of the list sent: every rule's `client_id`, name and whether it is enabled. */
    private fun puts(): List<List<Triple<String, String?, Boolean>>> = page.backend.sent(PUT).map { put ->
        val rules = put.json.getJSONArray("rules")
        (0 until rules.length()).map(rules::getJSONObject).map {
            Triple(it.getString("client_id"), if (it.isNull("name")) null else it.getString("name"), it.getBoolean("enabled"))
        }
    }

    private fun assertIsUuid(id: String) = assertEquals(id, UUID.fromString(id).toString())

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/sync/v4/$name")) { "missing fixture sync/v4/$name" }.readText()

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
        // The read, and the later toggle's answer.
        page.awaitAnswers(vm, 2)
        assertEquals(listOf("Scholarships" to true), vm.shown())
        first.release()
        // The earlier toggle's echo, handled by the page: it lands nowhere.
        page.awaitAnswers(vm, 3)

        assertEquals(listOf(false, true), page.backend.sent("PATCH /v3/bulletin-subscriptions/1").map { it.json.getBoolean("enabled") })
        assertEquals(listOf("Scholarships" to true), vm.shown())
        assertEquals(SaveState.Saved, vm.state.value.saveState)
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

    @Test
    fun `on v4 a toggle puts the whole list, each rule by the client_id it was read with`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val ids = page.backend.clientIds(FIRST)

        page.onMain { vm.toggleEnabled(1) }
        page.eventually("the list is saved") { vm.state.value.saveState == SaveState.Saved }

        assertEquals(listOf(listOf(Triple(ids[0], "Scholarships", true), Triple(ids[1], "Lectures", false))), puts())
        assertEquals(listOf(GET, PUT), page.backend.routes())
        assertEquals("Bearer $FIRST", page.backend.sent(PUT).single().authorization)
        assertEquals(listOf("Scholarships" to true, "Lectures" to false), vm.shown())
        assertEquals(listOf("Scholarships" to true, "Lectures" to false), page.backend.held(FIRST))
        assertEquals(ids, page.backend.clientIds(FIRST))
    }

    @Test
    fun `on v4 a new rule gets a client_id of its own, and every later save keeps it`() {
        val vm = page.open(V4, "Scholarships")

        page.onMain { vm.upsertRule(SubscriptionRule(name = "Lectures", tags = listOf("lecture")), replacingIndex = null) }
        page.eventually("the new rule is saved") { vm.state.value.saveState == SaveState.Saved }
        page.onMain { vm.toggleEnabled(1) }
        page.eventually("the toggle is saved") {
            vm.state.value.saveState == SaveState.Saved && page.backend.held(FIRST) == listOf("Scholarships" to true, "Lectures" to false)
        }

        val (first, second) = puts()
        val made = first[1].first
        assertIsUuid(made)
        assertNotEquals(first[0].first, made)
        assertEquals(made, second[1].first)
        assertEquals(listOf(first[0].first, made), page.backend.clientIds(FIRST))
        assertEquals(listOf(GET, PUT, PUT), page.backend.routes())
    }

    @Test
    fun `on v4 an edited rule keeps its place and its client_id`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val ids = page.backend.clientIds(FIRST)
        val edited = vm.state.value.rules[0].copy(name = "Grants")

        page.onMain { vm.upsertRule(edited, replacingIndex = 0) }
        page.eventually("the edit is saved") { vm.state.value.saveState == SaveState.Saved }

        assertEquals(listOf(listOf(Triple(ids[0], "Grants", true), Triple(ids[1], "Lectures", true))), puts())
        assertEquals(listOf("Grants" to true, "Lectures" to true), vm.shown())
        assertEquals("the backend kept each row", listOf(1, 2), vm.state.value.rules.map { it.id })
        assertEquals(ids, page.backend.clientIds(FIRST))
    }

    @Test
    fun `on v4 a deleted rule is left out of the list`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val ids = page.backend.clientIds(FIRST)

        page.onMain { vm.deleteRule(0) }

        assertEquals(listOf("Lectures" to true), vm.shown())
        page.eventually("the delete is saved") { vm.state.value.saveState == SaveState.Saved }
        assertEquals(listOf(listOf(Triple(ids[1], "Lectures", true))), puts())
        assertEquals(listOf("Lectures" to true), page.backend.held(FIRST))
        assertEquals(listOf(GET, PUT), page.backend.routes())
    }

    @Test
    fun `on v4 the default rule is put as the list`() {
        val vm = page.open(V4)
        page.eventually("the taxonomy is in") { vm.state.value.taxonomy != null }

        page.onMain { vm.applyDefaultRules() }
        page.eventually("the default rule is saved") { vm.state.value.saveState == SaveState.Saved }

        val rule = page.backend.sent(PUT).single().json.getJSONArray("rules").getJSONObject(0)
        assertEquals("a rule without a name leaves it out", setOf("client_id", "orgs", "tags", "mode", "enabled"), rule.keys().asSequence().toSet())
        assertIsUuid(rule.getString("client_id"))
        assertEquals("OR", rule.getString("mode"))
        assertEquals("scholarship", rule.getJSONArray("tags").getString(0))
        assertEquals(listOf(null to true), vm.shown())
    }

    @Test
    fun `on v4 a rule read without a client_id gets one where the list loads, and keeps it`() {
        page.backend.answerNext(
            GET, 200,
            """{"items":[{"id":7,"name":"Scholarships","orgs":[],"tags":["scholarship"],"mode":"AND","enabled":true,"revision":1}]}""",
        )
        val vm = page.open(V4)

        page.onMain { vm.toggleEnabled(0) }
        page.eventually("the first toggle is saved") { vm.state.value.saveState == SaveState.Saved }
        page.onMain { vm.toggleEnabled(0) }
        page.eventually("the second toggle is saved") {
            vm.state.value.saveState == SaveState.Saved && page.backend.held(FIRST) == listOf("Scholarships" to true)
        }

        val (first, second) = puts()
        assertIsUuid(first.single().first)
        assertEquals(first.single().first, second.single().first)
    }

    /** iOS `sync-order`, "One send per channel at a time", scenario "Two saves in a row". */
    @Test
    fun `two saves in one round trip keep the second on screen and send it after the first reply`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        val second = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()

        page.onMain { vm.toggleEnabled(1) }

        assertEquals(listOf("Scholarships" to false, "Lectures" to false), vm.shown())
        assertEquals("one save at a time: the second waits for the first answer", 1, page.savesBegun(vm))
        first.release()
        second.awaitArrival()
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), vm.shown())
        second.release()
        page.eventually("both saves are in") { vm.state.value.saveState == SaveState.Saved }
        assertEquals(listOf("> $PUT", "< $PUT 200", "> $PUT", "< $PUT 200"), page.backend.events.filter { PUT in it })
        assertEquals(listOf(listOf(false, true), listOf(false, false)), puts().map { put -> put.map { it.third } })
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), vm.shown())
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), page.backend.held(FIRST))
    }

    @Test
    fun `a reply never replaces newer local rules`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        val second = page.backend.hold(PUT)
        page.onMain { vm.deleteRule(1) }
        first.awaitArrival()
        page.onMain { vm.upsertRule(SubscriptionRule(name = "Grants"), replacingIndex = null) }

        first.release()
        second.awaitArrival()

        // The first answer is in, and holds "Scholarships" alone: the rule added since stays.
        assertEquals(listOf("Scholarships" to true, "Grants" to true), vm.shown())
        second.release()
        page.eventually("the second save is in") { vm.state.value.saveState == SaveState.Saved }
        assertEquals(listOf("Scholarships" to true, "Grants" to true), vm.shown())
        assertEquals(listOf(listOf("Scholarships"), listOf("Scholarships", "Grants")), puts().map { put -> put.map { it.second } })
    }

    @Test
    fun `on v4 a refused save puts back what the backend last agreed to`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        page.backend.refuseNext(PUT, 503, "{}")

        page.onMain { vm.toggleEnabled(0) }
        page.eventually("the save fails") { vm.state.value.saveState is SaveState.Failed }

        assertEquals(listOf(GET, PUT), page.backend.routes())
        assertEquals(listOf("Scholarships" to true, "Lectures" to true), vm.shown())
    }

    @Test
    fun `on v4 a duplicate client_id shows as a failed save`() {
        val vm = page.open(V4, "Scholarships")
        page.backend.refuseNext(PUT, 422, fixture("error-duplicate-client-id.json"))

        page.onMain { vm.upsertRule(SubscriptionRule(name = "Grants"), replacingIndex = null) }
        page.eventually("the save fails") { vm.state.value.saveState is SaveState.Failed }

        assertEquals(listOf(GET, PUT), page.backend.routes())
        assertEquals(listOf("Scholarships" to true), vm.shown())
        assertEquals(listOf("Scholarships" to true), page.backend.held(FIRST))
    }

    @Test
    fun `on v4 a refused save under a newer edit leaves the newer edit to its own save`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain { vm.toggleEnabled(1) }

        first.release(503 to "{}")
        page.eventually("the newer edit is saved") {
            vm.state.value.saveState == SaveState.Saved &&
                page.backend.held(FIRST) == listOf("Scholarships" to false, "Lectures" to false)
        }

        assertEquals(listOf("Scholarships" to false, "Lectures" to false), vm.shown())
        assertEquals(listOf(listOf(false, true), listOf(false, false)), puts().map { put -> put.map { it.third } })
    }

    @Test
    fun `on v4 a refused save puts back the list the last save that landed left`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain { vm.toggleEnabled(1) }
        page.backend.refuseNext(PUT, 503, "{}")

        first.release()
        page.eventually("the second save fails") { vm.state.value.saveState is SaveState.Failed }

        // The first save landed while the second edit waited, and only the second was refused.
        assertEquals(listOf("Scholarships" to false, "Lectures" to true), vm.shown())
        assertEquals(listOf("Scholarships" to false, "Lectures" to true), page.backend.held(FIRST))
    }

    private companion object {
        const val GET = "GET /v4/bulletin-subscriptions"
        const val PUT = "PUT /v4/bulletin-subscriptions"
    }
}
