package org.ntust.app.tigerduck.mail.index

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.model.MailFlags

/** [MailIndexer] over [FakeMailServer], its one session handed out as MailRepository hands out the shared one. */
class MailIndexerTest {
    private val server = FakeMailServer()
    private val index = InMemoryMailSearchIndex()
    private val session = server.factory().open(MailCredentials("b10000001", "pw"))

    /** Whether a block holds the connection now, as the shared one's lock would say. */
    private var holding = false

    private val sessions = object : MailSessionRunner {
        override suspend fun <T> run(stillWanted: () -> Boolean, block: (MailSession) -> T): T {
            if (!stillWanted()) throw MailError.Abandoned()
            holding = true
            try {
                return block(session)
            } finally {
                holding = false
            }
        }
    }

    private val me = "b10000001@mail.ntust.edu.tw"

    /** An indexer whose account never changes. */
    private fun indexer() = MailIndexer(index, sessions, owner = { MailIndexer.Owner(me, 1, epoch = 0) }, stillOwner = { true })

    /** [folder] deleted and created again on the server: a new UIDVALIDITY, and its UIDs from 1 again. */
    private fun recreate(folder: String = "INBOX") {
        server.folders.getValue(folder).clear()
        server.uidValidity++
        server.nextUid = 1
    }

    @Test
    fun `a run whose account changed right after it was read writes nothing`() = runTest {
        server.deliver("期中考")
        var generation = 1
        // Another student signs in right after the account is read, before anything else is.
        val indexer = MailIndexer(
            index, sessions,
            owner = { MailIndexer.Owner(me, generation, epoch = 0).also { generation++ } },
            stillOwner = { it.generation == generation },
        )

        runCatching { indexer.plan("INBOX")?.let { indexer.fill(it) } }

        assertTrue(index.uids(me, "INBOX").isEmpty())
    }

    @Test
    fun `mail fetched once the folder was recreated is not filed as the mail planned`() = runTest {
        server.deliver("舊信")
        val indexer = indexer()
        val plan = indexer.plan("INBOX")!!
        recreate()
        server.deliver("新信")

        assertFalse("cut short, for the next run to plan again", indexer.fill(plan))

        assertEquals(emptyList<Long>(), index.search(me, "INBOX", plan.uidValidity, "新信"))
    }

    @Test
    fun `flags read once the folder was recreated are not the downloaded mail's`() = runTest {
        server.deliver("舊信")
        val indexer = indexer()
        val plan = indexer.plan("INBOX")!!
        indexer.fill(plan)
        val validity = server.uidValidity
        recreate()
        server.deliver("新信", seen = true)

        assertFalse("cut short, for the next run to plan again", indexer.refreshFlags(plan))

        assertFalse(index.summaries(me, "INBOX", validity, listOf(1L)).getValue(1L).flags.seen)
    }

    /**
     * A mark-read or a move holds the connection too. Let in between a batch's read of a mail and
     * its filing, it would be undone by the batch's older answer: so the two are one hold.
     */
    @Test
    fun `a plan, a batch and the flags it refreshes are filed before the connection is let go`() = runTest {
        server.deliver("期中考")
        val unheld = mutableListOf<String>()
        val watched = object : MailSearchIndex by index {
            override fun open(owner: String, folder: String, uidValidity: Long, stillCurrent: () -> Boolean): Boolean {
                if (!holding) unheld += "open"
                return index.open(owner, folder, uidValidity, stillCurrent)
            }

            override fun remove(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>, stillCurrent: () -> Boolean) {
                if (!holding) unheld += "remove"
                index.remove(owner, folder, uidValidity, uids, stillCurrent)
            }

            override fun add(
                owner: String,
                folder: String,
                uidValidity: Long,
                entries: List<MailSearchIndex.Entry>,
                stillCurrent: () -> Boolean,
            ): Boolean {
                if (!holding) unheld += "add"
                return index.add(owner, folder, uidValidity, entries, stillCurrent)
            }

            override fun updateFlags(
                owner: String,
                folder: String,
                uidValidity: Long,
                flags: Map<Long, MailFlags>,
                stillCurrent: () -> Boolean,
            ) {
                if (!holding) unheld += "updateFlags"
                index.updateFlags(owner, folder, uidValidity, flags, stillCurrent)
            }
        }
        val indexer = MailIndexer(watched, sessions, owner = { MailIndexer.Owner(me, 1, epoch = 0) }, stillOwner = { true })
        val plan = indexer.plan("INBOX")!!

        indexer.fill(plan)
        indexer.refreshFlags(plan)

        assertEquals(emptyList<String>(), unheld)
    }
}
