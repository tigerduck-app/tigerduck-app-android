package org.ntust.app.tigerduck.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play-flavor wiring for Firebase Analytics. Auto-collected events (sessions,
 * first_open, screen_view) flow without any caller action; this class only
 * exists so feature code can record custom events from `main/`.
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
        val bundle = Bundle().apply {
            params.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is String -> putString(key, value)
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Float -> putDouble(key, value.toDouble())
                    is Boolean -> putLong(key, if (value) 1L else 0L)
                    else -> putString(key, value.toString())
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
}
