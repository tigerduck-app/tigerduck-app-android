package org.ntust.app.tigerduck.shared

/**
 * Wire constants for the phone↔watch Wearable Data Layer protocol. Defined in
 * `:shared` so the two ends can never drift — a typo or rename on one side
 * would silently break delivery with no error surfaced.
 */
object WearProtocol {

    /** Schedule snapshot pushed phone → watch. */
    object Schedule {
        const val PATH = "/tigerduck/schedule"
        const val KEY_COURSES = "courses"
        const val KEY_ACCENT = "accentHex"
        const val KEY_SYNCED_AT = "syncedAtMs"
        const val KEY_LOGGED_IN = "loggedIn"
        const val KEY_LANGUAGE = "languageTag"
    }

    /** One-shot message watch → phone asking for a fresh [Schedule] publish. */
    object SyncRequest {
        const val PATH = "/tigerduck/sync_request"
    }

    /** Debug-clock override pushed phone → watch (debug builds only). */
    object DebugClock {
        const val PATH = "/tigerduck/debug-clock"
        const val KEY_CLEARED = "cleared"
        const val KEY_INSTANT = "instant_millis"
        const val KEY_FROZEN = "frozen"
        const val KEY_SAVED_AT = "saved_at_real_millis"
        // Bumped on every push so the data layer treats two equal-payload
        // overrides as different items and re-delivers them. Without this,
        // toggling on→off→on with the same instant would silently no-op.
        const val KEY_VERSION = "version"
    }
}
