package org.ntust.app.tigerduck.network.tls

import android.content.SharedPreferences
import org.ntust.app.tigerduck.shared.tls.PinAcceptance
import org.ntust.app.tigerduck.shared.tls.PinAcceptances

/**
 * The student's Continues on pin mismatches (TLS pins spec, A6). An acceptance lasts until the
 * build number changes (D4): on load, any made under another versionCode is dropped. Kept in a
 * preferences file of its own, apart from account data, so a sign-out and a full reset keep it; the
 * app backs nothing up, so an uninstall takes it. A Cancel stores nothing here: the next launch asks
 * again (tigerduck-app#958).
 */
class PinAcceptanceStore(
    private val prefs: SharedPreferences,
    private val versionCode: Long,
) {

    @Volatile
    private var acceptances: List<PinAcceptance> = PinAcceptances.decode(prefs.getString(KEY_ACCEPTANCES, null))
        .filter { it.appVersion == versionCode }
        .also { kept -> prefs.edit().putString(KEY_ACCEPTANCES, PinAcceptances.encode(kept)).apply() }

    /** The acceptances made under the running build. */
    val all: List<PinAcceptance> get() = acceptances

    /** Trusts [spkis] for [setId] until the build changes, together with what it already trusted. */
    @Synchronized
    fun accept(setId: String, spkis: Set<String>, now: Long): List<PinAcceptance> {
        val earlier = acceptances.filter { it.setId == setId }.flatMap { it.spkis }.toSet()
        val next = acceptances.filter { it.setId != setId } + PinAcceptance(setId, earlier + spkis, versionCode, now)
        prefs.edit().putString(KEY_ACCEPTANCES, PinAcceptances.encode(next)).apply()
        acceptances = next
        return next
    }

    companion object {
        const val FILE = "tls_pin_acceptances"
        private const val KEY_ACCEPTANCES = "acceptances"
    }
}
