// The public API of clubs.ntust.edu.tw, as the Apple apps read it (ClubAPIModels.swift): every
// club, and the activities of the next two weeks. Read through Gson's tree, never by reflection,
// so R8 can rename nothing it reads and a stored copy needs no keep rule.

package org.ntust.app.tigerduck.clubs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** A club of the directory, `GET /clubs`, with the fields the app shows. */
data class ClubCard(
    val id: Int,
    val name: String,
    val enName: String?,
    /** One of [ClubDirectory.ATTRIBUTE_ORDER]; any other value, or none, is unclassified. */
    val attribute: String?,
    /** Written in Chinese only. */
    val tagline: String?,
) {
    /** The Chinese name in a Chinese interface; elsewhere the English one when the club has one. */
    fun displayName(chinese: Boolean): String = if (chinese || enName.isNullOrBlank()) name else enName
}

/** What an activity is, as the feed's `type` names it. */
sealed interface ClubActivityKind {
    /** `社課或會議`. */
    data object Meeting : ClubActivityKind

    /** `活動`. */
    data object Event : ClubActivityKind

    /** A kind this build does not know, as the feed names it. */
    data class Other(val name: String) : ClubActivityKind

    companion object {
        fun of(type: String): ClubActivityKind = when (type) {
            "社課或會議" -> Meeting
            "活動" -> Event
            else -> Other(type)
        }
    }
}

/** An activity that starts and ends on one Taipei day: its `yyyy-MM-dd` and its minutes after midnight. */
data class OneDay(val day: String, val start: Int, val end: Int)

/** An activity of the feed, `GET /activities`. Its dates and times are Taipei wall-clock values. */
data class ClubActivity(
    val id: Int,
    val name: String,
    val clubId: Int,
    /** The club's Chinese name. */
    val clubName: String,
    /** `yyyy-MM-dd`. */
    val date: String?,
    val endDate: String?,
    /** `HH:mm:ss`. */
    val startTime: String?,
    val endTime: String?,
    val location: String,
    val kind: ClubActivityKind,
    val content: String,
) {
    /** Where the class table draws it; null for one that spans days, lacks a time or does not end after it starts. */
    val oneDay: OneDay?
        get() {
            val day = date?.takeIf { it == endDate } ?: return null
            val start = minutes(startTime) ?: return null
            val end = minutes(endTime) ?: return null
            return if (start < end) OneDay(day, start, end) else null
        }

    private companion object {
        fun minutes(time: String?): Int? {
            val parts = time?.split(":") ?: return null
            if (parts.size != 3 || parts[1].length != 2 || parts[2].length != 2) return null
            if (parts.any { part -> part.isEmpty() || !part.all { it in '0'..'9' } }) return null
            // An hour of any length reads, as Swift's Int does, and one past Int's range or past 23 has no place.
            val (hour, minute, second) = parts.map { it.toIntOrNull() ?: return null }
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
            return hour * 60 + minute
        }
    }
}

/** A list none of whose rows reads, or no envelope at all: the stored copy stays. */
class ClubListUnreadableException(message: String) : IOException(message)

/**
 * The `data` list of the API's envelope, `{success, data, error, meta}`, less the rows this build
 * cannot read, so one bad row does not hide the others, and less a second row of an id. A list
 * none of whose rows reads is unreadable as a whole.
 */
object ClubApiList {
    fun clubs(body: String): List<ClubCard> = decode(body, ::club) { it.id }

    fun activities(body: String): List<ClubActivity> = decode(body, ::activity) { it.id }

    private fun <T> decode(body: String, read: (JsonObject) -> T?, id: (T) -> Int): List<T> {
        val rows = try {
            JsonParser.parseString(body).asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        } catch (e: RuntimeException) {
            null
        } ?: throw ClubListUnreadableException("no data list")
        val seen = HashSet<Int>()
        val elements = rows.mapNotNull { row ->
            row.takeIf { it.isJsonObject }?.let { element ->
                try {
                    read(element.asJsonObject)
                } catch (e: UnreadableField) {
                    null
                }
            }
        }.filter { seen.add(id(it)) }
        if (rows.size() > 0 && elements.isEmpty()) throw ClubListUnreadableException("no row is readable")
        return elements
    }

    private fun club(row: JsonObject): ClubCard? = ClubCard(
        id = row.int("id") ?: return null,
        name = row.string("name") ?: return null,
        enName = row.optionalString("en_name"),
        attribute = row.optionalString("attribute"),
        tagline = row.optionalString("tagline"),
    )

    private fun activity(row: JsonObject): ClubActivity? = ClubActivity(
        id = row.int("id") ?: return null,
        name = row.string("name") ?: return null,
        clubId = row.int("club_id") ?: return null,
        clubName = row.string("club_name") ?: return null,
        date = row.optionalString("date"),
        endDate = row.optionalString("end_date"),
        startTime = row.optionalString("start_time"),
        endTime = row.optionalString("end_time"),
        location = row.string("location") ?: return null,
        kind = ClubActivityKind.of(row.string("type") ?: return null),
        content = row.string("content") ?: return null,
    )

    /** A JSON whole number in Int's range; null for any other value or none. */
    private fun JsonObject.int(key: String): Int? {
        val value = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        return runCatching { value.asBigDecimal.intValueExact() }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** The string at [key], or null for none; a value of another type makes the row unreadable. */
    private fun JsonObject.optionalString(key: String): String? {
        val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString
        throw UnreadableField
    }

    /** A row's optional field of the wrong type, as Swift's decoder fails the row on one. */
    private object UnreadableField : RuntimeException() {
        private fun readResolve(): Any = UnreadableField
        override fun fillInStackTrace(): Throwable = this
    }
}
