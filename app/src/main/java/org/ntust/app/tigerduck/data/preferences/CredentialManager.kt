@file:Suppress("DEPRECATION")

package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.shared.LibraryAccountGate
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.StoredPasswordSchedule
import org.ntust.app.tigerduck.shared.sameStudentId
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialManager private constructor(
    private val prefs: SharedPreferences,
    encrypted: Boolean,
    recreated: Boolean,
) : LibraryCredentialStore, MailCredentialStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(openEncrypted(context))

    private constructor(opened: Opened) : this(opened.prefs, opened.encrypted, opened.recreated)

    /** [prefs] as the store, unencrypted: for tests, which cannot reach the Android keystore. */
    @VisibleForTesting
    internal constructor(prefs: SharedPreferences) : this(prefs, encrypted = false, recreated = false)

    val isEncrypted: Boolean = encrypted

    /**
     * True when [openEncrypted] had to delete the backing file and
     * rebuild an empty credential store (keystore corruption, restore-to-new-
     * device, etc). Callers can use this signal to prompt the user to re-login
     * rather than silently presenting an unauthenticated app.
     */
    val wasRecreatedDueToCorruption: Boolean = recreated

    var ntustStudentId: String?
        get() = prefs.getString("ntust_student_id", null)
        set(value) = if (value != null)
            prefs.edit().putString("ntust_student_id", value).apply()
        else
            prefs.edit().remove("ntust_student_id").apply()

    var ntustPassword: String?
        get() = prefs.getString("ntust_password", null)
        set(value) = if (value != null)
            prefs.edit().putString("ntust_password", value).apply()
        else
            prefs.edit().remove("ntust_password").apply()

    override val libraryUsername: String?
        get() = prefs.getString("library_username", null)

    override val libraryPassword: String?
        get() = prefs.getString("library_password", null)

    override val libraryToken: String?
        get() = prefs.getString("library_token", null)

    override val libraryTokenExpiry: Long
        get() = prefs.getLong("library_token_expiry", 0L)

    override val isLibraryTokenValid: Boolean
        get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry

    private val libraryAccount = LibraryAccountGate()

    override val libraryAccountGeneration: Int get() = libraryAccount.current

    override fun saveLibrarySignIn(
        generation: Int,
        username: String,
        password: String,
        token: String?,
        tokenExpiry: Long,
        byUser: Boolean,
        stillCurrent: () -> Boolean,
    ): Boolean = libraryAccount.saveIfCurrent(generation, byUser, { libraryUsername }, username, stillCurrent) {
        prefs.edit()
            .putString("library_username", username)
            .putString("library_password", password)
            .putString("library_token", token)
            .putLong("library_token_expiry", tokenExpiry)
            // Credentials that work, so the app may sign in with them again at once.
            .remove(KEY_LIBRARY_PASSWORD_REFUSALS)
            .remove(KEY_LIBRARY_PASSWORD_REFUSED_AT)
            .apply()
    }

    init {
        takeUpLibraryLatch(prefs)
    }

    override val libraryPasswordRefusals: Int
        get() = prefs.getInt(KEY_LIBRARY_PASSWORD_REFUSALS, 0)

    override val libraryPasswordRefusedAt: Long
        get() = prefs.getLong(KEY_LIBRARY_PASSWORD_REFUSED_AT, 0L)

    override fun refuseLibraryPassword(generation: Int, password: String, at: Long): Boolean =
        libraryAccount.whileCurrent(generation) {
            if (libraryPassword != password) return@whileCurrent false
            // Written through before anything reads it: a process the system ends
            // right after must not start the next one without the wait.
            prefs.edit()
                .putInt(KEY_LIBRARY_PASSWORD_REFUSALS, libraryPasswordRefusals + 1)
                .putLong(KEY_LIBRARY_PASSWORD_REFUSED_AT, at)
                .commit()
            true
        } ?: false

    /** Long-lived Moodle Mobile wstoken, obtained via the OIDC launch flow. */
    var moodleToken: String?
        get() = prefs.getString("moodle_token", null)
        set(value) = if (value != null)
            prefs.edit().putString("moodle_token", value).apply()
        else
            prefs.edit().remove("moodle_token").apply()

    /**
     * Stores the Moodle [token], with the [privateToken] harvested with it, only while [studentId]
     * is still the stored account. Under the same lock as [clearNtustCredentials] and [clearAll],
     * so a sign-out cannot land between the check and the store and leave the departing account's
     * tokens for the next one.
     */
    @Synchronized
    fun storeMoodleTokenIfSignedIn(studentId: String, token: String, privateToken: String?): Boolean {
        if (ntustStudentId != studentId) return false
        moodleToken = token
        moodlePrivateToken = privateToken
        return true
    }

    /**
     * The private token Moodle harvests with [moodleToken], which only ever asks Moodle for a
     * one-time autologin key (`tool_mobile_get_autologin_key`). A secret: never logged, and sent
     * nowhere but that request to the Moodle site. Cleared with the NTUST account.
     */
    var moodlePrivateToken: String?
        get() = prefs.getString("moodle_private_token", null)
        set(value) = if (value != null)
            prefs.edit().putString("moodle_private_token", value).apply()
        else
            prefs.edit().remove("moodle_private_token").apply()

    // ── v3 JWT tokens ────────────────────────────────────────────────────────

    val v3Tokens = V3TokenStore(prefs)
    val pendingUnregister = PendingUnregisterPrefs(prefs)

    @Synchronized
    fun clearNtustCredentials() {
        prefs.edit()
            .remove("ntust_student_id")
            .remove("ntust_password")
            .remove("moodle_token")
            .remove("moodle_private_token")
            // Not the backend session, nor its refresh token's send count:
            // AuthTokenManager.logout() drops them under its own lock, and hands
            // the session to the sign-out's unregister as it does.
            .apply()
    }

    fun clearLibraryCredentials() {
        // A library sign-in still running for this account then stores nothing.
        libraryAccount.signOut {
            prefs.edit()
                .remove("library_username")
                .remove("library_password")
                .remove("library_token")
                .remove("library_token_expiry")
                .remove(KEY_LIBRARY_PASSWORD_REFUSALS)
                .remove(KEY_LIBRARY_PASSWORD_REFUSED_AT)
                .apply()
        }
    }

    // Moved by each school sign-out, under the library account's lock.
    private var schoolAccount = 0

    /**
     * Moved by each school sign-out, so work begun for the school account,
     * such as a bulletin fetch, stores nothing once it has left (see
     * [isSchoolAccount]). The library account is not touched by it.
     */
    val schoolAccountGeneration: Int get() = libraryAccount.atomically { schoolAccount }

    /** Whether no school sign-out has come since [read] was read from [schoolAccountGeneration]. */
    fun isSchoolAccount(read: Int): Boolean = libraryAccount.atomically { schoolAccount == read }

    /**
     * A school sign-out's credentials: the school account goes, and the
     * library account stays, whoever's it is, as each account signs in and
     * out by itself. Moves [schoolAccountGeneration], under the library's
     * lock as [isSchoolAccount] reads it.
     */
    fun signOutSchoolAccount() {
        clearNtustCredentials()
        libraryAccount.atomically { schoolAccount++ }
    }

    // ── School mail — its own account, never shared with the NTUST or library one ──

    override var mailStudentId: String?
        get() = prefs.getString("mail_student_id", null)
        set(value) = if (value != null)
            prefs.edit().putString("mail_student_id", value).apply()
        else
            prefs.edit().remove("mail_student_id").apply()

    override var mailPassword: String?
        get() = prefs.getString("mail_password", null)
        set(value) = if (value != null)
            prefs.edit().putString("mail_password", value).apply()
        else
            prefs.edit().remove("mail_password").apply()

    override fun clearMailCredentials() {
        prefs.edit()
            .remove("mail_student_id")
            .remove("mail_password")
            .apply()
    }

    /** Wipe every credential key. Used by the full-reset flow. */
    @Synchronized
    fun clearAll() {
        // All but the device unregister a sign-out still owes; see PendingUnregisterPrefs.
        libraryAccount.signOut { clearAllButOwedUnregister(prefs) }
    }

    private class Opened(val prefs: SharedPreferences, val encrypted: Boolean, val recreated: Boolean)

    companion object {
        private const val PREFS_NAME = "tigerduck_credentials"
        private const val KEY_LIBRARY_PASSWORD_REFUSALS = "library_password_refusals"
        private const val KEY_LIBRARY_PASSWORD_REFUSED_AT = "library_password_refused_at"

        /** Before the retries, a refusal held every automatic sign-in until the user signed in again. */
        private const val KEY_LIBRARY_PASSWORD_REFUSED = "library_password_refused"

        /**
         * Moves a refusal kept by a build before the retries onto the count with
         * the quick ones spent: one try at once, then one every 6 hours, as on
         * the iPhone (`LibraryPasswordLatchRetryMigration`).
         */
        private fun takeUpLibraryLatch(prefs: SharedPreferences) {
            if (!prefs.contains(KEY_LIBRARY_PASSWORD_REFUSED)) return
            val edit = prefs.edit().remove(KEY_LIBRARY_PASSWORD_REFUSED)
            if (prefs.getBoolean(KEY_LIBRARY_PASSWORD_REFUSED, false)) {
                edit.putInt(KEY_LIBRARY_PASSWORD_REFUSALS, StoredPasswordSchedule.quickRetries)
                    .putLong(KEY_LIBRARY_PASSWORD_REFUSED_AT, 0L)
            }
            edit.commit()
        }

        private fun openEncrypted(context: Context): Opened {
            fun attempt(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            val (prefs, recreated) = openOrReset(
                attempt = ::attempt,
                reset = { runCatching { context.deleteSharedPreferences(PREFS_NAME) } },
            )
            return Opened(prefs, encrypted = true, recreated = recreated)
        }
    }
}

