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
        get() {
            purgeIfStale()
            return prefs.getString(KEY_USERNAME, null)
        }
        set(value) = if (value != null) prefs.edit().putString(KEY_USERNAME, value).apply()
        else prefs.edit().remove(KEY_USERNAME).apply()

    override var libraryPassword: String?
        get() {
            purgeIfStale()
            return prefs.getString(KEY_PASSWORD, null)
        }
        set(value) = if (value != null) prefs.edit().putString(KEY_PASSWORD, value).apply()
        else prefs.edit().remove(KEY_PASSWORD).apply()

    override var libraryToken: String?
        get() {
            purgeIfStale()
            return prefs.getString(KEY_TOKEN, null)
        }
        set(value) = if (value != null) prefs.edit().putString(KEY_TOKEN, value).apply()
        else prefs.edit().remove(KEY_TOKEN).apply()

    override var libraryTokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    override val isLibraryTokenValid: Boolean
        get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry

    /** Outcome of an incoming Data Layer push — for caller logging only. */
    enum class ApplyResult { APPLIED, WIPED, REJECTED_REPLAY, MALFORMED }

    /**
     * Apply a phone-side credential push. Enforces anti-replay
     * (`version <= storedVersion` → REJECTED_REPLAY) and routes to either a
     * keychain replace (`hasCredentials = true`) or a keys-only wipe
     * (`hasCredentials = false`) that preserves the monotonic version
     * counter — so a delayed-but-stale `set` packet can't re-credential the
     * watch after a `wipe`.
     */
    @Synchronized
    fun applyPush(
        hasCredentials: Boolean,
        username: String?,
        password: String?,
        token: String?,
        tokenExpiry: Long,
        version: Long,
        issuedAtMs: Long,
    ): ApplyResult {
        val stored = prefs.getLong(KEY_LAST_VERSION, 0L)
        if (version <= stored) return ApplyResult.REJECTED_REPLAY

        val edit = prefs.edit()
        if (hasCredentials) {
            if (username == null || password == null) return ApplyResult.MALFORMED
            edit.putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .putLong(KEY_TOKEN_EXPIRY, tokenExpiry)
                .putLong(KEY_ISSUED_AT_MS, issuedAtMs)
            if (token == null) edit.remove(KEY_TOKEN) else edit.putString(KEY_TOKEN, token)
            edit.putLong(KEY_LAST_VERSION, version).apply()
            return ApplyResult.APPLIED
        }

        edit.remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_ISSUED_AT_MS)
            .putLong(KEY_LAST_VERSION, version)
            .apply()
        return ApplyResult.WIPED
    }

    /**
     * Bounded-staleness purge: if the watch hasn't heard a fresh credential
     * push within 7 days, wipe everything (including the version counter)
     * so the next phone push lands cleanly. Also catches the "watch app
     * upgraded ahead of phone app" case where credentials exist but
     * `issuedAtMs == 0` — those orphan creds have no TTL anchor, so we
     * treat them as expired.
     */
    private fun purgeIfStale() {
        val username = prefs.getString(KEY_USERNAME, null) ?: return
        if (username.isEmpty()) return
        val issuedAtMs = prefs.getLong(KEY_ISSUED_AT_MS, 0L)
        val now = System.currentTimeMillis()
        val isOrphan = issuedAtMs <= 0L
        val isExpired = !isOrphan && now - issuedAtMs > CREDENTIAL_TTL_MS
        if (isOrphan || isExpired) {
            Log.i(TAG, "purging stale credentials (orphan=$isOrphan, expired=$isExpired)")
            clear()
        }
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
        /** Bounded staleness window for cached credentials — see [purgeIfStale]. */
        const val CREDENTIAL_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

        private const val PREFS_NAME = "wear_library_credentials"
        private const val KEY_USERNAME = "library_username"
        private const val KEY_PASSWORD = "library_password"
        private const val KEY_TOKEN = "library_token"
        private const val KEY_TOKEN_EXPIRY = "library_token_expiry"

        // Phone composed-at timestamp; anchors the 7-day TTL.
        private const val KEY_ISSUED_AT_MS = "library_issued_at_ms"

        // Last applied phone-side epoch — anti-replay guard. Survives a
        // `.wipe`-style push so a delayed earlier `.set` can't re-credential
        // the watch after logout.
        private const val KEY_LAST_VERSION = "library_last_version"

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
                try {
                    attempt()
                } catch (retry: Exception) {
                    // Mirror the phone-side CredentialManager: a second failure
                    // means the keystore itself is unusable — surface a typed,
                    // logged error instead of whatever raw exception attempt()
                    // happened to throw.
                    Log.e(TAG, "EncryptedSharedPreferences retry failed", retry)
                    throw SecurityException("Cannot create encrypted credential storage", retry)
                }
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
