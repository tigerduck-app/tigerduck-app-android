package org.ntust.app.tigerduck.shared

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.LocalDate

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

        /** The accent as `#RRGGBB`; with a gradient picked, its start. */
        const val KEY_ACCENT = "accentHex"

        // The end of the accent gradient as `#RRGGBB`, and its direction as an
        // [AccentGradientDirection.code] Int, both only while a gradient is
        // picked. Neither is sent for a single color, and a phone that
        // predates them sends neither: the watch then draws [KEY_ACCENT]
        // alone, and a watch that predates them draws it alone too.
        const val KEY_ACCENT_END = "accentEndHex"
        const val KEY_ACCENT_DIRECTION = "accentDirection"
        const val KEY_SYNCED_AT = "syncedAtMs"
        const val KEY_LOGGED_IN = "loggedIn"
        const val KEY_LANGUAGE = "languageTag"

        // Mirrors the phone's `Settings → Developer → Disable screen-capture
        // protection` toggle. Only ever set to true by a DEBUG-build phone;
        // release phones publish false unconditionally. The watch's
        // LibraryQR SecureScreen reads the persisted value to decide whether
        // to apply FLAG_SECURE to the QR window.
        const val KEY_DISABLE_SCREEN_CAPTURE_PROTECTION = "disableScreenCaptureProtection"

        // Days on which no class meets for this user, as [encodeQuietDays]
        // writes them. A phone that predates the key sends none, which the
        // watch reads as no quiet days, and a watch that predates it ignores it.
        const val KEY_QUIET_DAYS = "quietDays"

        // The interface style picked in Settings › Interface style, as the phone stores it
        // ("default" for TigerDuck, [VISUAL_PRESET_TAT]): the key and values the iPhone's
        // WatchWireFormat uses. A phone that predates it sends none, which the watch reads
        // as TigerDuck, as it does a value it doesn't know; a watch that predates it ignores it.
        const val KEY_VISUAL_PRESET = "visualPreset"

        /** The TAT look's raw value under [KEY_VISUAL_PRESET]. */
        const val VISUAL_PRESET_TAT = "tat"

        /** ISO `yyyy-MM-dd` strings, the form [KEY_QUIET_DAYS] carries. */
        fun encodeQuietDays(days: Collection<LocalDate>): ArrayList<String> =
            days.sorted().mapTo(ArrayList(days.size)) { it.toString() }

        /**
         * Null, from a phone or a stored snapshot that predates
         * [KEY_QUIET_DAYS], reads as none, so the watch shows every class as
         * it did before. An entry that is not a date is dropped rather than
         * failing the rest.
         */
        fun decodeQuietDays(raw: Collection<String>?): Set<LocalDate> =
            raw.orEmpty().mapNotNullTo(HashSet()) { runCatching { LocalDate.parse(it) }.getOrNull() }

        // The classes the school changed, as [encodeCourseChanges] writes them, on every day of the
        // feed. A phone that predates the key sends none, which the watch reads as no changes, and a
        // watch that predates it ignores it.
        const val KEY_COURSE_CHANGES = "courseChanges"

        /**
         * [changes] as one JSON object, course number to ISO day to that day's classes, each with
         * its `periods` and `cancelled`, and `room`, `start` and `end` (`HH:mm`) only when changed:
         * `{"CC900":{"2026-11-05":[{"periods":["2"],"cancelled":true}]}}`. As the iPhone's
         * `WatchPayloadCodec` writes `changes`, but by course rather than inside each meeting, so
         * [KEY_COURSES] keeps the shape older watches read.
         */
        fun encodeCourseChanges(changes: WatchCourseChanges): String {
            val root = JsonObject()
            for ((courseNo, days) in changes.byCourse.toSortedMap()) {
                val byDay = JsonObject()
                for ((day, classes) in days.toSortedMap()) {
                    byDay.add(day.toString(), JsonArray().apply { classes.forEach { add(classJson(it)) } })
                }
                root.add(courseNo, byDay)
            }
            return root.toString()
        }

        /**
         * Null or blank, from a phone or a stored snapshot that predates [KEY_COURSE_CHANGES], reads
         * as no changes, and so does a payload that is not an object of courses. A day or class of
         * another shape is dropped rather than failing the rest; a block left with no class shows
         * as the timetable has it.
         */
        fun decodeCourseChanges(raw: String?): WatchCourseChanges {
            if (raw.isNullOrBlank()) return WatchCourseChanges.EMPTY
            val root = runCatching { JsonParser.parseString(raw) }.getOrNull() as? JsonObject
                ?: return WatchCourseChanges.EMPTY
            val byCourse = root.entrySet().mapNotNull { (courseNo, days) ->
                val byDay = (days as? JsonObject)?.entrySet().orEmpty().mapNotNull { (key, classes) ->
                    val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
                    val decoded = (classes as? JsonArray)?.mapNotNull(::decodeClass).orEmpty()
                    if (decoded.isEmpty()) null else day to decoded
                }.toMap()
                if (byDay.isEmpty()) null else courseNo to byDay
            }.toMap()
            return WatchCourseChanges(byCourse)
        }

        private fun classJson(change: WatchClassChange) = JsonObject().apply {
            add("periods", JsonArray().apply { change.periods.forEach(::add) })
            addProperty("cancelled", change.cancelled)
            change.room?.let { addProperty("room", it) }
            change.start?.let { addProperty("start", it) }
            change.end?.let { addProperty("end", it) }
        }

        /** Null for a class without periods, or with a value of the wrong type or a time that is not `HH:mm`. */
        private fun decodeClass(element: JsonElement): WatchClassChange? {
            val entry = element as? JsonObject ?: return null
            val periods = (entry.get("periods") as? JsonArray)?.map { stringOf(it) ?: return null }
            if (periods.isNullOrEmpty()) return null
            val cancelled = when (val value = entry.get("cancelled")) {
                null, is JsonNull -> false
                is JsonPrimitive -> if (value.isBoolean) value.asBoolean else return null
                else -> return null
            }
            val values = listOf("room", "start", "end").associateWith { key ->
                when (val value = entry.get(key)) {
                    null, is JsonNull -> null
                    else -> stringOf(value) ?: return null
                }
            }
            val start = values["start"]
            val end = values["end"]
            if (start != null && !CLOCK_TIME.matches(start)) return null
            if (end != null && !CLOCK_TIME.matches(end)) return null
            return WatchClassChange(periods, cancelled, values["room"]?.takeIf { it.isNotEmpty() }, start, end)
        }

        private fun stringOf(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.asString

        private val CLOCK_TIME = Regex("([01]\\d|2[0-3]):[0-5]\\d")
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

        // Strictly monotonic across phone app restarts (persisted on the
        // phone). The watch rejects pushes with `version <= storedVersion`
        // as replay, so the same constant also defeats DataClient's
        // identical-payload de-dup — both roles in one counter.
        const val KEY_VERSION = "version"

        // Wall-clock ms when the phone composed this push. Anchors the
        // watch's 7-day staleness TTL: independent of WC delivery time, so
        // a payload that sits queued for a week still expires correctly.
        const val KEY_ISSUED_AT_MS = "issuedAtMs"
    }

    /**
     * TLS pins phone → watch (TLS pins spec, A8): the pin document the phone verified, as
     * the envelope bytes it received, for the watch to verify again with its own keys, and the
     * student's acceptances. The watch applies an acceptance only while its own build is the one
     * the phone made it under.
     */
    object TlsPins {
        const val PATH = "/tigerduck/tls_pins"

        /** The verified envelope's exact bytes; absent while the phone holds no document. */
        const val KEY_ENVELOPE = "envelope"

        /**
         * The acceptances, as [org.ntust.app.tigerduck.shared.tls.PinAcceptances] encodes them,
         * each with the phone build number it was made under.
         */
        const val KEY_ACCEPTANCES = "acceptances"

        /**
         * Strictly increasing, from the wall clock, so it stays ahead across phone restarts and
         * reinstalls; the watch drops a push not above its last.
         */
        const val KEY_VERSION = "version"

        /**
         * The watch's versionCode is the phone's plus this (wear/build.gradle.kts), so the watch
         * reads the phone build an acceptance was made under against its own.
         */
        const val WATCH_VERSION_CODE_OFFSET = 10_000L
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
