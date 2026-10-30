package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.CourseSyllabus
import org.ntust.app.tigerduck.network.CourseSyllabusBreakdown
import org.ntust.app.tigerduck.ui.openLink
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleTopBar
import org.ntust.app.tigerduck.ui.screen.coursemoodle.cardDividerColor
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import java.text.NumberFormat

/** The routes of a course's syllabus page, opened from the course detail. */
object CourseSyllabusRoutes {
    const val ARG_SEMESTER = "semester"
    const val ARG_COURSE_NO = "courseNo"
    const val SYLLABUS = "classTable/course/syllabus/{$ARG_SEMESTER}/{$ARG_COURSE_NO}"

    fun syllabus(semester: String, courseNo: String) =
        "classTable/course/syllabus/${android.net.Uri.encode(semester)}/${android.net.Uri.encode(courseNo)}"
}

/** The teaching methods' names in the app's language, for [CourseSyllabusBreakdown.teaching]. */
@Composable
internal fun syllabusTeachingLabels() = CourseSyllabusBreakdown.TeachingLabels(
    lecture = stringResource(R.string.course_detail_syllabus_lecture),
    groupDiscussion = stringResource(R.string.course_detail_syllabus_group_discussion),
    caseStudy = stringResource(R.string.course_detail_syllabus_case_study),
    practical = stringResource(R.string.course_detail_syllabus_practical),
    other = stringResource(R.string.course_detail_syllabus_other),
)

/**
 * Loads the syllabus the route names. The course detail has fetched it already to offer this
 * page, and [CourseService] keeps it for the app's run, so it is there at once; after the
 * process was ended it is fetched again.
 */
@HiltViewModel
class CourseSyllabusViewModel @javax.inject.Inject constructor(
    savedState: SavedStateHandle,
    private val courseService: CourseService,
) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Loaded(val syllabus: CourseSyllabus?) : State
        data object Failed : State
    }

    private val semester: String = savedState[CourseSyllabusRoutes.ARG_SEMESTER] ?: ""
    private val courseNo: String = savedState[CourseSyllabusRoutes.ARG_COURSE_NO] ?: ""

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    // The course service's language the syllabus on screen is in, and its load; main-thread-only.
    private var language: String? = null
    private var loading: Job? = null

    init {
        load()
    }

    fun load() {
        val asked = courseService.preferredCourseApiLanguage()
        language = asked
        _state.value = State.Loading
        // A load for the language before replaces nothing after it.
        loading?.cancel()
        loading = viewModelScope.launch {
            _state.value = try {
                State.Loaded(courseService.syllabus(semester, courseNo, asked))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                State.Failed
            }
        }
    }

    /** Loads again when the app's language for the course service has changed since; see [load]. */
    fun followLanguage() {
        if (courseService.preferredCourseApiLanguage() != language) load()
    }
}

/**
 * A course's syllabus, as the iPhone's (tigerduck-app#885): grading and teaching methods as
 * shares, then the outline, the details a tap opens, and the course's website.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSyllabusScreen(
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: CourseSyllabusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The headings follow a change of the app's language; the syllabus, in the school's words,
    // loads again in the new one.
    val locales = LocalConfiguration.current.locales
    LaunchedEffect(locales) { viewModel.followLanguage() }
    Scaffold(
        topBar = { MoodleTopBar(title = stringResource(R.string.course_detail_syllabus), onBack = onBack, moodlePath = null) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (val current = state) {
                CourseSyllabusViewModel.State.Loading ->
                    CircularProgressIndicator(Modifier.padding(top = 60.dp))
                CourseSyllabusViewModel.State.Failed ->
                    TextButton(onClick = viewModel::load, modifier = Modifier.padding(top = 60.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                is CourseSyllabusViewModel.State.Loaded ->
                    current.syllabus?.let { CourseSyllabusContent(it, browserPreference) }
            }
        }
    }
}

@Composable
internal fun CourseSyllabusContent(syllabus: CourseSyllabus, browserPreference: String) {
    val labels = syllabusTeachingLabels()
    val grading = syllabus.grading
    val gradingParts = grading?.let(CourseSyllabusBreakdown::grading)
    val teachingParts = CourseSyllabusBreakdown.teaching(syllabus, labels)
    val details = listOfNotNull(
        syllabus.content?.let { stringResource(R.string.course_detail_syllabus_content) to it },
        syllabus.references?.let { stringResource(R.string.course_detail_syllabus_references) to it },
        syllabus.notes?.let { stringResource(R.string.course_detail_syllabus_notes) to it },
        syllabus.objectives?.let { stringResource(R.string.course_detail_syllabus_objectives) to it },
    )
    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (grading != null) {
            SyllabusCard(stringResource(R.string.course_detail_syllabus_grading)) {
                if (gradingParts != null) SyllabusBreakdown(gradingParts) else SyllabusText(grading)
            }
        }
        syllabus.outline?.let { outline ->
            SyllabusCard(stringResource(R.string.course_detail_syllabus_outline)) { SyllabusText(outline) }
        }
        if (teachingParts != null) {
            SyllabusCard(stringResource(R.string.course_detail_syllabus_method)) { SyllabusBreakdown(teachingParts) }
        }
        if (details.isNotEmpty()) SyllabusDetails(details)
        syllabus.website?.let { SyllabusWebsite(it, browserPreference) }
    }
}

@Composable
private fun SyllabusCard(heading: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun SyllabusText(text: String) {
    SelectionContainer {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The content, references, notes and objectives, each opened by a tap, in one card. */
@Composable
private fun SyllabusDetails(details: List<Pair<String, String>>) {
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            details.forEachIndexed { index, (heading, text) ->
                if (index > 0) HorizontalDivider(color = cardDividerColor())
                val expanded = heading in open
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { open = if (expanded) open - heading else open + heading }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) SyllabusText(text)
                }
            }
        }
    }
}

@Composable
private fun SyllabusWebsite(url: String, browserPreference: String) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { openLink(context, url, browserPreference) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.course_detail_syllabus_website),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// Distinct hues of the class table's palette, as the iPhone's tint palette for the bar.
private val breakdownColors: List<Color> = listOf(7, 3, 5, 9, 10, 0, 8, 2).map { courseColorPalette[it] }

/** Shares as one stacked bar, then a row per part with its color, label and percentage. */
@Composable
private fun SyllabusBreakdown(parts: List<CourseSyllabusBreakdown.Part>) {
    var next = 0
    val colors = parts.map { part ->
        if ((part.percent ?: 0.0) > 0) breakdownColors[next++ % breakdownColors.size] else null
    }
    val segments = parts.zip(colors).mapNotNull { (part, color) -> color?.let { part to it } }
    val total = segments.sumOf { it.first.percent ?: 0.0 }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (segments.isNotEmpty() && total > 0) {
            val spoken = segments.joinToString { (part, _) -> "${part.label} ${percentText(part.percent ?: 0.0)}" }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clearAndSetSemantics { contentDescription = spoken },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                segments.forEach { (part, color) ->
                    Box(Modifier.weight(((part.percent ?: 0.0) / total).toFloat().coerceAtLeast(0.001f)).fillMaxSize().background(color))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.zip(colors).forEach { (part, color) ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color ?: Color.Transparent),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(part.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        part.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    part.percent?.let {
                        Text(percentText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

/** 30 as "30%", 12.5 as "12.5%", in the locale's digits. */
internal fun percentText(percent: Double): String =
    NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(percent / 100)
