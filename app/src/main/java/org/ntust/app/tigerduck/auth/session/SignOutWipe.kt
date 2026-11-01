package org.ntust.app.tigerduck.auth.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
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
 */
class SignOutWipe(
    private val wipe: suspend () -> Unit,
    private val session: AccountSession,
    private val scope: CoroutineScope,
) {
    constructor(
        importedClassTables: ImportedClassTableStore,
        dataCache: DataCache,
        widgetUpdater: WidgetUpdater,
        session: AccountSession,
        scope: CoroutineScope,
    ) : this({ run(importedClassTables, dataCache, widgetUpdater) }, session, scope)

    private val lock = Any()
    private var unfinished = 0

    // Only keeps the wipes apart; the state is all under [lock].
    private val oneWipe = Mutex()

    /**
     * Before a sign-out clears anything: [departingStudentId] goes on the marker, on disk, and the
     * session closes, before the clearing moves the sign-in generation a typed sign-in checks.
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
     * on, for the next start.
     */
    fun finish() {
        scope.launch {
            oneWipe.withLock { wipe() }
            synchronized(lock) { if (--unfinished == 0) session.open() }
        }
    }

    /**
     * At process start, before anything acts on the account: when the marker holds a sign-out the
     * process died in, runs [clearAccount], that sign-out's own clearing, whatever of it already
     * happened, and says true: the caller then [finish]es it. A marker of someone else's is
     * dropped, with nothing cleared, and with no marker this costs one read of a boolean.
     */
    fun recover(storedStudentId: () -> String?, clearAccount: () -> Unit): Boolean {
        synchronized(lock) {
            if (!session.wipeOwed) return false
            if (!session.replays(storedStudentId())) {
                session.open()
                return false
            }
            unfinished++
        }
        // Thrown at every start, this would keep the app from opening at all.
        runCatching(clearAccount).onFailure { Log.w(TAG, "Recovery's clearing failed: ${it.logSummary()}") }
        return true
    }

    companion object {
        private const val TAG = "SignOutWipe"

        /** Each step on its own, so one that throws stops no other. */
        suspend fun run(importedClassTables: ImportedClassTableStore, dataCache: DataCache, widgetUpdater: WidgetUpdater) {
            // Only this sign-out empties them: a relogin in the background or a renewed session
            // keeps the class tables the user imported.
            runCatching { importedClassTables.clearForSignOut() }
                .onFailure { Log.w("AuthService", "clearForSignOut failed on logout: ${it.logSummary()}") }
            runCatching { dataCache.clearAllUserData() }
                .onFailure { Log.w("AuthService", "clearAllUserData failed on logout: ${it.logSummary()}") }
            // Now that the timetable is gone: a widget left alone shows the departing student's
            // until its next scheduled update, up to half an hour away.
            runCatching { widgetUpdater.updateAll() }
                .onFailure { Log.w("AuthService", "widget update failed on logout: ${it.logSummary()}") }
        }
    }
}
