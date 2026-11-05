package org.ntust.app.tigerduck.push

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.clubs.ClubSubscriptions
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocumentTest.Companion.at
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsWire
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.SyncOutcome
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/** The club document's sync step and its runner, as iOS's ClubSubscriptionsSyncTests. */
class ClubSubscriptionsSyncTest {

    /** The settings document's server for one namespace: reads and writes answered in turn. */
    private class Server : SettingsDocumentTransport {
        var stored: SettingsDocumentEnvelope<JsonObject>? = null
        val conflicts = ArrayDeque<SettingsDocumentEnvelope<JsonObject>>()
        var failReads = 0
        var failWrites = 0
        var reads = 0
        val writes = mutableListOf<Pair<JsonObject, Long?>>()

        override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? {
            reads++
            if (failReads > 0) { failReads--; throw SettingsDocumentApiException("read club_subscriptions failed: HTTP 500") }
            return stored
        }

        override suspend fun write(document: JsonObject, baseRevision: Long?, isCurrent: () -> Boolean): SettingsWriteResult<JsonObject> {
            if (!isCurrent()) return SettingsWriteResult.Abandoned
            writes += document to baseRevision
            if (failWrites > 0) { failWrites--; throw SettingsDocumentApiException("write club_subscriptions failed: HTTP 500") }
            conflicts.removeFirstOrNull()?.let { stored = it; return SettingsWriteResult.Conflict(it) }
            val revision = (stored?.revision ?: 0) + 1
            stored = SettingsDocumentEnvelope(document, revision)
            return SettingsWriteResult.Written(revision)
        }
    }

    private fun subscribed(vararg ids: Int, from: Int = 1): ClubSubscriptionsDocument =
        ids.foldIndexed(ClubSubscriptionsDocument()) { offset, doc, id -> doc.subscribe(id, 0xD35400, at(from + offset)) }

    private fun envelope(document: ClubSubscriptionsDocument, revision: Long) =
        SettingsDocumentEnvelope(ClubSubscriptionsWire.json(document), revision)

    @Test
    fun `a first push creates the document from the device's copy`() = runBlocking {
        val server = Server()
        val local = subscribed(93)

        val synced = syncClubSubscriptions(local, server) { true }

        assertEquals(local, synced)
        assertNull(server.writes.single().second)
        assertEquals(local, ClubSubscriptionsWire.read(server.writes.single().first))
    }

    @Test
    fun `keys this build does not know survive the write, at the top and inside a club`() = runBlocking {
        val server = Server()
        val held = ClubSubscriptionsWire.json(subscribed(33)).apply {
            getAsJsonObject("clubs").getAsJsonObject("33").addProperty("notify", true)
            add("future", JsonParser.parseString("""{"x": 1}"""))
        }
        server.stored = SettingsDocumentEnvelope(held, 3)

        syncClubSubscriptions(subscribed(33).subscribe(93, 0xD35400, at(5)), server) { true }

        val (written, base) = server.writes.single()
        assertEquals(3L, base)
        assertEquals(1, written.getAsJsonObject("future").get("x").asInt)
        assertTrue(written.getAsJsonObject("clubs").getAsJsonObject("33").get("notify").asBoolean)
        assertEquals(listOf(33, 93), ClubSubscriptionsWire.read(written).subscribedIds)
    }

    @Test
    fun `a document the server already holds is not written again, and the device takes the server's newer values`() = runBlocking {
        val server = Server()
        val held = subscribed(41, 93, from = 10)
        server.stored = envelope(held, 7)

        val synced = syncClubSubscriptions(subscribed(93), server) { true }

        assertEquals(held, synced)
        assertEquals(emptyList<Pair<JsonObject, Long?>>(), server.writes)
    }

    @Test
    fun `a 409 is merged once more with the newer copy it carries, and a second 409 gives up`() = runBlocking {
        val server = Server()
        server.stored = SettingsDocumentEnvelope(JsonObject(), 3)
        server.conflicts += envelope(subscribed(41, from = 9), 4)

        val synced = syncClubSubscriptions(subscribed(93), server) { true }

        assertEquals(listOf(3L, 4L), server.writes.map { it.second })
        assertEquals(synced, ClubSubscriptionsWire.read(server.writes.last().first))
        assertEquals(listOf(41, 93), synced?.subscribedIds)

        val again = Server()
        again.conflicts += envelope(ClubSubscriptionsDocument(), 1)
        again.conflicts += envelope(ClubSubscriptionsDocument(), 2)
        try {
            syncClubSubscriptions(subscribed(93), again) { true }
            fail("two conflicts in a row")
        } catch (e: SettingsDocumentApiException) {
            assertEquals(2, again.writes.size)
        }
    }

