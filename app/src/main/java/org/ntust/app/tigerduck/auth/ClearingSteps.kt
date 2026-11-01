package org.ntust.app.tigerduck.auth

import android.util.Log
import org.ntust.app.tigerduck.util.logSummary

/**
 * The steps of one clearing of an account. Each is tried whatever became of the ones before, and
 * what one throws is logged and goes no further: a browser flag that cannot be written must not
 * leave the credentials behind.
 */
internal class ClearingSteps {
    /** False once a [must] step failed: the clearing is not done, and has to be tried again. */
    var cleared = true
        private set

    /** A step that leaves something of the departing account on disk if it fails. */
    fun must(name: String, step: () -> Unit) {
        if (!tried(name, step)) cleared = false
    }

    /** A step a later start has nothing to try again for: what it clears ends with the process, or is owed elsewhere. */
    fun once(name: String, step: () -> Unit) {
        tried(name, step)
    }

    private fun tried(name: String, step: () -> Unit): Boolean = runCatching(step)
        .onFailure { Log.w(TAG, "The sign-out's $name step failed: ${it.logSummary()}") }
        .isSuccess

    private companion object {
        const val TAG = "ClearingSteps"
    }
}
