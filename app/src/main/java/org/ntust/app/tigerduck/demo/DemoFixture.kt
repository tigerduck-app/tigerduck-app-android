package org.ntust.app.tigerduck.demo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * A made-up student, read from the hand-written `assets/demo.json`.
 *
 * The file is deliberately not the on-disk cache format. This builds real
 * [Course] / [Assignment] objects and the caller hands them to `DataCache`,
 * so the bytes that land in the cache files come from the app's own Gson and
 * model classes — writing those files directly would mean re-deriving a
 * format that has already caused two shipped upgrade crashes.
 *
 * Parsed with Gson's tree API rather than `org.json`, which is only a stub
 * under this module's plain-JVM unit tests. A section the file leaves out
 * reads as null rather than empty, so a caller can tell "none" from "absent".
 */
data class DemoFixture(
    val studentId: String?,
    val password: String?,
    val libraryQrContent: String?,
    val libraryFakeSignedIn: Boolean,
    val courses: List<Course>?,
    val assignments: List<Assignment>?,
    val bulletins: List<BulletinSummary>?,
    val calendar: List<CalendarEvent>?,
) {
    /**
     * Whether a sign-in attempt names this account: the student ID compared
     * the way `AuthService.login` normalizes it, the password exactly. A file
     * missing either never matches, so an empty field cannot open it.
     */
    fun matches(studentId: String, password: String): Boolean {
        val id = this.studentId?.trim()?.uppercase().orEmpty()
        val pw = this.password.orEmpty()
        return id.isNotEmpty() && pw.isNotEmpty() &&
            studentId.trim().uppercase() == id && password == pw
    }

    companion object {
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        private val ZONE: ZoneId = ZoneId.of("Asia/Taipei")

        /**
         * [lang] picks which half of every `{"zh": …, "en": …}` pair to read.
         * What is read lands in the caches as plain strings, so it is resolved
         * once, here; a later language switch needs the file parsed again.
         *
         * Throws when [json] is not a JSON object. A malformed entry inside
         * one is skipped instead, so one typo does not cost the whole file.
         */
        fun parse(json: String, lang: String): DemoFixture {
            val root = JsonParser.parseString(json).asJsonObject
            val qr = root.obj("libraryQr")
            return DemoFixture(
                studentId = root.str("studentId"),
                password = root.str("password"),
                libraryQrContent = qr?.str("content"),
                libraryFakeSignedIn = qr?.bool("fakeLoggedIn", true) ?: false,
                courses = root.list("courses") { o, _ -> parseCourse(o, lang) },
                assignments = root.list("assignments") { o, _ -> parseAssignment(o, lang) },
                bulletins = root.list("bulletins") { o, i -> parseBulletin(o, i) },
                calendar = root.list("calendar") { o, i -> parseCalendarEvent(o, i) },
            )
        }

        /**
         * Reads a field that is either a plain string or a `{"zh", "en"}`
         * pair. Missing the requested language falls back to the other one
         * rather than to blank: a name in the wrong language is obvious on
         * sight, while a blank cell just looks like a bug in the app.
         */
        private fun localized(json: JsonObject, key: String, lang: String): String {
            val pair = json.obj(key) ?: return json.str(key).orEmpty()
            pair.str(lang)?.let { return it }
            return pair.str(if (lang == LANG_ZH) LANG_EN else LANG_ZH).orEmpty()
        }

        /**
         * Built through [Course.fromSchedule] rather than the constructor so
         * the weekday/period map is encoded to `scheduleJson` exactly the way
         * the network layer encodes it.
         */
        private fun parseCourse(json: JsonObject, lang: String): Course? {
            val courseNo = json.str("courseNo") ?: return null
            val schedule = json.obj("schedule")?.entrySet()?.mapNotNull { (key, value) ->
                val weekday = key.toIntOrNull() ?: return@mapNotNull null
                val periods = (value as? JsonArray)?.mapNotNull { it.primitiveString() }
                    ?: return@mapNotNull null
                weekday to periods
            }?.toMap().orEmpty()
            val classroomMap = json.obj("classroomMap")?.entrySet()
                ?.mapNotNull { (key, value) -> value.primitiveString()?.let { key to it } }
                ?.toMap().orEmpty()
            return Course.fromSchedule(
                courseNo = courseNo,
                courseName = localized(json, "name", lang),
                instructor = localized(json, "instructor", lang),
                credits = json.int("credits", 0),
                classroom = json.str("classroom").orEmpty(),
                schedule = schedule,
                classroomMap = classroomMap,
            ).copy(customColorHex = json.str("colorHex"))
        }

        private fun parseAssignment(json: JsonObject, lang: String): Assignment? {
            val id = json.str("id") ?: return null
            val due = parseLocal(json.str("due").orEmpty()) ?: return null
            return Assignment(
                assignmentId = id,
                courseNo = json.str("courseNo").orEmpty(),
                courseName = localized(json, "courseName", lang),
                title = localized(json, "title", lang),
                dueDate = due,
                isCompleted = json.bool("completed", false),
            )
        }

        /**
         * The list shape only; a bulletin's detail page is a separate fetch,
         * which the demo account refuses like every other. [index] backs the
         * id when the entry has none, so hand-written entries do not have to
         * carry unique numbers to avoid colliding in the cache.
         */
        private fun parseBulletin(json: JsonObject, index: Int): BulletinSummary {
            val id = json.int("id", index + 1)
            val title = json.str("title").orEmpty()
            return BulletinSummary(
                id = id,
                externalId = json.str("externalId") ?: "demo-$id",
                title = title,
                titleClean = title.ifBlank { null },
                canonicalOrg = json.str("org"),
                contentTags = json.arr("tags")?.mapNotNull { it.primitiveString() }.orEmpty(),
                importance = json.str("importance"),
                summary = json.str("summary"),
                sourceUrl = json.str("sourceUrl"),
                postedAt = json.str("postedAt"),
            )
        }

        private fun parseCalendarEvent(json: JsonObject, index: Int): CalendarEvent? {
            val date = parseLocal(json.str("date").orEmpty()) ?: return null
            return CalendarEvent(
                eventId = json.str("id") ?: "demo-cal-${index + 1}",
                title = json.str("title").orEmpty(),
                date = date,
                // Anything else renders as a generic school event, the right
                // fallback for a typo in a hand-written file.
                sourceRaw = json.str("source") ?: "school",
            )
        }

        /** `2026-09-15T23:59` (a space works too) or a plain date, in Taipei time. */
        private fun parseLocal(raw: String): Date? {
            val normalized = raw.trim().replace(' ', 'T')
            if (normalized.isEmpty()) return null
            val local = try {
                LocalDateTime.parse(normalized)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(normalized).atStartOfDay()
                } catch (_: DateTimeParseException) {
                    return null
                }
            }
            return Date(local.atZone(ZONE).toInstant().toEpochMilli())
        }

        private fun com.google.gson.JsonElement.primitiveString(): String? =
            takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.str(key: String): String? =
            get(key)?.primitiveString()?.takeIf { it.isNotBlank() }

        private fun JsonObject.bool(key: String, default: Boolean): Boolean =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default

        private fun JsonObject.int(key: String, default: Int): Int =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: default

        private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

        private fun JsonObject.arr(key: String): JsonArray? = get(key) as? JsonArray

        private fun <T> JsonObject.list(key: String, parse: (JsonObject, Int) -> T?): List<T>? =
            arr(key)?.mapIndexedNotNull { i, element -> (element as? JsonObject)?.let { parse(it, i) } }
    }
}
