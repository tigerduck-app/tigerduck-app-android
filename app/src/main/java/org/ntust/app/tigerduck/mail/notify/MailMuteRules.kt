package org.ntust.app.tigerduck.mail.notify

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID

/**
 * Which new mail School Mail leaves out of its notifications (tigerduck-app-android#354, as the
 * iPhone's tigerduck-app#801). It only decides whether a notification is posted: the mail stays
 * in the inbox, unread, and the list, the badge and the server are unchanged. Kept on the device
 * only, in [org.ntust.app.tigerduck.mail.store.MailStateStore], and cleared at the mail sign-out.
 */
data class MailMuteSettings(
    val quietHours: MailQuietHours = MailQuietHours(),
    val presets: Set<MailMutePreset> = emptySet(),
    val rules: List<MailMuteRule> = emptyList(),
) {
    /** Whether [mail], which arrived at [arrivedAt], is left out of the notifications. */
    fun mutes(mail: MailSummary, arrivedAt: Instant): Boolean =
        quietHours.contains(arrivedAt) || presets.any { it.matches(mail) } || rules.any { it.matches(mail) }
}

/**
 * A time window in which new mail is not notified, on [days]. Times are minutes after midnight on
 * the Asia/Taipei clock, as the rest of the app's day math, whatever the phone's time zone.
 */
data class MailQuietHours(
    val enabled: Boolean = false,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    /**
     * Whether [at] falls inside the window: the start minute is in it and the end minute is not,
     * equal start and end make no window, and one that ends on the next day belongs to the day
     * it starts on.
     */
    fun contains(at: Instant): Boolean {
        if (!enabled || startMinute == endMinute) return false
        val local = at.atZone(AppConstants.TAIPEI_ZONE)
        val time = local.hour * 60 + local.minute
        val day = local.dayOfWeek
        return when {
            startMinute < endMinute -> day in days && time >= startMinute && time < endMinute
            time >= startMinute -> day in days
            else -> time < endMinute && day.minus(1) in days
        }
    }

    /** [day] switched, except the last selected day, which stays: quiet hours on no day quiet nothing. */
    fun toggling(day: DayOfWeek): MailQuietHours = when {
        day !in days -> copy(days = days + day)
        days.size > 1 -> copy(days = days - day)
        else -> this
    }
}

/** Rules shipped with the app, each switched on by itself; all off by default. */
enum class MailMutePreset(val key: String) {
    BULLETIN("bulletin"),
    MOODLE_SUBMISSION("moodleSubmission");

    fun matches(mail: MailSummary): Boolean = when (this) {
        BULLETIN -> FROM_BULLETIN.matches(mail)
        MOODLE_SUBMISSION -> FROM_MOODLE.matches(mail) && MOODLE_SUBMISSION_SUBJECTS.any { it.matches(mail) }
    }

    companion object {
        const val BULLETIN_SENDER = "bulletin@mail.ntust.edu.tw"
        const val MOODLE_SENDER = "noreply@mail.ntust.edu.tw"

        private val FROM_BULLETIN = presetRule(MailMuteRule.Field.FROM, MailMuteRule.Operator.IS, BULLETIN_SENDER)
        private val FROM_MOODLE = presetRule(MailMuteRule.Field.FROM, MailMuteRule.Operator.IS, MOODLE_SENDER)

        /** The receipt's subject in Moodle's Chinese and English interfaces. */
        private val MOODLE_SUBMISSION_SUBJECTS = listOf("您已經繳交", "You have submitted your assignment submission")
            .map { presetRule(MailMuteRule.Field.SUBJECT, MailMuteRule.Operator.CONTAINS, it) }

        private fun presetRule(field: MailMuteRule.Field, op: MailMuteRule.Operator, value: String) =
            MailMuteRule("preset", field, op, value)

        fun fromKey(key: String?): MailMutePreset? = entries.firstOrNull { it.key == key }
    }
}

