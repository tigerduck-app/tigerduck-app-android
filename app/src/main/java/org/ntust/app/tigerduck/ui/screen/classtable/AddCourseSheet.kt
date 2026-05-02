package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

// Course codes are ASCII alphanumeric with at least one digit
// (e.g. "EC1013701", "GE1002101"). Anything else is treated as a name or
// teacher query — matching the iOS AddCourseSheet heuristic.
private val courseCodePattern = Regex("^[A-Za-z0-9]+$")

private fun looksLikeCourseCode(text: String): Boolean =
    courseCodePattern.matches(text) && text.any { it.isDigit() }

// Map any simplified Han characters in the query to traditional before hitting
// the zh API — NTUST QueryCourse stores course names in traditional only.
private val simplifiedToTraditional by lazy {
    runCatching { android.icu.text.Transliterator.getInstance("Simplified-Traditional") }.getOrNull()
}

private fun toTraditional(text: String): String =
    simplifiedToTraditional?.transliterate(text) ?: text

private fun containsHan(text: String): Boolean = text.any {
    val block = Character.UnicodeBlock.of(it) ?: return@any false
    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCourseSheet(
    semester: String,
    existingCourseNos: Set<String>,
    courseService: CourseService,
    sheetState: SheetState,
    onAdd: (Course) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // Total available sheet container height (screen height). The sheet's
    // animated offset measures distance from this container's top, so the
    // currently visible sheet height = containerHeight - sheetOffset.
    val containerHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    // Reactively track the visible portion of the sheet so the placeholder
    // overlay can re-center as the user drags between partial and full.
    val visibleHeightDp by remember(sheetState, containerHeightPx) {
        derivedStateOf {
            val offset = runCatching { sheetState.requireOffset() }.getOrDefault(0f)
            with(density) { (containerHeightPx - offset).coerceAtLeast(0f).toDp() }
        }
    }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GroupedCourse>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var addedCourseNo by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun search() {
        val trimmed = searchText.trim()
        if (trimmed.isEmpty()) return
        errorMessage = null
        isSearching = true
        searchResults = emptyList()
        addedCourseNo = null
        searchJob?.cancel()

        val isCourseCode = looksLikeCourseCode(trimmed)
        // Always send the traditional form to the zh API so simplified queries
        // ("隐私") still match traditional course names ("隱私與資訊安全").
        val zhQuery = toTraditional(trimmed)
        val enQuery = trimmed
        val uiLang = courseService.preferredCourseApiLanguage()
        // Query language drives the parenthetical: when the query language
        // differs from the UI language, we display the other-language name in
        // parens. Course codes are language-agnostic — fall back to the UI
        // language so no parenthetical is shown.
        val queryLang = when {
            isCourseCode -> uiLang
            containsHan(trimmed) -> "zh"
            else -> "en"
        }
        searchJob = scope.launch {
            try {
                val (zhResults, enResults) = coroutineScope {
                    val zh = async {
                        runCatching {
                            if (isCourseCode) courseService.lookupCourse(semester, trimmed, "zh")
                            else mergeResults(
                                runCatching { courseService.searchCourses(semester, zhQuery, "zh") }.getOrDefault(emptyList()),
                                runCatching { courseService.searchByTeacher(semester, zhQuery, "zh") }.getOrDefault(emptyList()),
                            )
                        }.getOrDefault(emptyList())
                    }
                    val en = async {
                        runCatching {
                            if (isCourseCode) courseService.lookupCourse(semester, trimmed, "en")
                            else mergeResults(
                                runCatching { courseService.searchCourses(semester, enQuery, "en") }.getOrDefault(emptyList()),
                                runCatching { courseService.searchByTeacher(semester, enQuery, "en") }.getOrDefault(emptyList()),
                            )
                        }.getOrDefault(emptyList())
                    }
                    zh.await() to en.await()
                }
                // The name-search API matches against the queried language only, so
                // searching "隱私" against the EN endpoint returns nothing even when
                // a course exists. To still surface the other-language name for the
                // parenthetical, fill in any missing courseNos via lookupCourse,
                // which is keyed by code and language-agnostic. Lookups are cached
                // per (semester, courseNo, lang), so this is essentially free on
                // repeat queries.
                val (zhFilled, enFilled) = coroutineScope {
                    val zhByNo = zhResults.associateBy { it.courseNo }
                    val enByNo = enResults.associateBy { it.courseNo }
                    val missingZhNos = enByNo.keys - zhByNo.keys
                    val missingEnNos = zhByNo.keys - enByNo.keys
                    val zhLookups = missingZhNos.map { no ->
                        async {
                            runCatching { courseService.lookupCourse(semester, no, "zh") }
                                .getOrDefault(emptyList())
                        }
                    }
                    val enLookups = missingEnNos.map { no ->
                        async {
                            runCatching { courseService.lookupCourse(semester, no, "en") }
                                .getOrDefault(emptyList())
                        }
                    }
                    val zhExtras = zhLookups.flatMap { it.await() }
                    val enExtras = enLookups.flatMap { it.await() }
                    (zhResults + zhExtras) to (enResults + enExtras)
                }
                val grouped = groupBilingualResults(
                    zhResults = zhFilled,
                    enResults = enFilled,
                    courseService = courseService,
                    uiLang = uiLang,
                    queryLang = queryLang,
                )
                searchResults = grouped
                errorMessage = if (grouped.isEmpty()) resources.getString(R.string.add_course_not_found) else null
                isSearching = false
            } catch (e: CancellationException) {
                // Superseded by a newer search — leave state for the live job to manage.
                throw e
            } catch (e: Exception) {
                errorMessage = resources.getString(R.string.add_course_search_failed, e.message ?: "")
                isSearching = false
            }
        }
    }

    // Live search: debounce typing so we don't fire a request per keystroke,
    // but users no longer need to press the submit/search action to see results.
    // Skip very short queries — partial course codes hit an exact-match endpoint
    // and short name/teacher queries are rejected by the server, both of which
    // would otherwise surface as a misleading "not found".
    LaunchedEffect(searchText) {
        val trimmed = searchText.trim()
        if (trimmed.isEmpty()) {
            searchJob?.cancel()
            searchResults = emptyList()
            errorMessage = null
            isSearching = false
            return@LaunchedEffect
        }
        // Course codes need to be entered fully; only run the live search once
        // the code looks complete. For free-text queries, require at least 2
        // characters so a single CJK keystroke doesn't fire.
        val minLength = if (looksLikeCourseCode(trimmed)) 8 else 2
        if (trimmed.length < minLength) return@LaunchedEffect
        delay(300)
        search()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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

            // Unified search field — detects code / name / teacher from the input.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text(stringResource(R.string.add_course_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                        search()
                    }),
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(
                    onClick = { search() },
                    enabled = searchText.isNotBlank() && !isSearching
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                }
            }

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Results list — only present when we have results. For empty/loading
            // states the Column expands to fill the sheet via the Spacer below so
            // the centered overlay can position relative to the full sheet height.
            if (!isSearching && searchResults.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                items(searchResults) { group ->
                    val alreadyExists = group.courseNo in existingCourseNos || group.courseNo == addedCourseNo

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !alreadyExists) {
                                val course = Course.fromSchedule(
                                    courseNo = group.courseNo,
                                    courseName = group.courseName,
                                    instructor = group.instructor,
                                    credits = group.credits,
                                    classroom = group.classroom,
                                    enrolledCount = group.enrolledCount,
                                    maxCount = group.maxCount,
                                    schedule = group.schedule
                                )
                                onAdd(course)
                                addedCourseNo = group.courseNo
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                group.courseName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                stringResource(
                                    R.string.add_course_result_meta,
                                    group.courseNo,
                                    group.instructor,
                                    group.credits
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                            )
                            if (group.classroom.isNotEmpty()) {
                                Text(
                                    group.classroom,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                                )
                            }
                            if (group.nodeDisplay.isNotEmpty()) {
                                Text(
                                    group.nodeDisplay,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                                )
                            }
                        }
                        if (alreadyExists) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = stringResource(R.string.add_course_added),
                                tint = Color(0xFF34C759),
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.action_add),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    HorizontalDivider()
                }
                }
            } else {
                // Take all remaining vertical space so the parent Box can
                // center its overlay relative to the full sheet height.
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Overlay constrained to the *visible* sheet height so the contents
        // sit at the visual middle of whatever portion is currently on
        // screen — partial, fully expanded, or anywhere mid-drag.
        if (isSearching || searchResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(visibleHeightDp),
                contentAlignment = Alignment.Center
            ) {
                if (isSearching) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.add_course_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.add_course_example),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                        )
                    }
                }
            }
        }
    }
}

