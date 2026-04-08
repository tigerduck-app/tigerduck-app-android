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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

private enum class SearchMode(val label: String) {
    COURSE_CODE("課程代碼"),
    COURSE_NAME("課名搜尋")
}

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
    var searchMode by remember { mutableStateOf(SearchMode.COURSE_CODE) }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GroupedCourse>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var addedCourseNo by remember { mutableStateOf<String?>(null) }

    fun search() {
        val trimmed = searchText.trim()
        if (trimmed.isEmpty()) return
        errorMessage = null
        isSearching = true
        searchResults = emptyList()
        addedCourseNo = null

        scope.launch {
            try {
                val results = when (searchMode) {
                    SearchMode.COURSE_CODE -> courseService.lookupCourse(semester, trimmed)
                    SearchMode.COURSE_NAME -> courseService.searchCourses(semester, trimmed)
                }
                searchResults = groupResults(results, courseService)
                if (searchResults.isEmpty()) errorMessage = "找不到符合的課程"
            } catch (e: Exception) {
                errorMessage = "搜尋失敗：${e.message}"
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
                "新增課程",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("關閉") }
        }

        Spacer(Modifier.height(12.dp))

        // Search mode picker
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            SearchMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = searchMode == mode,
                    onClick = { searchMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, SearchMode.entries.size)
                ) {
                    Text(mode.label)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Search field
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
                placeholder = {
                    Text(
                        if (searchMode == SearchMode.COURSE_CODE) "例如：EC1013701"
                        else "例如：微積分"
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(
                onClick = { search() },
                enabled = searchText.isNotBlank() && !isSearching
            ) {
                Icon(Icons.Filled.Search, contentDescription = "搜尋")
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
                Text(
                    "輸入課程代碼或課名後搜尋",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
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
                                "${group.courseNo} · ${group.instructor} · ${group.credits}學分",
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
                                contentDescription = "已加入",
                                tint = Color(0xFF34C759),
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "新增",
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
                maxCount = result.restrict1?.toIntOrNull() ?: 0,
                schedule = courseService.parseNodeToSchedule(result.node),
                nodeDisplay = result.node ?: ""
            )
        }
    }

    return order.mapNotNull { seen[it] }
}
