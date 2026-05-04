package org.ntust.app.tigerduck.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.ntust.app.tigerduck.BuildConfig

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
        if (!check("event name '$event' exceeds $MAX_NAME_CHARS chars") { event.length <= MAX_NAME_CHARS }) {
            return
        }
        val entries = if (check("event '$event' has ${params.size} params (max $MAX_PARAMS)") { params.size <= MAX_PARAMS }) {
            params.entries
        } else {
            params.entries.take(MAX_PARAMS)
        }
        val bundle = Bundle().apply {
            entries.forEach { (key, value) ->
                if (!check("param key '$key' on '$event' exceeds $MAX_NAME_CHARS chars") { key.length <= MAX_NAME_CHARS }) {
                    return@forEach
                }
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
        analytics?.setUserProperty(name, value)
    }

    fun setEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    private fun truncateValue(event: String, key: String, value: String): String {
        if (value.length <= MAX_VALUE_CHARS) return value
        check("param '$key' on '$event' value of ${value.length} chars exceeds $MAX_VALUE_CHARS") { false }
        return value.substring(0, MAX_VALUE_CHARS)
    }

    /** Throws in debug, logs and returns the predicate result in release. */
    private inline fun check(message: String, predicate: () -> Boolean): Boolean {
        val ok = predicate()
        if (!ok) {
            if (BuildConfig.DEBUG) error("AnalyticsLogger: $message")
            Log.w(TAG, message)
        }
        return ok
    }

    private companion object {
        const val TAG = "AnalyticsLogger"
        // Firebase Analytics hard limits — silently dropped/truncated upstream.
        const val MAX_NAME_CHARS = 40
        const val MAX_VALUE_CHARS = 100
        const val MAX_PARAMS = 25
    }
}
