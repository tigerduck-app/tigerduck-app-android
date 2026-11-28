package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import android.util.Log
import org.ntust.app.tigerduck.push.DeviceSecretStore

/**
 * The device secret and the backend's refusal of it, in the encrypted credential store beside the
 * backend session. Both are the device's, as its id is, and outlive every account: no sign-out
 * clears them, and the full reset leaves them ([clearAllButOwedUnregister]).
 */
class DeviceSecretPrefs internal constructor(private val prefs: SharedPreferences) : DeviceSecretStore {

    override fun load(): String? = prefs.getString(KEY_SECRET, null)

    /**
     * Committed, so the secret is on disk before a request carries it. A write the disk refuses
     * still sits in memory, where a later read would find it and send it, so it is taken out again.
     */
    override fun save(secret: String): Boolean {
        if (prefs.edit().putString(KEY_SECRET, secret).commit()) return true
        Log.w(TAG, "device secret not written to disk")
        prefs.edit().remove(KEY_SECRET).commit()
        return false
    }

    override val refused: Boolean get() = prefs.getBoolean(KEY_REFUSED, false)

    override fun recordRefusal(): Boolean = write { putBoolean(KEY_REFUSED, true) }

    override fun clearRefusal() {
        write { remove(KEY_REFUSED) }
    }

    override fun startOver() {
        write { remove(KEY_SECRET).remove(KEY_REFUSED) }
    }

    // Committed, so a process ended right after still finds it at the next start.
    private fun write(change: SharedPreferences.Editor.() -> SharedPreferences.Editor): Boolean =
        prefs.edit().change().commit().also { if (!it) Log.w(TAG, "device secret state not written to disk") }

    internal companion object {
        const val KEY_SECRET = "push_device_secret"
        const val KEY_REFUSED = "push_device_secret_refused"
        val KEYS = setOf(KEY_SECRET, KEY_REFUSED)
        private const val TAG = "DeviceSecretPrefs"
    }
}