private data class GroupedCourse(
    val courseNo: String,
    val courseName: String,
    val instructor: String,
    val credits: Int,
    val classroom: String,
    val enrolledCount: Int,
    val maxCount: Int,
    val schedule: Map<Int, List<String>>,
    val nodeDisplay: String
)

/**
 * Merge name-search and teacher-search results, preserving name-first order
 * and deduping by (courseNo, node). Matches the iOS behaviour so the Android
 * feature stays in lockstep when the server adds/renames courses.
 */
private fun mergeResults(
    primary: List<CourseSearchResult>,
    secondary: List<CourseSearchResult>,
): List<CourseSearchResult> {
    val seen = mutableSetOf<String>()
    val merged = mutableListOf<CourseSearchResult>()
    for (result in primary + secondary) {
        val key = "${result.courseNo}#${result.node ?: ""}"
        if (seen.add(key)) merged.add(result)
    }
    return merged
}

private fun groupResults(results: List<CourseSearchResult>, courseService: CourseService): List<GroupedCourse> {
    val seen = mutableMapOf<String, GroupedCourse>()
    val order = mutableListOf<String>()

    for (result in results) {
        val key = result.courseNo
        val existing = seen[key]
        if (existing != null) {
            val partial = courseService.parseNodeToSchedule(result.node)
            val merged = existing.schedule.toMutableMap()
            for ((day, periods) in partial) {
                merged[day] = (merged[day] ?: emptyList()) + periods
            }
            val room = (result.classRoomNo ?: "").trim()
            val existingRooms = existing.classroom.split(",").map { it.trim() }
            val newClassroom = when {
                existing.classroom.isEmpty() -> room
                room.isEmpty() || room in existingRooms -> existing.classroom
                else -> "${existing.classroom}, $room"
            }
            val nodeStr = when {
                existing.nodeDisplay.isEmpty() -> result.node ?: ""
                result.node.isNullOrEmpty() -> existing.nodeDisplay
                else -> "${existing.nodeDisplay}, ${result.node}"
            }
            seen[key] = existing.copy(
                classroom = newClassroom,
                schedule = merged,
                nodeDisplay = nodeStr
            )
        } else {
            order.add(key)
            seen[key] = GroupedCourse(
                courseNo = result.courseNo,
                courseName = result.courseName,
                instructor = result.courseTeacher,
                credits = result.creditPoint.toIntOrNull() ?: 0,
                classroom = result.classRoomNo ?: "",
                enrolledCount = result.chooseStudent ?: 0,
                maxCount = result.maxEnrollment,
                schedule = courseService.parseNodeToSchedule(result.node),
                nodeDisplay = result.node ?: ""
            )
        }
    }

    return order.mapNotNull { seen[it] }
}

