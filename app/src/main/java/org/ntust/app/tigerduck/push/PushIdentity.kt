package org.ntust.app.tigerduck.push

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable identity used by the push-server registration: a per-install UUID
 * (`device_id`) plus the most recently signed-in NTUST id (`user_id`).
 * Mirrors PushIdentity.swift on iOS so the server treats Android the same way.
 */
@Singleton
class PushIdentity @Inject constructor(
    private val prefs: AppPreferences,
) {
    fun deviceId(): String =
        prefs.getOrCreateString(KEY_DEVICE_ID) { UUID.randomUUID().toString() }

    fun userId(): String? = prefs.getString(KEY_USER_ID)

    fun setUserId(id: String) {
        prefs.putString(KEY_USER_ID, id)
    }

    private companion object {
        const val KEY_DEVICE_ID = "push.device_id"
        const val KEY_USER_ID = "push.user_id"
    }
}
