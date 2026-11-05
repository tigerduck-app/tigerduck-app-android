package org.ntust.app.tigerduck.clubs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Club
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Stamp
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Stamped
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.time.Instant

/** The subscriptions store, as iOS's ClubSubscriptionsTests. */
class ClubSubscriptionsTest {

    private val context = JvmContext()
    private val prefs = context.getSharedPreferences("club_subscriptions", 0)
    private var minute = 0L

    private fun store() = ClubSubscriptions(prefs) { Instant.ofEpochSecond(1_793_620_800L + 60 * ++minute) }

    @Test
    fun `a change is saved for its sign-in and marked unsent`() {
        val store = store()
        val signIn = testSignIn()

        val changes = listOf(
            store.subscribe(93, signIn),
            store.subscribe(33, signIn),
            store.subscribe(33, signIn),
            store.reorder(listOf(33, 93), signIn),
            store.setColor(0x123456, 93, signIn),
        )

        assertEquals(listOf(true, true, false, true, true), changes.map { it.changed })
        assertEquals(listOf(true, false, false, false, false), changes.map { it.firstSubscription })
        assertTrue(store.hasUnsentChanges)
        assertEquals(listOf(33, 93), store.subscribedIds)
        assertEquals(0x123456, store.color(93))
        assertEquals(0xC0392B, store.color(33))
        assertEquals(0x1ABC9C, store.color(41))
        assertEquals(store.document.value, store().document.value)
    }

    @Test
    fun `a change for a sign-in that ended writes nothing`() {
        val store = store()
        val series = SignInSeries()
        val ended = series.current.also { series.end() }

        val change = store.subscribe(93, ended)
        store.adopt(ClubSubscriptionsDocument(mapOf(41 to Club(Stamped(true, Stamp.of(Instant.now()))))), ended)

        assertFalse(change.changed)
        assertFalse(store.hasSubscriptions)
        assertFalse(store.hasUnsentChanges)
        assertNull(prefs.getString("document", null))
    }

    @Test
    fun `adopting the server's copy keeps a change made since, and clears the mark only once nothing is left`() {
        val store = store()
        val signIn = testSignIn()
        store.subscribe(93, signIn)
        val sent = store.document.value
        store.subscribe(33, signIn)

        store.adopt(sent, signIn)
        assertEquals(listOf(93, 33), store.subscribedIds)
        assertTrue(store.hasUnsentChanges)

        store.adopt(store.document.value, signIn)
        assertFalse(store.hasUnsentChanges)
    }

    @Test
    fun `an edit here beats every value this device has seen, even one stamped ahead by another device's clock`() {
        val store = store()
        val signIn = testSignIn()
        val ahead = Stamp.read("2099-01-01T00:00:00.000Z")!!
        val server = ClubSubscriptionsDocument(mapOf(93 to Club(Stamped(true, ahead), Stamped(0x111111, ahead))))
        store.adopt(server, signIn)

        store.setColor(0x222222, 93, signIn)

        assertEquals(0x222222, ClubSubscriptionsDocument.merged(store.document.value, server).clubs[93]?.color?.value)
        store.adopt(server, signIn)
        assertEquals(0x222222, store.color(93))
        assertTrue(store.hasUnsentChanges)
    }

    @Test
    fun `the first subscribed club, here or adopted from another device, is told once`() {
        val signIn = testSignIn()
        val adopting = store()
        assertTrue(adopting.adopt(ClubSubscriptionsDocument(mapOf(41 to Club(Stamped(true, Stamp.of(Instant.now()))))), signIn))
        assertFalse(adopting.subscribe(93, signIn).firstSubscription)

        adopting.clear()
        val subscribing = store()
        assertTrue(subscribing.subscribe(93, signIn).firstSubscription)
        assertFalse(subscribing.subscribe(33, signIn).firstSubscription)
    }

    @Test
    fun `signing out forgets the subscriptions, their colors and the unsent mark`() {
        val store = store()
        store.subscribe(93, testSignIn())

        store.forgetAccount()

        assertNull(prefs.getString("document", null))
        assertFalse(store.hasUnsentChanges)
        assertEquals(ClubSubscriptionsDocument(), store.document.value)
        assertEquals(ClubSubscriptionsDocument(), store().document.value)
    }
}