/** One condition a mail is muted by: a [field], an [op] and a [value]. */
data class MailMuteRule(
    val id: String,
    val field: Field,
    val op: Operator,
    val value: String,
) {
    enum class Field(val key: String) {
        FROM("from"),
        TO_OR_CC("toOrCc"),
        SUBJECT("subject");

        companion object {
            fun fromKey(key: String?): Field? = entries.firstOrNull { it.key == key }
        }
    }

    enum class Operator(val key: String) {
        CONTAINS("contains"),
        IS("is");

        companion object {
            fun fromKey(key: String?): Operator? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * Case is ignored and [value] is trimmed; an empty value matches nothing. "is" compares only
     * the address for From and To or Cc, and the whole subject for Subject.
     */
    fun matches(mail: MailSummary): Boolean {
        val wanted = value.trim()
        if (wanted.isEmpty()) return false
        val recipients = mail.to + mail.cc
        val candidates = when (field) {
            Field.FROM -> when (op) {
                Operator.CONTAINS -> listOfNotNull(mail.from?.name, mail.from?.address)
                Operator.IS -> listOfNotNull(mail.from?.address)
            }
            Field.TO_OR_CC -> when (op) {
                Operator.CONTAINS -> recipients.flatMap { listOfNotNull(it.name, it.address) }
                Operator.IS -> recipients.map { it.address }
            }
            Field.SUBJECT -> listOf(mail.subject)
        }
        return candidates.any { candidate ->
            when (op) {
                Operator.CONTAINS -> candidate.contains(wanted, ignoreCase = true)
                Operator.IS -> candidate.trim().equals(wanted, ignoreCase = true)
            }
        }
    }

    companion object {
        /** A new rule, with an id of its own, as the add sheet starts one. */
        fun matching(field: Field, op: Operator, value: String) = MailMuteRule(UUID.randomUUID().toString(), field, op, value)
    }
}

/**
 * [MailMuteSettings] as the stored JSON. Read field by field, each with its default when missing or
 * unreadable, so a field a later build adds does not wipe the rules; a rule or preset this build
 * does not know is left out. Not Gson: its defaults do not survive a missing key.
 */
object MailMuteSettingsCodec {
    fun encode(settings: MailMuteSettings): String = JSONObject()
        .put(
            "quietHours",
            JSONObject()
                .put("enabled", settings.quietHours.enabled)
                .put("start", settings.quietHours.startMinute)
                .put("end", settings.quietHours.endMinute)
                .put("days", JSONArray(settings.quietHours.days.sorted().map { it.value })),
        )
        .put("presets", JSONArray(settings.presets.sorted().map { it.key }))
        .put(
            "rules",
            JSONArray(
                settings.rules.map {
                    JSONObject().put("id", it.id).put("field", it.field.key).put("op", it.op.key).put("value", it.value)
                },
            ),
        )
        .toString()

    fun decode(json: String?): MailMuteSettings {
        val root = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return MailMuteSettings()
        val defaults = MailMuteSettings()
        return MailMuteSettings(
            quietHours = root.optJSONObject("quietHours")?.let(::quietHours) ?: defaults.quietHours,
            presets = root.optJSONArray("presets")?.strings()?.mapNotNullTo(mutableSetOf(), MailMutePreset::fromKey)
                ?: defaults.presets,
            rules = root.optJSONArray("rules")?.let(::rules) ?: defaults.rules,
        )
    }

    /**
     * The stored rules, each with an id of its own: one stored without an id, or with another's,
     * takes one from its place in the list, the same on every read, so the editor finds again
     * the rule it opened. The next save stores it.
     */
    private fun rules(array: JSONArray): List<MailMuteRule> {
        val seen = mutableSetOf<String>()
        return (0 until array.length()).mapNotNull { index ->
            val rule = array.optJSONObject(index)?.let(::rule) ?: return@mapNotNull null
            val id = rule.id.takeIf { it.isNotEmpty() && it !in seen }
                ?: generateSequence(0) { it + 1 }.map { "stored-$index-$it" }.first { it !in seen }
            seen += id
            rule.copy(id = id)
        }
    }

    private fun quietHours(o: JSONObject): MailQuietHours {
        val defaults = MailQuietHours()
        val days = o.optJSONArray("days")?.ints()
            ?.mapNotNullTo(mutableSetOf()) { value -> DayOfWeek.entries.firstOrNull { it.value == value } }
        return MailQuietHours(
            enabled = o.optBoolean("enabled", defaults.enabled),
            startMinute = o.optInt("start", defaults.startMinute).takeIf { it in MINUTES_OF_DAY } ?: defaults.startMinute,
            endMinute = o.optInt("end", defaults.endMinute).takeIf { it in MINUTES_OF_DAY } ?: defaults.endMinute,
            // None selected would quiet nothing on any day, which the screen never lets happen.
            days = days?.takeIf { it.isNotEmpty() } ?: defaults.days,
        )
    }

    private fun rule(o: JSONObject): MailMuteRule? {
        val field = if (o.has("field")) MailMuteRule.Field.fromKey(o.optString("field")) ?: return null else MailMuteRule.Field.FROM
        val op = if (o.has("op")) MailMuteRule.Operator.fromKey(o.optString("op")) ?: return null else MailMuteRule.Operator.CONTAINS
        return MailMuteRule(o.optString("id"), field, op, o.optString("value"))
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

    private fun JSONArray.ints(): List<Int> = (0 until length()).mapNotNull { i ->
        try {
            getInt(i)
        } catch (e: JSONException) {
            null
        }
    }

    private val MINUTES_OF_DAY = 0 until 24 * 60
}
