package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetUpdater

/**
 * The wipe a school sign-out leaves for the application scope, and the session opening after it.
 * Run by the sign-out, and at the next start when a process death cut that run short
 * ([SignOutOwed]): the same block both times.
 *
 * One wipe runs at a time. A sign-out during a wipe queues one more, and the marker clears and
 * the session opens only when none is left, so a sign-in waiting on the first never starts while
 * a second is pending.
 */
class SignOutWipe(
    private val clearing: suspend () -> Unit,
    private val session: AccountSession,
    private val scope: CoroutineScope,
    /** The student stored now, or null. */
    private val storedStudentId: () -> String?,
    /** Finishes a sign-out the process died in: the credentials, the tokens and the signed-in state. */
    private val dropStoredAccount: () -> Unit,
) {
    constructor(
        importedClassTables: ImportedClassTableStore,
        dataCache: DataCache,
        widgetUpdater: WidgetUpdater,
        session: AccountSession,
        scope: CoroutineScope,
        storedStudentId: () -> String?,
        dropStoredAccount: () -> Unit,
    ) : this({ run(importedClassTables, dataCache, widgetUpdater) }, session, scope, storedStudentId, dropStoredAccount)

    private val lock = Any()
    private var running = false
    private var again = false

    /**
     * Wipes on the application scope, so a coroutine launched from a transient ViewModel scope
     * can't be cancelled mid-delete when the user backs out of Settings or the activity dies.
     * Joins the wipe under way by asking it for one more pass.
     */
    fun start(): Unit = synchronized(lock) {
        again = true
        if (running) return
        running = true
        scope.launch {
            try {
                drain()
            } finally {
                synchronized(lock) { running = false }
            }
        }
    }

    // The session opens under the lock that start() takes, so a sign-out cannot ask for another
    // pass between the last pass and the opening.
    private suspend fun drain() {
        while (true) {
            synchronized(lock) {
                if (!again) {
                    session.open()
                    return
                }
                again = false
            }
            clearing()
        }
    }

    /**
     * At process start, before anything reads the account: finishes the sign-out the marker
     * promised. The stored account goes first and synchronously when it is still the one that
     * left; a marker of someone else's is dropped.
     */
    fun finishOwedSignOut() {
        if (!session.wipeOwed) return
        when (session.replay(storedStudentId())) {
            SignOutOwed.Replay.SIGN_OUT_AND_WIPE -> dropStoredAccount()
            SignOutOwed.Replay.WIPE -> Unit
            SignOutOwed.Replay.DROP -> session.open()
        }
    }

    /** At process start, after [finishOwedSignOut]: runs the wipe a process death cut short. */
    fun finishOwed() {
        if (session.wipeOwed) start()
    }

    companion object {
        /** Each step on its own, so one that throws stops no other. */
        suspend fun run(importedClassTables: ImportedClassTableStore, dataCache: DataCache, widgetUpdater: WidgetUpdater) {
            // Only this sign-out empties them: a relogin in the background or a renewed session
            // keeps the class tables the user imported.
            runCatching { importedClassTables.clearForSignOut() }
                .onFailure { android.util.Log.w("AuthService", "clearForSignOut failed on logout: ${it.logSummary()}") }
            runCatching { dataCache.clearAllUserData() }
                .onFailure { android.util.Log.w("AuthService", "clearAllUserData failed on logout: ${it.logSummary()}") }
            // Now that the timetable is gone: a widget left alone shows the departing student's
            // until its next scheduled update, up to half an hour away.
            runCatching { widgetUpdater.updateAll() }
                .onFailure { android.util.Log.w("AuthService", "widget update failed on logout: ${it.logSummary()}") }
        }
    }
}
