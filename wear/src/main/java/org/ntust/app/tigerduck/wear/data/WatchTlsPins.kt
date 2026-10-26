package org.ntust.app.tigerduck.wear.data

import android.content.Context
import android.content.SharedPreferences
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.shared.tls.PinAcceptances
import org.ntust.app.tigerduck.shared.tls.PinDocument
import org.ntust.app.tigerduck.shared.tls.PinDocumentKeys
import org.ntust.app.tigerduck.shared.tls.PinDocumentResult
import org.ntust.app.tigerduck.shared.tls.PinDocumentVerifier
import org.ntust.app.tigerduck.shared.tls.PinMismatchResolver
import org.ntust.app.tigerduck.shared.tls.PinPolicy
import org.ntust.app.tigerduck.shared.tls.PinState
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.wear.BuildConfig
import java.util.Base64

/**
 * The watch's TLS pins (TLS pins spec, A8): what the phone forwards, checked again here. The
 * envelope is verified with the same embedded keys, and only one that supersedes the one held
 * is taken; a push not newer than the last is dropped. An acceptance applies only while this
 * watch's build is the one the phone made it under, the phone's versionCode plus
 * [WearProtocol.TlsPins.WATCH_VERSION_CODE_OFFSET]. The watch never prompts: a mismatch fails,
 * and the library page says to open TigerDuck on the phone.
 */
class WatchTlsPins internal constructor(
    private val prefs: SharedPreferences,
    private val verifier: PinDocumentVerifier,
    /** The phone build this watch build pairs with. */
    phoneBuild: Long,
) {
    val state = PinState(
        PinPolicy(
            document = prefs.getString(KEY_ENVELOPE, null)?.let(::verified),
            acceptances = PinAcceptances.decode(prefs.getString(KEY_ACCEPTANCES, null)),
            appVersion = phoneBuild,
        ),
    )

    /** The trust the watch's library client uses: no retry, no prompt. */
    val tls: PinnedTls by lazy { PinnedTls.forPlatform(state, onMismatch = {}, resolver = PinMismatchResolver { _, _ -> false }) }

    /** Applies a phone push; a [version] not above the last applied is dropped. */
    @Synchronized
    fun applyPush(envelope: ByteArray?, acceptances: String?, version: Long) {
        if (version <= prefs.getLong(KEY_LAST_VERSION, 0L)) return
        val editor = prefs.edit().putLong(KEY_LAST_VERSION, version)
        val document = envelope?.let { bytes ->
            (verifier.verify(bytes) as? PinDocumentResult.Verified)?.document
                ?.takeIf { PinDocumentVerifier.supersedes(it, state.policy.document) }
        }
        if (document != null) editor.putString(KEY_ENVELOPE, Base64.getEncoder().encodeToString(document.envelope))
        val accepted = PinAcceptances.decode(acceptances)
        editor.putString(KEY_ACCEPTANCES, PinAcceptances.encode(accepted)).apply()
        state.update { it.with(document = document ?: it.document, acceptances = accepted) }
    }

    /** The stored envelope, verified again; one past its expiry still keeps its serial's place. */
    private fun verified(base64: String): PinDocument? {
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
        return (verifier.verify(bytes, allowExpired = true) as? PinDocumentResult.Verified)?.document
    }

    companion object {
        private const val PREFS_NAME = "wear_tls_pins"
        private const val KEY_ENVELOPE = "envelope"
        private const val KEY_ACCEPTANCES = "acceptances"
        private const val KEY_LAST_VERSION = "last_version"

        @Volatile
        private var instance: WatchTlsPins? = null

        fun get(context: Context): WatchTlsPins = instance ?: synchronized(this) {
            instance ?: WatchTlsPins(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                PinDocumentVerifier(PinDocumentKeys.keys),
                BuildConfig.VERSION_CODE.toLong() - WearProtocol.TlsPins.WATCH_VERSION_CODE_OFFSET,
            ).also { instance = it }
        }
    }
}
