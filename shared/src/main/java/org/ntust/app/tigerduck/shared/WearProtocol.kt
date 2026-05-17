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

    /**
     * Library credentials pushed phone → watch. Lets the watch hit
     * `api.lib.ntust.edu.tw` directly for the rotating-QR pass without
     * round-tripping through the phone. `KEY_HAS_CREDENTIALS=false` is the
     * explicit "user logged out" signal — watch clears its store on receipt.
     */
    object LibraryCredentials {
        const val PATH = "/tigerduck/library_credentials"
        const val KEY_HAS_CREDENTIALS = "hasCredentials"
        const val KEY_USERNAME = "libraryUsername"
        const val KEY_PASSWORD = "libraryPassword"
        const val KEY_TOKEN = "libraryToken"
        const val KEY_TOKEN_EXPIRY = "libraryTokenExpiry"
        // Bumped on every push so the data layer treats two equal-payload
        // updates as different items and re-delivers them. Without this,
        // refreshing the same token would silently no-op on the watch.
        const val KEY_VERSION = "version"
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
