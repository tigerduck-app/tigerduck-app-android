package org.ntust.app.tigerduck.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Drives the debug clock override over `adb shell am broadcast`, so a script
 * can put the app "in class" without a human tapping through Settings →
 * Developer.
 *
 * This exists because everything interesting about this app is a function of
 * the current time — ongoing-class UI, next-class resolution, the Live
 * Update, widgets, and every AlarmManager-backed notification. The in-app
 * picker was the only way to move that clock, which made all of it
 * hand-driven and therefore untestable in CI or from a shell script.
 *
 * It delegates to [DebugClockController.setOverride], the same entry point
 * the settings screen uses, so the broadcast path cannot drift from the UI
 * path: both persist the override, mirror it to a paired watch, and
 * reschedule every alarm against the new clock.
 *
 * **Debug builds only.** The `<receiver>` is declared in
 * `app/src/debug/AndroidManifest.xml`, so it is absent from the merged
 * manifest of `playRelease` / `fdroidRelease` — there is no release
 * component to guard. It is exported, because a broadcast from the `shell`
 * uid cannot reach an unexported receiver; on a debug build that also means
 * any app on the device could send it, which is an acceptable trade for a
 * build that already ships a developer menu.
 */
class DebugClockReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun debugClockController(): DebugClockController
    }

    override fun onReceive(context: Context, intent: Intent) {
        val controller = EntryPointAccessors
            .fromApplication(context.applicationContext, Deps::class.java)
            .debugClockController()

        when (intent.action) {
            ACTION_CLEAR -> {
                controller.setOverride(null)
                Log.i(TAG, "cleared clock override; back to real time")
            }

            ACTION_SET -> {
                val instant = resolveInstant(intent)
                if (instant == null) {
                    Log.e(TAG, "ignoring SET: pass --es at \"$AT_FORMAT_HINT\" or --el instant <epochMillis>")
                    return
                }
                // Default frozen: a script that sets a time and then asserts
                // on what it sees wants that time to still be true when the
                // assertion runs.
                val frozen = intent.getBooleanExtra(EXTRA_FROZEN, true)
                controller.setOverride(
                    ClockOverride(
                        instantMillis = instant,
                        frozen = frozen,
                        savedAtRealMillis = System.currentTimeMillis(),
                    )
                )
                Log.i(
                    TAG,
                    "clock override -> ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(instant), ZONE)}" +
                            " ${ZONE.id} (${if (frozen) "frozen" else "ticking"})",
                )
            }

            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    /**
     * `instant` wins when both are present: it is unambiguous, whereas `at`
     * has to be interpreted in a zone.
     */
    private fun resolveInstant(intent: Intent): Long? {
        if (intent.hasExtra(EXTRA_INSTANT)) {
            val raw = intent.getLongExtra(EXTRA_INSTANT, 0L)
            return raw.takeIf { it > 0L }
        }
        val at = intent.getStringExtra(EXTRA_AT) ?: return null
        return parseLocal(at)?.atZone(ZONE)?.toInstant()?.toEpochMilli()
    }

    /**
     * Accepts `2026-09-09T10:44`, `2026-09-09 10:44`, `2026-09-09T10:44:30`
     * and a bare `2026-09-09` (midnight). The space form matters because it
     * is what a shell user types without thinking, and `am broadcast` passes
     * it through happily once quoted.
     */
    private fun parseLocal(raw: String): LocalDateTime? {
        val normalized = raw.trim().replace(' ', 'T')
        return try {
            LocalDateTime.parse(normalized)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(normalized).atStartOfDay()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private companion object {
        const val TAG = "DebugClock"
        const val ACTION_SET = "org.ntust.app.tigerduck.debug.SET_CLOCK"
        const val ACTION_CLEAR = "org.ntust.app.tigerduck.debug.CLEAR_CLOCK"
        const val EXTRA_AT = "at"
        const val EXTRA_INSTANT = "instant"
        const val EXTRA_FROZEN = "frozen"
        const val AT_FORMAT_HINT = "2026-09-09T10:44"

        // Matches DebugViewModel: the in-app picker means Taipei local time,
        // and a broadcast that meant something else would silently disagree
        // with the UI it is standing in for.
        val ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
