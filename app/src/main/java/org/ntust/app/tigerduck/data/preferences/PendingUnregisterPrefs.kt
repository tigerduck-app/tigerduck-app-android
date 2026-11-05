package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.network.backend.ApiBase
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PendingUnregisterStore
import org.ntust.app.tigerduck.util.logSummary

/**
 * The device unregister a sign-out still owes, in the encrypted credential store beside the
 * session it was taken from. The sign-out's own clearing leaves it: it is what outlasts the
 * sign-out.
 */
class PendingUnregisterPrefs internal constructor(private val prefs: SharedPreferences) : PendingUnregisterStore {

    @Synchronized
    override fun load(): List<PendingUnregister> {
        // A new key leaves the old shape readable on a direct upgrade. No Gson fields depend on
        // constructor defaults or R8 names: every persisted field is read explicitly.
        val stored = prefs.getString(KEY_DEBTS, null) ?: return listOfNotNull(legacy())
        val entries = JSONArray(stored)
        return List(entries.length()) { read(entries.getJSONObject(it)) }
    }

    private fun legacy(): PendingUnregister? {
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
    @Synchronized
    override fun save(pending: PendingUnregister): Boolean =
        write(load().filterNot { ApiBase.sameBackend(it.session.baseUrl, pending.session.baseUrl) } + pending)

    @Synchronized
    override fun clear(base: String?) {
        if (base != null) {
            write(load().filterNot { ApiBase.sameBackend(it.session.baseUrl, base) })
            return
        }
        val written = prefs.edit()
            .remove(KEY_DEBTS)
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

    private fun read(entry: JSONObject): PendingUnregister {
        val session = DepartingSession(
            entry.getString(KEY_ACCESS_TOKEN), entry.getString(KEY_REFRESH_TOKEN),
            entry.optLong(KEY_EXPIRES_AT, 0L), entry.getString(KEY_BASE_URL),
            if (entry.isNull(KEY_STUDENT_ID)) null else entry.getString(KEY_STUDENT_ID),
        )
        return PendingUnregister(
            entry.getString(KEY_DEVICE_ID), session, entry.optLong(KEY_OWED_AT, 0L), entry.optInt(KEY_UNANSWERED, 0),
        )
    }

    private fun write(pending: List<PendingUnregister>): Boolean {
        val entries = JSONArray()
        pending.forEach { debt ->
            entries.put(
                JSONObject()
                    .put(KEY_DEVICE_ID, debt.deviceId)
                    .put(KEY_ACCESS_TOKEN, debt.session.accessToken)
                    .put(KEY_REFRESH_TOKEN, debt.session.refreshToken)
                    .put(KEY_EXPIRES_AT, debt.session.accessExpiresAt)
                    .put(KEY_BASE_URL, debt.session.baseUrl)
                    .put(KEY_OWED_AT, debt.owedAt)
                    .put(KEY_UNANSWERED, debt.unansweredRefreshes)
                    .put(KEY_STUDENT_ID, debt.session.studentId),
            )
        }
        // The collection and removal of the legacy entry land in the same committed edit. An
        // empty collection also marks that entry as consumed, so it cannot reappear on restart.
        val editor = prefs.edit().putString(KEY_DEBTS, entries.toString())
        KEYS.filter { it != KEY_DEBTS }.forEach { editor.remove(it) }
        val written = editor.commit()
        if (!written) Log.w(TAG, "owed unregister not written to disk")
        return written
    }

    internal companion object {
        const val KEY_DEBTS = "departing_unregisters"
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
            KEY_STUDENT_ID, KEY_UNANSWERED, KEY_DEBTS,
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
