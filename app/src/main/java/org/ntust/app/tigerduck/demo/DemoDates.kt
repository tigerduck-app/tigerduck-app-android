package org.ntust.app.tigerduck.demo

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * `assets/demo.json`, with its dates moved next to today, as the iPhone's `DemoTime.shifted`
 * (tigerduck-app#1049). The file's top-level `anchor` is the day its dates are written around:
 * every string in it that starts with a `yyyy-MM-dd` day moves by the same whole number of Taipei
 * days, today less the anchor, and the rest of the string, a time or an offset, stays as written.
 * Weekdays are not kept: what is bound to one (course changes, clubs, the class table, the empty
 * classrooms) carries a weekday instead of a date. Every reader of the file reads it from here.
 */
object DemoDates {
    const val ASSET = "demo.json"

    /**
     * The day the file moves to, read once a process, so that the demo account, its mailbox and its
     * rooms, each read when first needed, move alike.
     */
    val launchDay: LocalDate by lazy { TaipeiClock.today() }

    /** The bundled file, moved to [launchDay]. */
    fun read(context: Context): String =
        shifted(context.assets.open(ASSET).bufferedReader().use { it.readText() }, launchDay)

    /** [json] moved so that its `anchor` falls on [today]; as it is without an anchor, or when it is not an object. */
    fun shifted(json: String, today: LocalDate): String {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject ?: return json
        val anchor = root.get("anchor")?.takeIf { it.isJsonPrimitive }?.asString?.let(::day) ?: return json
        val days = ChronoUnit.DAYS.between(anchor, today)
        if (days == 0L) return json
        return moved(root, days).toString()
    }

    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

    private fun day(text: String): LocalDate? {
        if (!DAY.matches(text)) return null
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun moved(value: JsonElement, days: Long): JsonElement = when (value) {
        is JsonObject -> JsonObject().also { out -> value.entrySet().forEach { (key, v) -> out.add(key, moved(v, days)) } }
        is JsonArray -> JsonArray().also { out -> value.forEach { out.add(moved(it, days)) } }
        is JsonPrimitive -> {
            val text = value.takeIf { it.isString }?.asString
            val day = text?.takeIf { it.length >= 10 }?.let { day(it.substring(0, 10)) }
            if (day == null) value else JsonPrimitive(day.plusDays(days).toString() + text.substring(10))
        }
        else -> value
    }
}
