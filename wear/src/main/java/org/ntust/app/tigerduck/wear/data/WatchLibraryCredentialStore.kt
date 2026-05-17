@file:Suppress("DEPRECATION")

package org.ntust.app.tigerduck.wear.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.ntust.app.tigerduck.shared.LibraryCredentialStore

/**
 * Watch-side mirror of the phone's library credentials, populated by the
 * Wearable Data Layer push (`WearProtocol.LibraryCredentials`). Implements the
 * shared [LibraryCredentialStore] so the same [LibraryService] code can run on
 * the watch.
 *
 * Backed by [EncryptedSharedPreferences] so the synced phone password and
 * token are not recoverable from a compromised or rooted watch — same at-rest
 * protection the phone gives them in `CredentialManager`. The synchronous
 * property getters/setters [LibraryService] expects keep working because
 * EncryptedSharedPreferences implements [SharedPreferences].
 */
class WatchLibraryCredentialStore(context: Context) : LibraryCredentialStore {

    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    /** Reactive snapshot for the UI layer — flips between "not synced", "logged out", and "logged in". */
    val state: Flow<LibrarySnapshot> = prefsFlow(prefs)
        .map { LibrarySnapshot(libraryUsername, libraryToken, libraryTokenExpiry) }
        .distinctUntilChanged()

    override var libraryUsername: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_USERNAME, value).apply()
        else prefs.edit().remove(KEY_USERNAME).apply()

    override var libraryPassword: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_PASSWORD, value).apply()
        else prefs.edit().remove(KEY_PASSWORD).apply()

    override var libraryToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_TOKEN, value).apply()
        else prefs.edit().remove(KEY_TOKEN).apply()

    override var libraryTokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    override val isLibraryTokenValid: Boolean
        get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry

    /** Bulk replace; used by the Data Layer listener on phone push. */
    fun replace(
        username: String?,
        password: String?,
        token: String?,
        tokenExpiry: Long,
    ) {
        prefs.edit()
            .apply {
                if (username == null) remove(KEY_USERNAME) else putString(KEY_USERNAME, username)
                if (password == null) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, password)
                if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
                putLong(KEY_TOKEN_EXPIRY, tokenExpiry)
            }
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    data class LibrarySnapshot(
        val username: String?,
        val token: String?,
        val tokenExpiry: Long,
    ) {
        val isLoggedIn: Boolean get() = !username.isNullOrEmpty()
    }

    companion object {
        private const val PREFS_NAME = "wear_library_credentials"
        private const val KEY_USERNAME = "library_username"
        private const val KEY_PASSWORD = "library_password"
        private const val KEY_TOKEN = "library_token"
        private const val KEY_TOKEN_EXPIRY = "library_token_expiry"

        @Volatile
        private var instance: WatchLibraryCredentialStore? = null

        fun get(context: Context): WatchLibraryCredentialStore {
            return instance ?: synchronized(this) {
                instance ?: WatchLibraryCredentialStore(context.applicationContext)
                    .also { instance = it }
            }
        }

        /**
         * Mirrors the phone's [CredentialManager] recovery path: if the
         * encrypted file is unreadable (corrupted keystore after restore, etc),
         * delete it and rebuild once. Losing the cached credentials forces a
         * re-sync from the phone, which is strictly better than crashing on
         * every read.
         */
        private fun createEncryptedPrefs(appContext: Context): SharedPreferences {
            fun attempt(): SharedPreferences {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                attempt()
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences unusable; resetting", e)
                runCatching { appContext.deleteSharedPreferences(PREFS_NAME) }
                attempt()
            }
        }

        private const val TAG = "WatchLibraryCreds"
    }
}

/**
 * Tiny `SharedPreferences` → Flow adapter: emits a `Unit` tick on every
 * change so callers can re-read the prefs. `conflate()` drops bursts so
 * back-to-back edits coalesce into a single recomposition.
 */
private fun prefsFlow(prefs: SharedPreferences): Flow<Unit> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.onStart { emit(Unit) }.conflate()
