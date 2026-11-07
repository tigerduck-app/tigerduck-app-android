package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/** The Add course sheet's pages: the search, and the filters with the pages they open. */
internal enum class AddCoursePage {
    Search, Filters, Department, Dimension, Periods;

    /** Where Back goes. */
    val parent: AddCoursePage
        get() = when (this) {
            Search, Filters -> Search
            Department, Dimension, Periods -> Filters
        }
}

/**
 * The filters, pushed inside the sheet as the iPhone pushes its filter page: hide conflicts, the
 * department (with its own search), level, general-education type and periods, the four toggles,
 * and Reset. Every list comes from the catalog, and everything runs on the device.
 */
@Composable
internal fun CourseSearchFilterPages(
    page: AddCoursePage,
    onPage: (AddCoursePage) -> Unit,
    model: CourseSearchModel,
    busy: Set<CourseSlot>,
    language: String,
) {
    BackHandler { onPage(page.parent) }
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        when (page) {
            AddCoursePage.Search -> Unit
            AddCoursePage.Filters -> FiltersPage(model, language, onPage)
            AddCoursePage.Department -> DepartmentPage(model, language) { onPage(AddCoursePage.Filters) }
            AddCoursePage.Dimension -> DimensionPage(model, language) { onPage(AddCoursePage.Filters) }
            AddCoursePage.Periods -> PeriodsPage(model, busy) { onPage(AddCoursePage.Filters) }
        }
    }
}

@Composable
private fun ColumnScope.FiltersPage(model: CourseSearchModel, language: String, onPage: (AddCoursePage) -> Unit) {
    val filters = model.filters
    PageHeader(stringResource(R.string.course_search_filters), onBack = { onPage(AddCoursePage.Search) }) {
        TextButton(onClick = { model.filters = CourseSearchFilters() }, enabled = !filters.isDefault) {
            Text(stringResource(R.string.course_search_reset_filters))
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        SwitchRow(R.string.course_search_hide_conflicts, filters.hideConflicts) { model.filters = filters.copy(hideConflicts = it) }
        HorizontalDivider()
        val department = filters.department?.let { code ->
            model.options.colleges.flatMap { it.departments }.firstOrNull { it.code == code }?.name(language) ?: code
        }
        NavRow(R.string.course_search_department, department ?: stringResource(R.string.course_search_any)) {
            onPage(AddCoursePage.Department)
        }
        LevelRow(filters.level) { model.filters = filters.copy(level = it) }
        val dimension = filters.dimension?.let { code ->
            model.options.dimensions.firstOrNull { it.code == code }?.name(language) ?: code
        }
        NavRow(R.string.course_search_dimension, dimension ?: stringResource(R.string.course_search_any)) {
            onPage(AddCoursePage.Dimension)
        }
        val periods = filters.periods.size
        NavRow(
            R.string.course_search_periods,
            if (periods == 0) stringResource(R.string.course_search_any)
            else stringResource(R.string.course_search_periods_count, periods),
        ) { onPage(AddCoursePage.Periods) }
        HorizontalDivider()
        SwitchRow(R.string.course_search_general_only, filters.generalOnly) { model.filters = filters.copy(generalOnly = it) }
        SwitchRow(R.string.course_search_english_taught, filters.englishTaught) { model.filters = filters.copy(englishTaught = it) }
        SwitchRow(R.string.course_search_intensive, filters.intensiveOnly) { model.filters = filters.copy(intensiveOnly = it) }
        SwitchRow(R.string.course_search_exclude_alliance, filters.excludeAlliance) {
            model.filters = filters.copy(excludeAlliance = it)
        }
    }
}

/** The departments by college, narrowed by their code or either name; picking one goes back. */
@Composable
private fun ColumnScope.DepartmentPage(model: CourseSearchModel, language: String, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val selection = model.filters.department
    fun pick(code: String?) {
        model.filters = model.filters.copy(department = code)
        back()
    }
    PageHeader(stringResource(R.string.course_search_department), onBack = back)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.action_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    LazyColumn(Modifier.weight(1f)) {
        if (query.isBlank()) {
            item { OptionRow(stringResource(R.string.course_search_any), selection == null) { pick(null) } }
        }
        CourseSearch.colleges(model.options.colleges, query).forEach { college ->
            item(key = "college-${college.name}") { SectionHeader(college.name(language)) }
            items(college.departments, key = { "department-${college.name}-${it.code}" }) { department ->
                OptionRow(department.name(language), selection == department.code) { pick(department.code) }
            }
        }
    }
}

/** Any, then each general-education type; picking one goes back, as the iPhone's picker does. */
@Composable
private fun ColumnScope.DimensionPage(model: CourseSearchModel, language: String, back: () -> Unit) {
    val selection = model.filters.dimension
    fun pick(code: String?) {
        model.filters = model.filters.copy(dimension = code)
        back()
    }
    PageHeader(stringResource(R.string.course_search_dimension), onBack = back)
    LazyColumn(Modifier.weight(1f)) {
        item { OptionRow(stringResource(R.string.course_search_any), selection == null) { pick(null) } }
        items(model.options.dimensions, key = { it.code }) { dimension ->
            OptionRow(dimension.name(language), selection == dimension.code) { pick(dimension.code) }
        }
    }
}

@Composable
private fun ColumnScope.PeriodsPage(model: CourseSearchModel, busy: Set<CourseSlot>, back: () -> Unit) {
    val periods = model.filters.periods
    PageHeader(stringResource(R.string.course_search_periods), onBack = back) {
        TextButton(onClick = { model.filters = model.filters.copy(periods = emptySet()) }, enabled = periods.isNotEmpty()) {
            Text(stringResource(R.string.course_search_clear_periods))
        }
    }
    CoursePeriodGrid(
        selection = periods,
        busy = busy,
        onSelection = { model.filters = model.filters.copy(periods = it) },
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
    )
    OutlinedButton(
        onClick = { model.filters = model.filters.copy(periods = CourseSearch.freeSlots(busy)) },
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp),
    ) { Text(stringResource(R.string.course_search_select_free_periods)) }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun NavRow(label: Int, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 1,
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}

// The segments sit under the label, so a long translation never squeezes them.
@Composable
private fun LevelRow(level: CourseSearchFilters.Level, onLevel: (CourseSearchFilters.Level) -> Unit) {
    val labels = listOf(
        CourseSearchFilters.Level.All to R.string.course_search_level_all,
        CourseSearchFilters.Level.Undergraduate to R.string.course_search_level_undergraduate,
        CourseSearchFilters.Level.Graduate to R.string.course_search_level_graduate,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.course_search_level), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = level == value,
                    onClick = { onLevel(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
