package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.AppState

/**
 * The class table's rows in Settings, in the iPhone's order: the Class tables list while someone
 * is imported ([hasImportedClassTables]), today's courses above the grid, then what each cell shows.
 */
@Composable
internal fun ClassTableSettingsRows(appState: AppState, hasImportedClassTables: Boolean, onOpenClassTableList: () -> Unit) {
    if (hasImportedClassTables) {
        SettingsLinkRow(stringResource(R.string.class_table_list_title)) { onOpenClassTableList() }
        HorizontalDivider()
    }
    SettingsToggleRow(stringResource(R.string.settings_pin_today_courses_on_class_table), appState.pinTodayCoursesOnClassTable) {
        appState.pinTodayCoursesOnClassTable = it
    }
    HorizontalDivider()
    SettingsToggleRow(stringResource(R.string.settings_show_classroom_on_class_table), appState.showClassroomInClassTable) {
        appState.showClassroomInClassTable = it
    }
    HorizontalDivider()
    SettingsToggleRow(stringResource(R.string.settings_outline_required_courses), appState.outlineRequiredCourses) {
        appState.outlineRequiredCourses = it
    }
    HorizontalDivider()
    SettingsToggleRow(stringResource(R.string.settings_always_show_periods_abc), appState.alwaysShowAllPeriods) {
        appState.alwaysShowAllPeriods = it
    }
}