/** How many times the store is opened before it is deleted, and the pause between the tries. */
private const val OPEN_TRIES = 3
private const val OPEN_RETRY_PAUSE_MS = 200L

/**
 * Opens the credential store with [attempt], [OPEN_TRIES] times [OPEN_RETRY_PAUSE_MS] apart, and
 * only then lets [reset] delete it and opens it once more; says whether it had to. The keystore
 * can refuse a call for a moment, busy or still starting after a boot, and deleting the store
 * then signed the user out for nothing. A corrupted file (e.g. after a system restore onto a
 * device whose keystore doesn't hold the original master key) refuses every try, so the user
 * loses only their stored credentials, not the whole app.
 */
internal fun <T> openOrReset(
    attempt: () -> T,
    reset: () -> Unit,
    pause: (Long) -> Unit = { Thread.sleep(it) },
): Pair<T, Boolean> {
    var failure: Exception? = null
    repeat(OPEN_TRIES) { index ->
        if (index > 0) pause(OPEN_RETRY_PAUSE_MS)
        try {
            return attempt() to false
        } catch (e: Exception) {
            failure = e
        }
    }
    val last = checkNotNull(failure)
    android.util.Log.w(
        "CredentialManager",
        "EncryptedSharedPreferences unusable; resetting: ${last.logSummary()}"
    )
    reset()
    return try {
        attempt() to true
    } catch (retry: Exception) {
        android.util.Log.e(
            "CredentialManager",
            "EncryptedSharedPreferences retry failed: ${retry.logSummary()}"
        )
        throw SecurityException("Cannot create encrypted credential storage", retry)
    }
}
