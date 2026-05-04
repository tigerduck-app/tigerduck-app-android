package org.ntust.app.tigerduck.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play-flavor wiring for Firebase Analytics. Collection is gated off in the
 * play manifest (`firebase_analytics_collection_enabled=false`) so neither
 * auto-collected events (session_start, first_open, screen_view) nor custom
 * events leave the device until [setEnabled] is called with `true` after the
 * user consents. Firebase persists that choice across launches.
 *
 * When `google-services.json` is absent at build time `FirebaseApp.getApps`
 * returns empty and every call becomes a silent no-op, mirroring the
 * FcmBootstrap pattern so debug builds without Firebase config still work.
 *
 * The fdroid variant ships a stub at the same FQN so callers in `main/` need
 * no conditional code.
 *
 * TODO: no caller wires `setEnabled(true)` yet. Until a consent UI flips it on,
 * the play flavor remains in the off-by-default state from the manifest. Add a
 * preference + Settings toggle (or onboarding consent step) to actually enable
 * collection.
 */
@Singleton
class AnalyticsLogger @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val analytics: FirebaseAnalytics? by lazy {
        if (FirebaseApp.getApps(context).isEmpty()) null
        else FirebaseAnalytics.getInstance(context)
    }

    fun log(event: String, params: Map<String, Any?> = emptyMap()) {
        val fa = analytics ?: return
        if (!validateName("event name", event, MAX_NAME_CHARS)) return
        val entries = if (validate("event '$event' has ${params.size} params (max $MAX_PARAMS)") { params.size <= MAX_PARAMS }) {
            params.entries
        } else {
            params.entries.take(MAX_PARAMS)
        }
        val bundle = Bundle().apply {
            entries.forEach { (key, value) ->
                if (!validateName("param key on '$event'", key, MAX_NAME_CHARS)) return@forEach
                when (value) {
                    null -> Unit
                    is String -> putString(key, truncateValue(event, key, value))
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Float -> putDouble(key, value.toDouble())
                    is Boolean -> putLong(key, if (value) 1L else 0L)
                    else -> putString(key, truncateValue(event, key, value.toString()))
                }
            }
        }
        fa.logEvent(event, bundle)
    }

    fun setUserProperty(name: String, value: String?) {
        val fa = analytics ?: return
        if (!validateName("user property name", name, MAX_USER_PROPERTY_NAME_CHARS)) return
        val truncated = value?.let { safeTruncate(it, MAX_USER_PROPERTY_VALUE_CHARS) }
        if (value != null && value.length > MAX_USER_PROPERTY_VALUE_CHARS) {
            Log.w(TAG, "user property '$name' value of ${value.length} chars exceeds $MAX_USER_PROPERTY_VALUE_CHARS")
        }
        fa.setUserProperty(name, truncated)
    }

    /**
     * Toggles Firebase Analytics collection. When disabling, also resets the
     * App Instance ID so consent revocation severs the link to previously
     * transmitted data — required for a clean GDPR opt-out.
     */
    fun setEnabled(enabled: Boolean) {
        val fa = analytics ?: return
        fa.setAnalyticsCollectionEnabled(enabled)
        if (!enabled) fa.resetAnalyticsData()
    }

    private fun truncateValue(event: String, key: String, value: String): String {
        if (value.length <= MAX_VALUE_CHARS) return value
        Log.w(TAG, "param '$key' on '$event' value of ${value.length} chars exceeds $MAX_VALUE_CHARS")
        return safeTruncate(value, MAX_VALUE_CHARS)
    }

    /**
     * Validates a Firebase event / param / user-property name against all four
     * silent-rejection rules (length, identifier syntax, reserved prefix,
     * non-empty). Logs a warning and returns false on any violation.
     */
    private fun validateName(label: String, name: String, maxChars: Int): Boolean {
        if (name.isEmpty()) {
            Log.w(TAG, "$label is empty")
            return false
        }
        if (name.length > maxChars) {
            Log.w(TAG, "$label '$name' exceeds $maxChars chars")
            return false
        }
        if (!isValidIdentifier(name)) {
            Log.w(TAG, "$label '$name' must start with a letter and contain only [A-Za-z0-9_]")
            return false
        }
        if (isReservedPrefix(name)) {
            Log.w(TAG, "$label '$name' uses a reserved Firebase prefix (firebase_/google_/ga_)")
            return false
        }
        return true
    }

    private fun isValidIdentifier(name: String): Boolean =
        name.matches(IDENTIFIER_REGEX)

    private fun isReservedPrefix(name: String): Boolean =
        RESERVED_PREFIXES.any { name.startsWith(it) }

    /**
     * Truncates [value] to at most [limit] UTF-16 code units, backing off by
     * one if the cut would split a surrogate pair (which would leave an
     * unpaired high surrogate and corrupt non-BMP characters like emoji).
     */
    private fun safeTruncate(value: String, limit: Int): String {
        if (value.length <= limit) return value
        val end = if (Character.isHighSurrogate(value[limit - 1])) limit - 1 else limit
        return value.substring(0, end)
    }

    private inline fun validate(message: String, predicate: () -> Boolean): Boolean {
        val ok = predicate()
        if (!ok) Log.w(TAG, message)
        return ok
    }

    private companion object {
        const val TAG = "AnalyticsLogger"
        // Firebase Analytics hard limits — silently dropped/truncated upstream.
        const val MAX_NAME_CHARS = 40
        const val MAX_VALUE_CHARS = 100
        const val MAX_PARAMS = 25
        // User properties have stricter limits than events.
        const val MAX_USER_PROPERTY_NAME_CHARS = 24
        const val MAX_USER_PROPERTY_VALUE_CHARS = 36
        val RESERVED_PREFIXES = listOf("firebase_", "google_", "ga_")
        val IDENTIFIER_REGEX = Regex("[a-zA-Z][a-zA-Z0-9_]*")
    }
}