    @Test
    fun `nothing is written once writes are no longer allowed`() = runBlocking {
        val server = Server()

        assertNull(syncClubSubscriptions(subscribed(93), server) { false })
        assertEquals(emptyList<Pair<JsonObject, Long?>>(), server.writes)
    }

    // ---- the runner ----

    private val context = JvmContext()
    private val subscriptions = ClubSubscriptions(context.getSharedPreferences("club_subscriptions", 0))
    private val server = Server()
    private var syncOn = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val signedIn = mutableListOf<SignIn>()
    private val syncer = ClubSubscriptionsSyncer(
        documents = { signIn -> signedIn += signIn; server },
        subscriptions = subscriptions,
        tigerSyncOn = { syncOn },
        scope = scope,
    )

    private suspend fun idle() = scope.coroutineContext.job.children.toList().joinAll()

    @Test
    fun `a run that lands clears the unsent mark, and its requests carry the sign-in`() = runBlocking {
        val signIn = testSignIn()
        subscriptions.subscribe(93, signIn)

        assertEquals(SyncOutcome.SYNCED, syncer.sync(signIn))

        assertFalse(subscriptions.hasUnsentChanges)
        assertEquals(listOf(signIn), signedIn)
        assertEquals(listOf(93), ClubSubscriptionsWire.read(server.stored!!.document).subscribedIds)
    }

    @Test
    fun `with TigerSync off nothing is asked, and the edit stays unsent for when it is on`() = runBlocking {
        val signIn = testSignIn()
        subscriptions.subscribe(93, signIn)
        syncOn = false

        assertEquals(SyncOutcome.SKIPPED, syncer.sync(signIn))
        syncer.retryIfOwed(signIn)
        idle()

        assertEquals(0, server.reads)
        assertTrue(subscriptions.hasUnsentChanges)
    }

    @Test
    fun `a failed write is sent again at the next poll, and a failed read is owed though nothing is unsent`() = runBlocking {
        val signIn = testSignIn()
        subscriptions.subscribe(93, signIn)
        server.failWrites = 1
        assertEquals(SyncOutcome.FAILED, syncer.sync(signIn))
        syncer.retryIfOwed(signIn)
        idle()
        assertFalse(subscriptions.hasUnsentChanges)
        assertEquals(2, server.writes.size)

        server.stored = envelope(subscribed(41, from = 30), 9)
        server.failReads = 1
        syncer.afterFullSync(signIn, revision = 9)
        idle()
        assertTrue(syncer.owesRun)
        syncer.retryIfOwed(signIn)
        idle()
        assertFalse(syncer.owesRun)
        assertEquals(setOf(41, 93), subscriptions.subscribedIds.toSet())
    }

    @Test
    fun `a full sync at a revision the last run already read asks nothing`() = runBlocking {
        val signIn = testSignIn()
        syncer.afterFullSync(signIn, revision = 5)
        idle()
        syncer.afterFullSync(signIn, revision = 5)
        idle()
        assertEquals(1, server.reads)

        syncer.afterFullSync(signIn, revision = 6)
        idle()
        assertEquals(2, server.reads)
        subscriptions.subscribe(93, signIn)
        syncer.afterFullSync(signIn, revision = 6)
        idle()
        assertEquals(3, server.reads)
    }

    @Test
    fun `a sign-out while the write is out leaves the device's copy alone, and a run for an ended sign-in sends nothing`() = runBlocking {
        val series = SignInSeries()
        val signIn = series.current
        subscriptions.subscribe(93, signIn)
        val before = subscriptions.document.value
        server.stored = envelope(subscribed(41, from = 30), 2)
        val ending = object : SettingsDocumentTransport by server {
            override suspend fun read() = server.read().also { series.end() }
        }
        val syncer = ClubSubscriptionsSyncer({ ending }, subscriptions, { true }, scope)

        assertEquals(SyncOutcome.SKIPPED, syncer.sync(signIn))
        assertEquals(before, subscriptions.document.value)
        assertEquals(emptyList<Pair<JsonObject, Long?>>(), server.writes)
        assertEquals(SyncOutcome.SKIPPED, syncer.sync(signIn))
        assertEquals(1, server.reads)
    }
}
