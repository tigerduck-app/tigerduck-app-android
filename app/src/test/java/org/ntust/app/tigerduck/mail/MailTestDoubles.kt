package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Stands in for the app-wide `@ApplicationScope`: the only thing [MailAccount]
 * runs there is sign-out's cache wipe, so a plain supervised scope is enough.
 * A test that asserts on that work waits for it (it lands on [Dispatchers.IO]).
 */
fun testApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Stops what a test left running on a [testApplicationScope] and waits for it
 * to end. Sign-out's cache wipe would otherwise outlive the test and race
 * TemporaryFolder deleting the same folder, and what it throws then fails a
 * later runTest, in any class, as UncaughtExceptionsBeforeTest. Call it from
 * an @After, which runs before rules such as TemporaryFolder clean up. Gives
 * up after ten seconds, failing that test, rather than hang the suite on work
 * that never ends.
 */
fun CoroutineScope.cancelAndJoinBlocking() {
    // Read here: inside runBlocking, coroutineContext is runBlocking's own.
    val job = coroutineContext.job
    runBlocking { withTimeout(10_000) { job.cancelAndJoin() } }
}

class InMemoryMailDevServerStore(
    override var settings: MailDevServerSettings = MailDevServerSettings.OFF,
) : MailDevServerStore

/** A [MailSite] with no developer override stored: the school server, as everything but the override screen sees it. */
fun schoolMailSite(): MailSite = MailSite(InMemoryMailDevServerStore())

class InMemoryCredentialStore : MailCredentialStore {
    /** The thread of every read of the student ID: the real store decrypts it, so never on the main thread. */
    val idReaders = ConcurrentLinkedQueue<Thread>()

    override var mailStudentId: String? = null
        get() {
            idReaders += Thread.currentThread()
            return field
        }

    /** Fires on every [mailPassword] read, which the real store decrypts: a test checks which thread it ran on. */
    var onPasswordRead: (() -> Unit)? = null
    override var mailPassword: String? = null
        get() = field.also { onPasswordRead?.invoke() }

    /** Fires inside [clearMailCredentials], before anything is removed: lets a test act mid-sign-out. */
    var beforeClear: (() -> Unit)? = null
    override fun clearMailCredentials() {
        beforeClear?.invoke()
        mailStudentId = null
        mailPassword = null
    }
}

class RecordingScheduler : MailBackgroundScheduler {
    var scheduled = 0
    var cancelled = 0

    /** Every schedule and cancel, in order, from whatever thread. */
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Fires inside [cancel], before it takes: a test acts while a cancel is under way. */
    @Volatile var onCancel: (() -> Unit)? = null

    override fun schedule() {
        scheduled++
        events += "schedule"
    }

    override fun cancel() {
        onCancel?.invoke()
        cancelled++
        events += "cancel"
    }
}

class RecordingNotifier : MailNotifier {
    val posted = mutableListOf<List<MailSummary>>()
    var authFailures = 0
    val cancelledUids = mutableListOf<Long>()
    var cancelledAll = 0

    /** Fires inside [postNewMail] -- lets a test act at the exact point the check notifies. */
    var onPostNewMail: (() -> Unit)? = null
    override fun postNewMail(folder: String, messages: List<MailSummary>) {
        posted += messages
        onPostNewMail?.invoke()
    }
    override fun postAuthFailure() { authFailures++ }
    var authFailuresCancelled = 0
    override fun cancelAuthFailure() { authFailuresCancelled++ }
    override fun cancelMessage(uid: Long) { cancelledUids += uid }
    val cancelledPosted = mutableListOf<List<MailSummary>>()
    override fun cancelPosted(messages: List<MailSummary>) { cancelledPosted += messages }
    /** Fires inside [cancelAll] -- lets a test wait for sign-out's own cleanup, which runs on its own. */
    var onCancelAll: (() -> Unit)? = null
    override fun cancelAll() {
        cancelledAll++
        onCancelAll?.invoke()
    }
}

