package org.ntust.app.tigerduck.data.cache

import android.content.Context

/**
 * Folder names for each account's downloaded Moodle files that do not spell the account, from a
 * random key kept in private preferences.
 */
internal class OpaqueFolderNames(context: Context) {

    /**
     * A folder name for [account] that does not spell it: its path shows in the content URI
     * another app is handed.
     */
    fun folderFor(account: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(installSalt(), "HmacSHA256"))
        return mac.doFinal(account.trim().uppercase().toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * A random key made once per install and kept in private preferences, so the account folder's
     * name cannot be worked back to a student ID by hashing every ID there is. Read from disk
     * once, then held for the process: one that could not be written still names the folder the
     * same way until the app restarts. A full reset makes a new one.
     */
    @Synchronized
    private fun installSalt(): ByteArray {
        salt?.let { return it }
        val kept = moodleSaltPrefs.getString(SALT_KEY, null)
            ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size >= 16 }
        val made = kept ?: ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it)
            val written = moodleSaltPrefs.edit()
                .putString(SALT_KEY, android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
                .commit()
            if (!written) android.util.Log.w(TAG, "Moodle folder salt not saved")
        }
        salt = made
        return made
    }

    /** Drops the folder salt, on disk and held, for a full reset. */
    @Synchronized
    fun forgetInstallSalt() {
        salt = null
        moodleSaltPrefs.edit().clear().commit()
    }

    private var salt: ByteArray? = null

    private val moodleSaltPrefs = context.getSharedPreferences(SALT_PREFS, Context.MODE_PRIVATE)

    private companion object {
        private const val TAG = "OpaqueFolderNames"
        private const val SALT_PREFS = "moodle_files"
        private const val SALT_KEY = "folder_salt"
    }
}
