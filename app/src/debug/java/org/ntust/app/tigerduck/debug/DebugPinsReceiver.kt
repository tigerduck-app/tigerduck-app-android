package org.ntust.app.tigerduck.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.network.tls.PinAcceptanceStore
import org.ntust.app.tigerduck.network.tls.PinManager
import java.time.Instant

/**
 * Drives the TLS pins over `adb shell am broadcast`, so the mismatch prompt (TLS pins spec, A6)
 * can be checked end to end before TigerSync publishes a document:
 *
 * - `INJECT_PIN_DOCUMENT`: holds a [DebugPinDocument] in memory, so a school chain may be offered;
 * - `SIMULATE_PIN_MISMATCH`: the `set` extra's pins (`ntust` by default) become one no chain has,
 *   as the Developer page's switch does;
 * - `CLEAR_PINS`: drops all of that and every acceptance, until the app restarts.
 *
 * **Debug builds only**, as [DebugClockReceiver] is: declared in the debug manifest, so no
 * release build has it.
 */
class DebugPinsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun pinManager(): PinManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pins = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).pinManager()
        when (intent.action) {
            ACTION_INJECT_DOCUMENT -> {
                val made = DebugPinDocument.make(pins.state.policy.document?.serial ?: 0, Instant.now())
                pins.state.update { it.with(document = made.document) }
                Log.i(TAG, "holding a debug pin document, serial ${made.document.serial}")
            }

            ACTION_SIMULATE_MISMATCH -> {
                val set = intent.getStringExtra(EXTRA_SET) ?: "ntust"
                pins.simulateMismatch(set)
                Log.i(TAG, "simulating a pin mismatch for $set")
            }

            ACTION_CLEAR -> {
                context.getSharedPreferences(PinAcceptanceStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
                pins.state.update { it.with(document = null, acceptances = emptyList(), simulatedMismatch = null) }
                Log.i(TAG, "cleared the debug pins and every acceptance")
            }
        }
    }

    private companion object {
        const val TAG = "DebugPins"
        const val ACTION_INJECT_DOCUMENT = "org.ntust.app.tigerduck.debug.INJECT_PIN_DOCUMENT"
        const val ACTION_SIMULATE_MISMATCH = "org.ntust.app.tigerduck.debug.SIMULATE_PIN_MISMATCH"
        const val ACTION_CLEAR = "org.ntust.app.tigerduck.debug.CLEAR_PINS"
        const val EXTRA_SET = "set"
    }
}
