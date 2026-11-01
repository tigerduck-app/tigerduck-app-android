package org.ntust.app.tigerduck.auth.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Whether a sign-out is still owed its wipe, and whose it was, kept on disk so a wipe a process
 * death cut short is finished at the next start (as [org.ntust.app.tigerduck.browser.BrowserCleanup]
 * does for the in-app browser). Set before the sign-in ends, cleared only when the session opens
 * again, after the last wipe ran. While it is set the school session stays closed, so nothing can
 * write and a sign-in waits.
 *
 * The marker carries a hash of the student who left, as `MoodleColorStore` keeps who its book
 * belongs to, so a marker that outlives its wipe can never wipe a later student: see [replay].
 *
 * Device scope: the marker is about the device's files, not about an account.
 */
class SignOutOwed(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    /** What a start does with a marker it finds. */
    enum class Replay {
        /** The departing student is still the stored account: the promised sign-out is finished, then the wipe. */
        SIGN_OUT_AND_WIPE,

        /** Nobody is stored, so the wipe is all that is left. */
        WIPE,

        /** Someone else is stored: the marker is stale, and nothing may be wiped. */
        DROP,
    }

    val owed: Boolean get() = prefs.getBoolean(KEY_OWED, false)

    /** Sets the marker for the sign-out of [studentId] (null: nobody was stored), on disk before it returns. */
    fun mark(studentId: String?): Boolean = commit { edit ->
        edit.putBoolean(KEY_OWED, true)
        if (studentId == null) edit.remove(KEY_WHO) else edit.putString(KEY_WHO, hash(studentId))
    }

    /** Takes the marker off. False when the disk would not take it, after one more try. */
    fun clear(): Boolean = commit { it.remove(KEY_OWED).remove(KEY_WHO) }

    /**
     * What to do with the marker given the student stored now. A replay wipes only when that is
     * still the student who left, or nobody is stored; never a later student's data.
     */
    fun replay(storedStudentId: String?): Replay {
        val who = prefs.getString(KEY_WHO, null)
        return when {
            storedStudentId == null -> Replay.WIPE
            who != null && who == hash(storedStudentId) -> Replay.SIGN_OUT_AND_WIPE
            else -> Replay.DROP
        }
    }

    private fun commit(change: (SharedPreferences.Editor) -> Unit): Boolean {
        repeat(2) {
            val edit = prefs.edit()
            change(edit)
            if (edit.commit()) return true
        }
        Log.w(TAG, "The sign-out marker could not be written")
        return false
    }

    // Not the ID itself: the marker describes a wipe, not who took the courses.
    private fun hash(studentId: String): String =
        MessageDigest.getInstance("SHA-256").digest(studentId.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "SignOutOwed"
        const val PREFS_FILE = "tigerduck_account_wipe"
        const val KEY_OWED = "owed"
        const val KEY_WHO = "who"
    }
}
