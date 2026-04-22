@file:Suppress("DEPRECATION")

package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialManager @Inject constructor(@ApplicationContext context: Context) {

    var isEncrypted: Boolean = false
        private set

    private val prefs: SharedPreferences = run {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "tigerduck_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { isEncrypted = true }
        } catch (e: Exception) {
            android.util.Log.e("CredentialManager", "EncryptedSharedPreferences failed", e)
            throw SecurityException("Cannot create encrypted credential storage", e)
        }
    }

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

    var libraryUsername: String?
        get() = prefs.getString("library_username", null)
        set(value) = if (value != null)
            prefs.edit().putString("library_username", value).apply()
        else
            prefs.edit().remove("library_username").apply()

    var libraryPassword: String?
        get() = prefs.getString("library_password", null)
        set(value) = if (value != null)
            prefs.edit().putString("library_password", value).apply()
        else
            prefs.edit().remove("library_password").apply()

    var libraryToken: String?
        get() = prefs.getString("library_token", null)
        set(value) = if (value != null)
            prefs.edit().putString("library_token", value).apply()
        else
            prefs.edit().remove("library_token").apply()

    var libraryTokenExpiry: Long
        get() = prefs.getLong("library_token_expiry", 0L)
        set(value) = prefs.edit().putLong("library_token_expiry", value).apply()

    val isLibraryTokenValid: Boolean
        get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry

    /** Long-lived Moodle Mobile wstoken, obtained via the OIDC launch flow. */
    var moodleToken: String?
        get() = prefs.getString("moodle_token", null)
        set(value) = if (value != null)
            prefs.edit().putString("moodle_token", value).apply()
        else
            prefs.edit().remove("moodle_token").apply()

    fun clearNtustCredentials() {
        prefs.edit()
            .remove("ntust_student_id")
            .remove("ntust_password")
            .remove("moodle_token")
            .apply()
    }

    fun clearLibraryCredentials() {
        prefs.edit()
            .remove("library_username")
            .remove("library_password")
            .remove("library_token")
            .remove("library_token_expiry")
            .apply()
    }
}
