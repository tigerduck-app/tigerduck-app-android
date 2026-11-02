package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.FIRST
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionPage.Companion.V4
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState

/**
 * The order of the subscription page's `/v4` saves, each a PUT of the whole list: one save at a
 * time (iOS `sync-order`, "One send per channel at a time"; spec §5.3, race 4), edits made while
 * one is out going together after its answer, and no answer or refusal replacing an edit made
 * since its save went.
 */
class SubscriptionListSavesTest {

    @get:Rule val page = SubscriptionPage()

    private fun SubscriptionSettingsViewModel.shown() = state.value.rules.map { it.name to it.enabled }

    /** What each PUT of the list sent: every rule's `client_id`, name and whether it is enabled. */
    private fun puts(): List<List<Triple<String, String?, Boolean>>> = page.backend.sent(PUT).map { put ->
        val rules = put.json.getJSONArray("rules")
        (0 until rules.length()).map(rules::getJSONObject).map {
            Triple(it.getString("client_id"), if (it.isNull("name")) null else it.getString("name"), it.getBoolean("enabled"))
        }
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
        assertEquals("one save at a time: the second waits for the first answer", 1, page.savesBegun())
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
    fun `on v4 a refused save under a newer edit leaves the newer edit to its own save`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        val second = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain { vm.toggleEnabled(1) }

        first.release(503 to "{}")
        second.awaitArrival()
        assertEquals("the newer edit stays while its save is out", listOf("Scholarships" to false, "Lectures" to false), vm.shown())
        assertEquals(SaveState.Saving, vm.state.value.saveState)
        second.release()
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

    /**
     * Off, then on and off again while the first save is out: the list is back to what that save
     * sent, but the student edited it since. The first save is refused, and the last edit, off,
     * stays on the page and goes up.
     */
    @Test
    fun `on v4 a refused save leaves edits made since to their own save, even ones that came back to its list`() {
        val vm = page.open(V4, "Scholarships")
        val first = page.backend.hold(PUT)
        val second = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain {
            vm.toggleEnabled(0)
            vm.toggleEnabled(0)
        }

        first.release(503 to "{}")
        second.awaitArrival()

        // The refusal is handled, and the last edit stays on the page while its own save is out.
        assertEquals(listOf("Scholarships" to false), vm.shown())
        assertEquals(SaveState.Saving, vm.state.value.saveState)
        second.release()
        // The refusal, and the last edit's save.
        page.awaitSaves(2)

        assertEquals(listOf(listOf(false), listOf(false)), puts().map { put -> put.map { it.third } })
        assertEquals(listOf("Scholarships" to false), page.backend.held(FIRST))
        assertEquals(listOf("Scholarships" to false), vm.shown())
        assertEquals(SaveState.Saved, vm.state.value.saveState)
    }

    /** As above, but the first save lands: the page is not saved until the last edit's save is. */
    @Test
    fun `on v4 an answer under edits made since leaves the page saving, even when they came back to its list`() {
        val vm = page.open(V4, "Scholarships")
        val first = page.backend.hold(PUT)
        val second = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain {
            vm.toggleEnabled(0)
            vm.toggleEnabled(0)
        }

        first.release()
        second.awaitArrival()

        assertEquals("a save is still out", SaveState.Saving, vm.state.value.saveState)
        second.release()
        page.awaitSaves(2)
        assertEquals(SaveState.Saved, vm.state.value.saveState)
        assertEquals(listOf("Scholarships" to false), vm.shown())
        assertEquals(listOf("Scholarships" to false), page.backend.held(FIRST))
    }

    /**
     * An edit waits behind a save still out when the student leaves the page, which clears its
     * view model. The edit still goes up once that save is answered, and the page opened again
     * shows it.
     */
    @Test
    fun `an edit waiting when the page closes is still saved, and the page opened again shows it`() {
        val left = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        page.onMain { left.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain { left.toggleEnabled(1) }

        page.close(left)
        first.release()

        page.eventually("both saves are answered") { page.backend.events.count { it.startsWith("< $PUT") } == 2 }
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), page.backend.held(FIRST))
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), page.reopen().shown())
    }

    /**
     * A save is out when the student leaves the page, opens it again and edits: the edit's save
     * waits for the first one's answer, as one save of the list is out at a time for the app,
     * and goes after it.
     */
    @Test
    fun `a page opened again while a save is out sends nothing until that save is answered`() {
        val left = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        val second = page.backend.hold(PUT)
        page.onMain { left.toggleEnabled(0) }
        first.awaitArrival()
        page.close(left)

        val again = page.reopen()
        page.onMain { again.toggleEnabled(1) }

        assertEquals("one save at a time for the app: the new page's waits for the old one's", 1, page.savesBegun())
        first.release()
        second.awaitArrival()
        second.release()
        page.eventually("both saves are answered") { page.backend.events.count { it.startsWith("< $PUT") } == 2 }
        assertEquals(listOf("> $PUT", "< $PUT 200", "> $PUT", "< $PUT 200"), page.backend.events.filter { PUT in it })
    }

    /** A save refused after the student left the page: the page opened again says so, until the student has seen it. */
    @Test
    fun `a refusal while the page was closed shows on the page opened again, until seen`() {
        val left = page.open(V4, "Scholarships")
        val first = page.backend.hold(PUT)
        page.onMain { left.toggleEnabled(0) }
        first.awaitArrival()
        page.close(left)
        first.release(503 to "{}")
        page.awaitSaves(1)

        val again = page.reopen()

        assertTrue("got ${again.state.value.saveState}", again.state.value.saveState is SaveState.Failed)
        assertEquals(listOf("Scholarships" to true), again.shown())
        page.onMain { again.clearSaveState() }
        assertEquals(SaveState.Idle, page.reopen().state.value.saveState)
    }

    /**
     * Both rules disabled, the first one's save still out and the second's waiting, and the page
     * reads the list again before either lands: the backend still holds both enabled. The page
     * keeps the newest list, so disabling the first rule again, as the editor's Done does with
     * its switch off, keeps the second rule disabled too, and both go up disabled.
     */
    @Test
    fun `a read while saves are out keeps the newest list on the page`() {
        val vm = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.onMain { vm.toggleEnabled(1) }

        page.onMain { vm.load() }
        page.awaitAnswers(vm, 2)
        assertEquals("the read keeps the newest list", listOf("Scholarships" to false, "Lectures" to false), vm.shown())
        page.onMain { vm.upsertRule(vm.state.value.rules[0].copy(enabled = false), replacingIndex = 0) }
        first.release()

        page.eventually("both saves are answered") { page.backend.events.count { it.startsWith("< $PUT") } == 2 }
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), page.backend.held(FIRST))
        page.eventually("the last save shows") { vm.state.value.saveState == SaveState.Saved }
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), vm.shown())
    }

    /**
     * The backend's list changed since the page read it, as a save whose answer was lost leaves
     * it, and the page reads it again while a save is out: the read is what the backend holds,
     * so a refusal of that save puts it back, not the list of the read before.
     */
    @Test
    fun `a refusal after a read while a save was out puts back what that read found`() {
        val vm = page.open(V4, "Scholarships")
        val first = page.backend.hold(PUT)
        page.onMain { vm.toggleEnabled(0) }
        first.awaitArrival()
        page.backend.seed(FIRST, "Grants")

        page.onMain { vm.load() }
        page.awaitAnswers(vm, 2)
        assertEquals("the read keeps the newest list", listOf("Scholarships" to false), vm.shown())
        first.release(503 to "{}")
        page.awaitSaves(1)

        assertTrue("got ${vm.state.value.saveState}", vm.state.value.saveState is SaveState.Failed)
        assertEquals(listOf("Scholarships" to true, "Grants" to true), vm.shown())
    }

    /** As above, through a page opened again while the first page's save is out. */
    @Test
    fun `a page opened again while a save is out shows that save's list`() {
        val left = page.open(V4, "Scholarships", "Lectures")
        val first = page.backend.hold(PUT)
        page.onMain { left.toggleEnabled(0) }
        first.awaitArrival()
        page.close(left)

        val again = page.reopen()

        assertEquals("the page shows the list being saved", listOf("Scholarships" to false, "Lectures" to true), again.shown())
        page.onMain { again.toggleEnabled(1) }
        first.release()
        page.eventually("both saves are answered") { page.backend.events.count { it.startsWith("< $PUT") } == 2 }
        assertEquals(listOf("Scholarships" to false, "Lectures" to false), page.backend.held(FIRST))
    }

    private companion object {
        const val PUT = "PUT /v4/bulletin-subscriptions"
    }
}
