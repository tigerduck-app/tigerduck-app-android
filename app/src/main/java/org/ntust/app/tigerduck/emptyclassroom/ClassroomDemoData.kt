package org.ntust.app.tigerduck.emptyclassroom

import android.content.Context
import android.util.Log
import androidx.core.os.ConfigurationCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.demo.DemoFixture
import org.ntust.app.tigerduck.util.logSummary
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The demo account's rooms, from `assets/demo.json`'s `emptyClassroom` section. The demo account
 * never reaches the school, so without these the finder would show nothing to a store reviewer.
 */
@Singleton
class ClassroomDemoData @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val demoAccount: DemoAccount,
    private val appPreferences: AppPreferences,
) {
    @Volatile
    private var loaded: Pair<String, DemoClassrooms?>? = null

    /** The rooms for [day] while the demo account is signed in, else null. */
    fun usage(day: LocalDate): CampusUsage? {
        if (!demoAccount.isActive) return null
        val lang = currentLang()
        val classrooms = loaded?.takeIf { it.first == lang }?.second ?: load(lang).also { loaded = lang to it }
        return classrooms?.usage(day)
    }

    private fun load(lang: String): DemoClassrooms? = runCatching {
        context.assets.open("demo.json").bufferedReader().use { DemoClassrooms.parse(it.readText(), lang) }
    }.onFailure { Log.e("ClassroomDemoData", "cannot read demo.json: ${it.logSummary()}") }.getOrNull()

    private fun currentLang(): String {
        val locale = AppLanguageManager.resolveExplicitLocale(appPreferences.appLanguage)
            ?: ConfigurationCompat.getLocales(context.resources.configuration)[0]
        return if (locale?.language == "zh") DemoFixture.LANG_ZH else DemoFixture.LANG_EN
    }
}

/**
 * The demo week of the booking site: each room's periods by weekday, `.` free, `b` booked and a
 * digit the class at that place in [classes], counted from 1. A room's `*` pattern stands for
 * every weekday it does not list. Weekends have no rooms, as on the site.
 */
data class DemoClassrooms(
    val buildingNames: Map<String, String>,
    val classes: List<RoomSlotUse.Course>,
    /** Room name to its patterns by weekday key: "1" (Monday) … "5", or "*". */
    val rooms: Map<String, Map<String, String>>,
) {
    fun usage(day: LocalDate): CampusUsage {
        if (EmptyClassroomSchedule.isWeekend(day)) return CampusUsage(emptyList(), buildingNames)
        val weekday = day.dayOfWeek.value.toString()
        val usages = rooms.mapNotNull { (room, week) ->
            val pattern = week[weekday] ?: week["*"] ?: return@mapNotNull null
            RoomUsage(room, List(ClassroomUsageParser.PERIOD_COUNT) { slot(pattern.getOrNull(it)) })
        }
        return CampusUsage(usages, buildingNames)
    }

    private fun slot(mark: Char?): RoomSlotUse = when {
        mark == 'b' -> RoomSlotUse.Booked
        mark != null && mark.isDigit() -> classes.getOrNull(mark.digitToInt() - 1) ?: RoomSlotUse.Free
        else -> RoomSlotUse.Free
    }

    companion object {
        /** Null when the file has no `emptyClassroom` section. Throws when [json] is not a JSON object. */
        fun parse(json: String, lang: String): DemoClassrooms? {
            val section = JsonParser.parseString(json).asJsonObject.getObject("emptyClassroom") ?: return null
            val buildings = section.getObject("buildings")?.entrySet()
                ?.mapNotNull { (code, name) -> name.primitive()?.let { code to it } }
                ?.toMap().orEmpty()
            val classes = section.get("classes")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { element ->
                    val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    RoomSlotUse.Course(localized(o, "name", lang), localized(o, "teacher", lang))
                }.orEmpty()
            val rooms = section.getObject("rooms")?.entrySet()?.mapNotNull { (room, week) ->
                val patterns = (week as? JsonObject)?.entrySet()
                    ?.mapNotNull { (key, value) -> value.primitive()?.let { key to it } }
                    ?.toMap() ?: return@mapNotNull null
                room to patterns
            }?.toMap().orEmpty()
            return DemoClassrooms(buildings, classes, rooms)
        }

        private fun localized(json: JsonObject, key: String, lang: String): String {
            val pair = json.getObject(key) ?: return json.get(key)?.primitive().orEmpty()
            return pair.get(lang)?.primitive()
                ?: pair.get(if (lang == DemoFixture.LANG_ZH) DemoFixture.LANG_EN else DemoFixture.LANG_ZH)?.primitive()
                ?: ""
        }

        private fun JsonObject.getObject(key: String): JsonObject? = get(key) as? JsonObject

        private fun com.google.gson.JsonElement.primitive(): String? =
            takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }
}
