package org.ntust.app.tigerduck.push

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent device identity derived from Settings.Secure.ANDROID_ID.
 *
 * ANDROID_ID is unique per (device, app signing key) and persists until
 * factory reset. We derive a deterministic UUID v5 from it so the format
 * is consistent with iOS (standard UUID string).
 */
@Singleton
class PushIdentity @Inject constructor(
    private val prefs: AppPreferences,
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val KEY_UUID = "push.device_uuid"
        // RFC 4122 "URL" namespace — used as the namespace for UUID v5
        private val NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

        /** Matches the `sw600dp` resource qualifier — see [deviceClass]. */
        private const val TABLET_SW_DP = 600
    }

    /**
     * Returns the persistent device UUID, generating it from ANDROID_ID
     * on first call or if the cached value is missing (e.g. after
     * uninstall/reinstall clears SharedPreferences).
     */
    fun uuid(): String = prefs.getOrCreateString(KEY_UUID) {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        uuid5(NAMESPACE, "tigerduck:$androidId").toString()
    }

    /**
     * The form factor an operator targets this device by: `"android"` or
     * `"android_tablet"`.
     *
     * Apple reports iphone / ipad / mac and gets three separate target
     * classes out of it; Android reported one flat value, so a tablet could
     * only ever be reached by addressing every Android device at once.
     *
     * 600dp of smallest width is the same threshold the resource system uses
     * to pick `sw600dp` layouts, so "tablet" here means what it means
     * everywhere else in the app rather than a second, privately-invented
     * definition. Read from the current configuration rather than cached: a
     * foldable changes class when it opens, and the next announce should say
     * so.
     */
    fun deviceClass(): String =
        if (context.resources.configuration.smallestScreenWidthDp >= TABLET_SW_DP) {
            "android_tablet"
        } else {
            "android"
        }

    /**
     * UUID v5 (SHA-1 name-based) per RFC 4122 §4.3.
     */
    private fun uuid5(namespace: UUID, name: String): UUID {
        val namespaceBytes = java.nio.ByteBuffer.allocate(16).run {
            putLong(namespace.mostSignificantBits)
            putLong(namespace.leastSignificantBits)
            array()
        }
        val digest = java.security.MessageDigest.getInstance("SHA-1").run {
            update(namespaceBytes)
            update(name.toByteArray(Charsets.UTF_8))
            digest()
        }
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte() // version 5
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte() // variant RFC4122
        val buf = java.nio.ByteBuffer.wrap(digest, 0, 16)
        return UUID(buf.long, buf.long)
    }
}
