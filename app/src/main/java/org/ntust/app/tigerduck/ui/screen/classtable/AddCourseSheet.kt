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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCourseSheet(
    semester: String,
    existingCourseNos: Set<String>,
    courseService: CourseService,
    onAdd: (Course) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
        searchJob = scope.launch {
            try {
                val raw = if (isCourseCode) {
                    courseService.lookupCourse(semester, trimmed)
                } else {
                    coroutineScope {
                        // Fire name + teacher queries concurrently, mirroring the
                        // iOS `async let` flow so a single input covers both.
                        val byName = async {
                            runCatching { courseService.searchCourses(semester, trimmed) }
                                .getOrDefault(emptyList())
                        }
                        val byTeacher = async {
                            runCatching { courseService.searchByTeacher(semester, trimmed) }
                                .getOrDefault(emptyList())
                        }
                        mergeResults(byName.await(), byTeacher.await())
                    }
                }
                searchResults = groupResults(raw, courseService)
                if (searchResults.isEmpty()) errorMessage = context.getString(R.string.add_course_not_found)
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.add_course_search_failed, e.message ?: "")
            } finally {
                isSearching = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
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

        // Results
        if (isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (searchResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
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
        } else {
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
