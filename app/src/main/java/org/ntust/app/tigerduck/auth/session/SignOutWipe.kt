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
 */
class SignOutWipe(
    private val importedClassTables: ImportedClassTableStore,
    private val dataCache: DataCache,
    private val widgetUpdater: WidgetUpdater,
    private val session: AccountSession,
    private val scope: CoroutineScope,
) {
    /**
     * Wipes on the application scope, so a coroutine launched from a transient ViewModel scope
     * can't be cancelled mid-delete when the user backs out of Settings or the activity dies.
     * The next sign-in starts after it, whatever the wipe did: it waited for it.
     */
    fun start(): Job = scope.launch {
        try { run() } finally { session.open() }
    }

    /** At process start: finishes the wipe a process death cut short. */
    fun finishOwed() {
        if (session.wipeOwed) start()
    }

    /** Each step on its own, so one that throws stops no other. */
    suspend fun run() {
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