class FakeDemoGate(override var appDemoActive: Boolean = false) : MailDemoGate {
    val box = DemoMailbox(
        studentId = "B10000099", password = "demo", displayName = "示範同學",
        messages = listOf(
            DemoMail(
                MailSummary(1001, MailAddress("教務處", "office@mail.ntust.edu.tw"), emptyList(), emptyList(), emptyList(),
                    "期中考時間公告", null, null, MailFlags.NONE, 100, false, "<d1@x>", null, null),
                MailBody("<p>期中考</p>", "期中考", emptyList(), emptyMap()),
            ),
            DemoMail(
                MailSummary(1002, MailAddress("示範同學", "b10000099@mail.ntust.edu.tw"), emptyList(),
                    listOf(MailAddress("系辦公室", "office2@mail.ntust.edu.tw")), emptyList(),
                    "詢問事項", null, null, MailFlags.NONE.copy(seen = true, draft = true), 50, false, "<d2@x>", null, null),
                MailBody(null, "詢問內容", emptyList(), emptyMap()),
                folder = SpecialFolder.DRAFTS,
            ),
            DemoMail(
                MailSummary(1003, MailAddress("示範同學", "b10000099@mail.ntust.edu.tw"), emptyList(),
                    listOf(MailAddress("教務處", "office@mail.ntust.edu.tw")), emptyList(),
                    "Re: 期中考時間公告", null, null, MailFlags.NONE.copy(seen = true), 50, false, "<d3@x>", null, null),
                MailBody(null, "收到，謝謝", emptyList(), emptyMap()),
                folder = SpecialFolder.SENT,
            ),
        ),
    )
    override fun matches(studentId: String, password: String) = box.matches(studentId, password)
    override fun mailbox() = box
}

/**
 * Signs [studentId] in again on [account], on a thread of its own, from inside a step of the
 * account's mark of a refusal ([during]), and lets that step go on once the sign-in has got as far
 * as the mark lets it: to the account's lock while the step holds it, or all the way otherwise.
 *
 * Nothing here throws into the step, which can be a view model's coroutine whose failure would
 * outlive the test: what went wrong is kept for [join], which the test calls once it is done.
 */
class SignInAgain(private val account: MailAccount, private val studentId: String, private val password: String) {
    @Volatile private var signIn: Thread? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var timedOut = false

    /** Call from inside the step. */
    fun during() {
        if (signIn != null) return
        val reached = CountDownLatch(1)
        account.onLockWait = { reached.countDown() }
        // Asked here, on the step's own thread, before the sign-in can take the lock itself.
        val held = account.holdsLock()
        val started = thread(isDaemon = true) {
            try {
                runBlocking { account.signIn(studentId, password) }
            } catch (t: Throwable) {
                failure = t
            } finally {
                reached.countDown()
            }
        }
        signIn = started
        // A long safety timeout only: each wait ends as soon as what it waits for happens.
        val arrived = if (held) reached.await(30, TimeUnit.SECONDS) else started.join30()
        if (!arrived) timedOut = true
        account.onLockWait = null
    }

    /** Waits for the sign-in to end, and fails the test for anything that went wrong on the way. */
    fun join() {
        val started = checkNotNull(signIn) { "the step never ran" }
        started.join(30_000)
        check(!started.isAlive) { "the sign-in never ended" }
        check(!timedOut) { "the sign-in never got as far as the step lets it" }
        failure?.let { throw AssertionError("the sign-in failed", it) }
    }

    private fun Thread.join30(): Boolean {
        join(30_000)
        return !isAlive
    }
}

/** [SignInAgain] from inside the account's mark of a refusal: as [state]'s authFailed is about to turn true. */
fun signInDuringRefusal(account: MailAccount, state: InMemoryMailStateStore, studentId: String, password: String): SignInAgain {
    val again = SignInAgain(account, studentId, password)
    state.onAuthFailedSet = { failed ->
        if (failed) {
            state.onAuthFailedSet = null
            again.during()
        }
    }
    return again
}
