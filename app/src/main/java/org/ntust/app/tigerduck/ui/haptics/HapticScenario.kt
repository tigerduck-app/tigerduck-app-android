package org.ntust.app.tigerduck.ui.haptics

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

enum class HapticScenario(
    val prefKey: String,
    val defaultStrengthPct: Int,
    val defaultDurationMs: Int,
    val userTunable: Boolean,
    val forceOneShot: Boolean = false,
    @StringRes val labelRes: Int,
) {
    TabSwitch(
        prefKey = "tabSwitch",
        defaultStrengthPct = 75,
        defaultDurationMs = 14,
        userTunable = true,
        labelRes = R.string.haptic_scenario_tab_switch,
    ),
    PullToRefresh(
        prefKey = "pullToRefresh",
        defaultStrengthPct = 60,
        defaultDurationMs = 12,
        userTunable = true,
        labelRes = R.string.haptic_scenario_pull_to_refresh,
    ),
    TimeSliderTick(
        prefKey = "timeSliderTick",
        defaultStrengthPct = 60,
        defaultDurationMs = 6,
        userTunable = true,
        labelRes = R.string.haptic_scenario_time_slider_tick,
    ),
    TabReorder(
        prefKey = "tabReorder",
        defaultStrengthPct = 70,
        defaultDurationMs = 12,
        userTunable = true,
        labelRes = R.string.haptic_scenario_tab_reorder,
    ),
    ClassTableLongPress(
        prefKey = "classTableLongPress",
        defaultStrengthPct = 80,
        defaultDurationMs = 20,
        userTunable = true,
        labelRes = R.string.haptic_scenario_class_table_long_press,
    ),
    LibraryWarning(
        prefKey = "libraryWarning",
        defaultStrengthPct = 100,
        defaultDurationMs = 1000,
        userTunable = false,
        forceOneShot = true,
        labelRes = R.string.haptic_scenario_library_warning,
    );

    companion object {
        val tunable: List<HapticScenario> = entries.filter { it.userTunable }
    }
}
