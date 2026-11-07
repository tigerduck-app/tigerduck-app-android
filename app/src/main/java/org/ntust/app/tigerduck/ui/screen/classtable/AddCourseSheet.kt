package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogAccess
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogStore
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.DetentSheetState
import org.ntust.app.tigerduck.ui.component.scrollbar
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.io.IOException

/**
 * Add course: searches the term's course catalog from the TigerDuck backend on the device, as the
 * iPhone's sheet does (tigerduck-app#876). The stored catalog answers at once; the backend is asked
 * once per opening whether it changed, and waited for only while nothing is stored.
 */
@Composable
internal fun AddCourseSheet(
    semester: String,
    tableCourses: List<Course>,
    courseService: CourseService,
    sheetState: DetentSheetState,
    /**
     * Returns true iff the course was actually persisted. The sheet uses this to gate
     * `sessionAddedCourseNos`: a rejected add (a triple-period conflict, say) must not flip the
     * row to a checkmark whose next tap would route through [onRemove].
     */
    onAdd: (Course) -> Boolean,
    /** Takes back a course added in this session; see [ClassTableViewModel.removeAddedCourse]. */
    onRemove: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val access = remember { EntryPointAccessors.fromApplication(context.applicationContext, CourseCatalogAccess::class.java) }
    val scope = rememberCoroutineScope()
    val model = remember { CourseSearchModel(scope, access.courseCatalogFiles(), label = courseService::applyAbbreviations) }
    val fetch = rememberCatalogFetch(access)
    val language = remember { courseService.preferredCourseApiLanguage() }
    var searchText by remember { mutableStateOf("") }
    // The courses added while this sheet is open, kept across searches: each stays tappable, and
    // a second tap takes it back out. Courses already on the class table are not in it.
    var sessionAddedCourseNos by remember { mutableStateOf<Set<String>>(emptySet()) }
    val query = searchText.trim()
    val table = remember(tableCourses) {
        tableCourses.map { CourseSearchTableCourse(it.courseNo, it.displayName, CourseSearch.slots(it.schedule)) }
    }

    // Every keystroke searches the stored catalog at once: no wait, and no request to the school.
    LaunchedEffect(query, model.filters.searched, model.searchAgainCount) {
        model.search(query, semester, fetch)
    }

    val shown = remember(model.results, table, model.filters) {
        AddCourseResults.shown(model.results, language, table, model.filters, courseService::parseNodeToSchedule)
    }
    val foundByNumber = remember(model.results, query) { CourseSearch.foundByNumber(query, model.results) }
    val existingCourseNos = remember(table) { table.mapTo(HashSet()) { it.courseNo } }
    val otherLanguage = CourseCatalogStore.LANGUAGES.first { it != language }

    AddCourseSearchPage(
        searchText = searchText,
        onSearchText = { searchText = it },
        model = model,
        hasResults = shown.isNotEmpty(),
        sheetState = sheetState,
        onDismiss = onDismiss,
    ) {
        items(shown, key = { it.course.courseNo }) { item ->
            val course = item.course
            val names = AddCourseResults.names(
                course, model.row(course.courseNo, language), model.row(course.courseNo, otherLanguage),
                query, foundByNumber, language,
            )
            val state = addCourseRowState(course.courseNo, existingCourseNos, sessionAddedCourseNos)
            AddCourseResultRow(item, names, state) {
                when (state) {
                    AddCourseRowState.AddedThisSession -> {
                        sessionAddedCourseNos = sessionAddedCourseNos - course.courseNo
                        onRemove(course.courseNo)
                    }
                    AddCourseRowState.Addable ->
                        if (onAdd(course.tableCourse(names.name, names.instructor))) {
                            sessionAddedCourseNos = sessionAddedCourseNos + course.courseNo
                        }
                    AddCourseRowState.AlreadyThere -> Unit
                }
            }
            HorizontalDivider()
        }
    }
}

/** The fetch of this sheet's sign-in: one read of it as the sheet opens, which a sign-out ends. */
@OptIn(StartsAccountWork::class)
@Composable
private fun rememberCatalogFetch(access: CourseCatalogAccess): CourseCatalogStore.Fetch = remember {
    val signIn = access.accountSessions().school.current
    val client = access.courseCatalogClient()
    CourseCatalogStore.Fetch { semester, language, etag -> client.fetch(semester, language, etag, signIn) }
}

/**
 * Why the search failed, as the sheet says it in place of "not found" when no catalog can be had:
 * a lost connection, or no catalog of the term (a missing one, a refused session).
 */
internal fun searchFailureReason(failure: Throwable): Int =
    if (failure is IOException) R.string.error_network_unavailable else R.string.error_courses_unavailable

@Composable
private fun AddCourseSearchPage(
    searchText: String,
    onSearchText: (String) -> Unit,
    model: CourseSearchModel,
    hasResults: Boolean,
    sheetState: DetentSheetState,
    onDismiss: () -> Unit,
    results: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.add_course_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
            Spacer(Modifier.height(12.dp))
            // One field for a course number, a name or a teacher. Enter only retries a failure.
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchText,
                placeholder = { Text(stringResource(R.string.add_course_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    model.submit()
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus)
            )
            model.failure?.let {
                Text(
                    stringResource(R.string.add_course_search_failed, stringResource(searchFailureReason(it))),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (hasResults) {
                val resultsState = rememberLazyListState()
                LazyColumn(
                    state = resultsState,
                    modifier = Modifier.weight(1f).scrollbar(resultsState),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    content = results,
                )
            } else {
                // Takes the rest, so the overlay below centers on the whole sheet.
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        if (!hasResults) EmptyState(searchText.isBlank(), model, visibleSheetHeight(sheetState))
    }
}

/**
 * The visible part of the sheet, which the empty state centers in: partial, full, or anywhere
 * mid-drag. The sheet's offset is measured from the top of a container as tall as the screen.
 */
@Composable
private fun visibleSheetHeight(sheetState: DetentSheetState): Dp {
    val density = LocalDensity.current
    val containerHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val height by remember(sheetState, containerHeightPx) {
        derivedStateOf {
            val offset = sheetState.offset.takeUnless { it.isNaN() } ?: 0f
            with(density) { (containerHeightPx - offset).coerceAtLeast(0f).toDp() }
        }
    }
    return height
}

/** The spinner while nothing is stored yet, the field's prompt before a query, and "not found" after one. */
@Composable
private fun EmptyState(blank: Boolean, model: CourseSearchModel, visibleHeight: Dp) {
    val message = when {
        model.isSearching -> null
        blank -> R.string.add_course_placeholder
        model.hasSearched && model.failure == null -> R.string.add_course_not_found
        else -> return
    }
    Box(modifier = Modifier.fillMaxWidth().height(visibleHeight), contentAlignment = Alignment.Center) {
        if (message == null) {
            CircularProgressIndicator()
        } else {
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
    }
}