/**
 * Run [groupResults] on each language's payload and stitch them together so
 * each course carries both its zh and en names. When the input language
 * differs from the UI language we surface the other-language name in
 * parentheses ("English (中文)" or "中文 (English)") so users searching in a
 * language they don't read recognize their result.
 */
private fun groupBilingualResults(
    zhResults: List<CourseSearchResult>,
    enResults: List<CourseSearchResult>,
    courseService: CourseService,
    uiLang: String,
    queryLang: String,
): List<GroupedCourse> {
    val zhGroups = groupResults(zhResults, courseService).associateBy { it.courseNo }
    val enGroups = groupResults(enResults, courseService).associateBy { it.courseNo }

    val primaryGroups = if (uiLang == "zh") zhGroups else enGroups
    val secondaryGroups = if (uiLang == "zh") enGroups else zhGroups

    // Preserve UI-language ordering; tack on any courses only the other
    // language matched (covers the case where the API returns nothing for the
    // UI language but the bilingual query still found a match).
    val orderedKeys = LinkedHashSet<String>().apply {
        addAll(primaryGroups.keys)
        addAll(secondaryGroups.keys)
    }
    val showBilingual = queryLang != uiLang

    return orderedKeys.mapNotNull { key ->
        val primary = primaryGroups[key]
        val secondary = secondaryGroups[key]
        val base = primary ?: secondary ?: return@mapNotNull null
        val primaryName = primary?.courseName?.takeIf { it.isNotBlank() }
            ?: secondary?.courseName.orEmpty()
        val secondaryName = secondary?.courseName
        val displayName = if (showBilingual && !secondaryName.isNullOrBlank() && secondaryName != primaryName) {
            "$primaryName ($secondaryName)"
        } else primaryName
        base.copy(courseName = displayName)
    }
}
