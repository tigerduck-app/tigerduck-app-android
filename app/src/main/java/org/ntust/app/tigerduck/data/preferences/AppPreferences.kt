package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.model.AppFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("hasCompletedOnboarding", false)
        set(value) = prefs.edit().putBoolean("hasCompletedOnboarding", value).apply()

    var accentColorHex: Int
        get() = prefs.getInt("accentColorHex", 0x007AFF)
        set(value) = prefs.edit().putInt("accentColorHex", value).apply()

    var rememberAnnouncementFilter: Boolean
        get() = prefs.getBoolean("rememberAnnouncementFilter", false)
        set(value) = prefs.edit().putBoolean("rememberAnnouncementFilter", value).apply()

    var savedAnnouncementDepartments: Set<String>
        get() {
            val json = prefs.getString("savedAnnouncementDepartments", null) ?: return emptySet()
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                (gson.fromJson<List<String>>(json, type) ?: emptyList()).toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }
        set(value) {
            prefs.edit().putString("savedAnnouncementDepartments", gson.toJson(value.toList())).apply()
        }

    var browserPreference: String
        get() = prefs.getString("browserPreference", "system") ?: "system"
        set(value) = prefs.edit().putString("browserPreference", value).apply()

    var showAbsoluteAssignmentTime: Boolean
        get() = prefs.getBoolean("showAbsoluteAssignmentTime", false)
        set(value) = prefs.edit().putBoolean("showAbsoluteAssignmentTime", value).apply()

    var configuredTabs: List<AppFeature>
        get() {
            val json = prefs.getString("configuredTabs", null)
                ?: return AppFeature.defaultTabs
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                val ids: List<String> = gson.fromJson(json, type) ?: return AppFeature.defaultTabs
                ids.mapNotNull { AppFeature.fromId(it) }.ifEmpty { AppFeature.defaultTabs }
            } catch (e: Exception) {
                AppFeature.defaultTabs
            }
        }
        set(value) {
            prefs.edit().putString("configuredTabs", gson.toJson(value.map { it.id })).apply()
        }

    var timeSliderStyle: String
        get() = prefs.getString("timeSliderStyle", "fluidTrack") ?: "fluidTrack"
        set(value) = prefs.edit().putString("timeSliderStyle", value).apply()

    var invertSliderDirection: Boolean
        get() = prefs.getBoolean("invertSliderDirection", false)
        set(value) = prefs.edit().putBoolean("invertSliderDirection", value).apply()

    var libraryFeatureEnabled: Boolean
        get() = prefs.getBoolean("libraryFeatureEnabled", false)
        set(value) = prefs.edit().putBoolean("libraryFeatureEnabled", value).apply()

    var notifyAssignments: Boolean
        get() = prefs.getBoolean("notifyAssignments", true)
        set(value) = prefs.edit().putBoolean("notifyAssignments", value).apply()

    var ssoLoginTimestamp: Long
        get() = prefs.getLong("ssoLoginTimestamp", 0L)
        set(value) = prefs.edit().putLong("ssoLoginTimestamp", value).apply()

    fun clearSsoTimestamp() {
        prefs.edit().remove("ssoLoginTimestamp").apply()
    }

    companion object {
        val themeColors: List<Pair<String, Int>> = listOf(
            "藍" to 0x007AFF,
            "紫" to 0xAF52DE,
            "粉" to 0xFF2D55,
            "紅" to 0xFF3B30,
            "橘" to 0xFF9500,
            "綠" to 0x34C759,
            "青" to 0x5AC8FA,
            "靛" to 0x5856D6,
        )
    }
}
