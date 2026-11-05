package org.ntust.app.tigerduck.auth.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetUpdater

/**
 * A school sign-out's hold on the next sign-in, and the wipe it leaves for the application scope.
 * A sign-out is [begin], the account's clearing, then [finish]. A process death anywhere in that
 * leaves the marker ([SignOutOwed]), and the next start runs what is left ([recover]).
 *
 * All of it is one count under one lock: the sign-outs that have begun and not finished their
 * wipe. [begin] counts one in and closes the session. The wipe [finish] starts counts it out, and
 * the last one out takes the marker off and opens the session. So the session opens, and a typed
 * sign-in waiting on it goes, only when no sign-out is under way, wherever a second one landed.
 *
 * Each sign-out has a wipe of its own, started after its own clearing, one running at a time.
 * Nothing stays running between them, so a sign-out can never wait on a worker that just stopped.
 *
 * A clearing with a step that failed still wipes, but that sign-out is not over: the last one out
 * leaves the marker on and the session closed, and the next start tries again, as often as
 * [SignOutOwed.triesAgain] allows.
 */
class SignOutWipe(
    private val wipe: suspend () -> Unit,
    private val session: AccountSession,
    private val scope: CoroutineScope,
) {
    constructor(
        stores: SchoolStores,
        widgetUpdater: WidgetUpdater,
        session: AccountSession,
        scope: CoroutineScope,
    ) : this({ run(stores, widgetUpdater) }, session, scope)

    private val lock = Any()
    private var unfinished = 0

    // A clearing failed, and the next start tries again.
    private var tryingAgain = false

    // Only keeps the wipes apart; the state is all under [lock].
    private val oneWipe = Mutex()

    /**
     * Before a sign-out clears anything: [departingStudentId] goes on the marker, on disk, and the
     * session closes, which stops a sign-in still on its way. The caller holds the lock a sign-in
     * stores its account under, so the student read for the marker is the one stored from here on.
     */
    fun begin(departingStudentId: String?): Unit = synchronized(lock) {
        unfinished++
        session.markWipeOwed(departingStudentId)
        session.signIns.end()
    }

    /**
     * After the clearing: wipes on the application scope, so a coroutine launched from a transient
     * ViewModel scope can't be canceled mid-delete when the user backs out of Settings or the
     * activity dies. A wipe that is stopped all the same leaves the session closed and the marker
     * on, for the next start. [cleared] says whether the clearing's steps all went.
     */
    fun finish(cleared: Boolean = true) {
        if (!cleared) clearingFailed()
        scope.launch {
            try {
                oneWipe.withLock { wipe() }
            } finally {
                // Not over a wipe its scope stopped: that one is the next start's, marker and all.
                if (isActive) countOut()
            }
        }
    }

    /**
     * Holds the session closed as one more sign-out under way, until [release]: a full reset's own
     * wipe, which a sign-in must wait out as it waits out the sign-out's.
     */
    fun hold() {
        synchronized(lock) { unfinished++ }
    }

    /** Lets go of a [hold]: the session opens when no sign-out is left. */
    fun release(): Unit = countOut()

    // The last one out takes the marker off and opens the session, unless a clearing failed.
    private fun countOut() = synchronized(lock) {
        if (--unfinished == 0) {
            if (!tryingAgain) session.open()
            tryingAgain = false
        }
    }

    private fun clearingFailed() = synchronized(lock) { if (session.triesAgain()) tryingAgain = true }

    /**
     * At process start, before anything acts on the account: when the marker holds a sign-out the
     * process died in, runs [clearAccount], that sign-out's own clearing, whatever of it already
     * happened, and says true: the caller then [finish]es it. [clearAccount] answers whether its
     * steps all went. A marker of someone else's is dropped, with nothing cleared, and with no
     * marker this costs one read of a boolean.
     */
    fun recover(storedStudentId: () -> String?, clearAccount: () -> Boolean): Boolean {
        synchronized(lock) {
            if (!session.wipeOwed) return false
            if (!session.replays(storedStudentId())) {
                session.open()
                return false
            }
            unfinished++
        }
        if (!clearAccount()) clearingFailed()
        return true
    }

    companion object {
        /** Each step on its own, so one that throws stops no other: the late stores in their order, then the widgets. */
        suspend fun run(stores: SchoolStores, widgetUpdater: WidgetUpdater) {
            stores.late.forEach { store ->
                runCatching { store.forgetAccount() }
                    .onFailure { Log.w("AuthService", "${store.javaClass.simpleName} failed on logout: ${it.logSummary()}") }
            }
            // Now that the timetable is gone: a widget left alone shows the departing student's
            // until its next scheduled update, up to half an hour away.
            runCatching { widgetUpdater.updateAll() }
                .onFailure { Log.w("AuthService", "widget update failed on logout: ${it.logSummary()}") }
        }
    }
}
