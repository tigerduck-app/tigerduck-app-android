package org.ntust.app.tigerduck.sync.outbox

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.shared.account.SignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The outbox at the school's real sign-out, over the real stores (`accountWorld`): every channel
 * forgets what it holds in memory for the account, while the queue files are still there for
 * DataCache's clearing, which comes after. Play only, as `accountWorld` is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxSignOutTest {

    private val context = RuntimeEnvironment.getApplication()

    /** The sign-out's own clearing, waited for; work that would reach a network goes nowhere. */
    private val signOutJob = SupervisorJob()
    private val signOutScope = CoroutineScope(signOutJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        signOutScope.cancel()
    }

    @Test
    fun `a sign-out forgets every channel's memory, before DataCache takes the queue files`() = runBlocking {
        val queueFile = File(context.filesDir, "TigerDuckData/unsent_marks.json")
        val forgotten = CopyOnWriteArrayList<OutboxChannelId>()
        val queueThere = CopyOnWriteArrayList<Boolean>()
        val channels = OutboxChannelId.entries.map { channelId ->
            object : OutboxChannel {
                override val id = channelId

                override suspend fun drain(signIn: SignIn) = Unit

                override suspend fun requeueParked(signIn: SignIn) = Unit

                override suspend fun heldFields(): Set<String> = emptySet()

                override fun forgetMemory() {
                    forgotten += channelId
                    queueThere += queueFile.exists()
                }
            }
        }
        val w = accountWorld(
            context, context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE), signOutScope, idleScope,
            outbox = Outbox(channels.shuffled().toSet()),
        )
        assertTrue(w.auth.login(STUDENT, "a-password"))
        assertTrue(w.dataCache.saveUnsentMarks(listOf(UnsentMark("4021", "ignored", madeAtMs = 1L)), w.sessions.school.signIns.current))
        assertTrue(queueFile.exists())
        assertEquals(emptyList<OutboxChannelId>(), forgotten.toList())

        w.plans.signOut(SignOutReason.SCHOOL)
        withTimeout(10_000) {
            w.sessions.school.awaitOpen()
            signOutJob.children.toList().joinAll()
        }

        assertEquals(OutboxChannelId.entries, forgotten.toList())
        assertEquals("the queue files were still there", List(OutboxChannelId.entries.size) { true }, queueThere.toList())
        assertFalse("DataCache took them after", queueFile.exists())
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
