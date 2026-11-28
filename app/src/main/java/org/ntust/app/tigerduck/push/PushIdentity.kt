package org.ntust.app.tigerduck.push

import android.content.Context
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.util.logSummary
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the device secret and the backend's refusal of it, in the encrypted credential store.
 * [load] throws when the store cannot be read now, rather than answering that there is none.
 */
interface DeviceSecretStore {
    fun load(): String?

    /** Says whether the disk took it; one it did not is not kept. */
    fun save(secret: String): Boolean

    /** The backend refused the secret, for the next launch with no session to start over. */
    val refused: Boolean

    /** Says whether the disk took it. */
    fun recordRefusal(): Boolean
    fun clearRefusal()

    /** Drops the secret and the refusal together. */
    fun startOver()
}

/**
 * The device's id, and the secret that proves it to the backend's anonymous registration
 * (`device_secret`), as iOS's `PushIdentity`.
 *
 * The id is derived from Settings.Secure.ANDROID_ID, unique per (device, app signing key) until a
 * factory reset, as a UUID v5, the standard UUID string iOS sends too; a reinstall derives the
 * same one. The secret is 32 random bytes, minted at its first use and saved before any request
 * carries it, in the encrypted credential store beside the backend session. A reinstall loses it,
 * and the backend then refuses the new one for the registration the old one bound
 * (`device_secret_invalid`). That refusal is recorded, and the next launch with no backend session
 * stored, nor one a sign-out keeps until its unregister lands, mints a random id and a new secret
 * before anything reads the id: the backend keys a signed-in device by it, and a sign-out's
 * unregister deletes that device by it. Derived from ANDROID_ID again, the id would come back the
 * same. The new id is kept where the derived one was, so later launches read it.
 */
@Singleton
class PushIdentity internal constructor(
    private val prefs: AppPreferences,
    private val context: Context,
    private val secrets: DeviceSecretStore,
    /** Whether a backend session, or one a sign-out keeps, is stored; throws when it cannot be read. */
    private val storesSession: () -> Boolean,
) {
    @Inject
    constructor(prefs: AppPreferences, @ApplicationContext context: Context, credentials: CredentialManager) : this(
        prefs,
        context,
        credentials.deviceSecret,
        { credentials.v3Tokens.refreshToken != null || credentials.pendingUnregister.load().isNotEmpty() },
    )

    // Guarded by this: the id and the secret this process reads, each once it is read, and how
    // many requests have bound the secret in this process.
    private var heldId: String? = null
    private var heldSecret: String? = null
    private var bound = 0

    /** The device id, the same for the life of the process. */
    @Synchronized
    fun uuid(): String = heldId ?: run {
        startOverIfRefused()
        prefs.getOrCreateString(KEY_UUID) {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: UUID.randomUUID().toString()
            uuid5(NAMESPACE, "tigerduck:$androidId").toString()
        }.also { heldId = it }
    }

    /**
     * The secret sent as `device_secret`, minted and saved at its first use; null when a minted one
     * could not be saved, which no request may carry. Throws when the store cannot be read, and
     * mints nothing then: a stored secret replaced would never be accepted again.
     */
    @Synchronized
    fun secret(): String? {
        uuid()
        heldSecret?.let { return it }
        return (secrets.load() ?: mintDeviceSecret().takeIf(secrets::save)).also { heldSecret = it }
    }

    /** How many requests have bound the secret in this process, taken as a request goes out. */
    @Synchronized
    fun bindings(): Int = bound

    /**
     * Records the backend's refusal of the secret, for the next launch with no session, unless a
     * request bound the secret since [unlessBoundSince] ([bindings]): the refusal answered a
     * request that went out before. Says whether the disk took it.
     */
    @Synchronized
    fun recordRefusal(unlessBoundSince: Int? = null): Boolean {
        if (unlessBoundSince != null && bound != unlessBoundSince) return false
        return runCatching { secrets.recordRefusal() }
            .onFailure { Log.w(TAG, "device secret refusal not recorded: ${it.logSummary()}") }
            .getOrDefault(false)
    }

    /** A request that went through with the secret and a push token bound it: the refusal is over. */
    @Synchronized
    fun clearRefusal() {
        bound++
        runCatching { if (secrets.refused) secrets.clearRefusal() }
            .onFailure { Log.w(TAG, "device secret refusal not cleared: ${it.logSummary()}") }
    }

    /** Whether the next launch starts over: no session is stored now, nor one a sign-out keeps. */
    fun startsOverAtNextLaunch(): Boolean = runCatching { !storesSession() }.getOrDefault(false)

    // Before this process reads the id: a refused device with no session takes a new one.
    private fun startOverIfRefused() {
        val due = runCatching { secrets.refused && !storesSession() }.getOrDefault(false)
        if (!due) return
        val old = prefs.getString(KEY_UUID)
        // On disk before the refusal goes: a process ended between the two starts over again.
        if (prefs.commitString(KEY_UUID, UUID.randomUUID().toString())) {
            runCatching { secrets.startOver() }
                .onFailure { Log.w(TAG, "device secret not dropped: ${it.logSummary()}") }
            return
        }
        // The disk kept the old id, so this process does too, and a later launch starts over.
        Log.w(TAG, "new device id not written to disk")
        if (old != null) prefs.putString(KEY_UUID, old) else prefs.remove(KEY_UUID)
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

    companion object {
        /** Where the id is kept, in [AppPreferences], whose full reset keeps it. */
        internal const val KEY_UUID = "push.device_uuid"
        private const val TAG = "PushIdentity"
        // RFC 4122 "URL" namespace — used as the namespace for UUID v5
        private val NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

        /** Matches the `sw600dp` resource qualifier — see [deviceClass]. */
        private const val TABLET_SW_DP = 600
    }
}

/** 32 random bytes as unpadded base64url, the 43 characters the backend takes as `device_secret`. */
internal fun mintDeviceSecret(random: SecureRandom = SecureRandom()): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
