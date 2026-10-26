package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import android.util.Log
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PendingUnregisterStore
import org.ntust.app.tigerduck.util.logSummary

/**
 * The device unregister a sign-out still owes, in the encrypted credential store beside the
 * session it was taken from. The sign-out's own clearing leaves it: it is what outlasts the
 * sign-out.
 */
class PendingUnregisterPrefs internal constructor(private val prefs: SharedPreferences) : PendingUnregisterStore {

    override fun load(): PendingUnregister? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val access = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val session = DepartingSession(
            access, refresh, prefs.getLong(KEY_EXPIRES_AT, 0L), baseUrl, prefs.getString(KEY_STUDENT_ID, null),
        )
        return PendingUnregister(deviceId, session, prefs.getLong(KEY_OWED_AT, 0L), prefs.getInt(KEY_UNANSWERED, 0))
    }

    /**
     * Committed, as [V3TokenStore.save] is: a rotated refresh token still queued by `apply()`
     * when the process dies would bring the replaced one back on the next try. Says whether the
     * disk took it, so a send whose count it refused is not made.
     */
    override fun save(pending: PendingUnregister): Boolean {
        val written = prefs.edit()
            .putString(KEY_DEVICE_ID, pending.deviceId)
            .putString(KEY_ACCESS_TOKEN, pending.session.accessToken)
            .putString(KEY_REFRESH_TOKEN, pending.session.refreshToken)
            .putLong(KEY_EXPIRES_AT, pending.session.accessExpiresAt)
            .putString(KEY_BASE_URL, pending.session.baseUrl)
            .putLong(KEY_OWED_AT, pending.owedAt)
            .putInt(KEY_UNANSWERED, pending.unansweredRefreshes)
            .apply { pending.session.studentId?.let { putString(KEY_STUDENT_ID, it) } ?: remove(KEY_STUDENT_ID) }
            .commit()
        if (!written) Log.w(TAG, "owed unregister not written to disk")
        return written
    }

    override fun clear() {
        val written = prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_BASE_URL)
            .remove(KEY_OWED_AT)
            .remove(KEY_STUDENT_ID)
            .remove(KEY_UNANSWERED)
            .commit()
        if (!written) Log.w(TAG, "owed unregister not removed from disk")
    }

    internal companion object {
        const val KEY_DEVICE_ID = "departing_device_id"
        const val KEY_ACCESS_TOKEN = "departing_access_token"
        const val KEY_REFRESH_TOKEN = "departing_refresh_token"
        const val KEY_EXPIRES_AT = "departing_expires_at"
        const val KEY_BASE_URL = "departing_base_url"
        const val KEY_OWED_AT = "departing_owed_at"
        const val KEY_STUDENT_ID = "departing_student_id"
        const val KEY_UNANSWERED = "departing_unanswered_refreshes"
        val KEYS = setOf(
            KEY_DEVICE_ID, KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_EXPIRES_AT, KEY_BASE_URL, KEY_OWED_AT,
            KEY_STUDENT_ID, KEY_UNANSWERED,
        )
        private const val TAG = "PendingUnregisterPrefs"
    }
}

/**
 * Removes every credential in [prefs] but the unregister a sign-out still owes: the full reset
 * signs out first, and its wipe must not take the debt along with the session it was taken
 * from, or a device whose unregister failed offline would stay registered to the account.
 * Key by key rather than `clear()` and a write back, so a debt the sign-out saves while this
 * runs is never removed either.
 */
internal fun clearAllButOwedUnregister(prefs: SharedPreferences) {
    val keys = runCatching { prefs.all.keys }
        .onFailure { Log.w("PendingUnregisterPrefs", "credentials unreadable; wiping them all: ${it.logSummary()}") }
        .getOrNull()
    if (keys == null) {
        prefs.edit().clear().apply()
        return
    }
    val editor = prefs.edit()
    keys.filter { it !in PendingUnregisterPrefs.KEYS }.forEach { editor.remove(it) }
    editor.apply()
}
