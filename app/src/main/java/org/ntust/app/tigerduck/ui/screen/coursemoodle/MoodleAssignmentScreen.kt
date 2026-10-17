package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleAccountAssignments
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentEntry
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentLookup
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.data.moodle.MoodleFeed
import org.ntust.app.tigerduck.data.moodle.MoodleFileOwner
import org.ntust.app.tigerduck.data.moodle.MoodleFileRef
import org.ntust.app.tigerduck.data.moodle.MoodlePersonName
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentGrade
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.moodle.MoodleFileUrls
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.rememberAppClockVersion
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.util.Locale

/** What the page shows of an assignment, apart from what is on screen. */
internal object AssignmentPageRules {
    /**
     * The assignment's page on the Moodle site: by its course module, else by the link the round
     * gave Home's row; null without either, for a row from a cache written before the link was.
     */
    fun moodlePath(entry: MoodleAssignmentEntry): String? {
        if (entry.record.cmid > 0) return MoodlePage.assignment(entry.record.cmid)
        val url = entry.listed?.moodleUrl?.takeIf { it.startsWith(MoodleWebservice.SITE + "/") } ?: return null
        return url.removePrefix(MoodleWebservice.SITE)
    }

    /**
     * Where the bottom button opens the assignment for submitting; null, and no button, for an
     * assignment that takes no online submission or has no page to open.
     */
    fun submitPath(entry: MoodleAssignmentEntry): String? =
        if (entry.record.noSubmissions) null else moodlePath(entry)

    /** The due time the page shows, Unix seconds: the record's, else Home's row's; null for an undated one. */
    fun dueSeconds(entry: MoodleAssignmentEntry): Long? =
        entry.record.dueDate.takeIf { it > 0 } ?: entry.listed?.dueDate?.let { it.time / 1000 }

    /**
     * The submission's time, Unix seconds, and before the first fetch answers, the one the
     * assignment round found; null for anything not handed in, a draft included.
     */
    fun submittedSeconds(entry: MoodleAssignmentEntry, submission: MoodleAssignmentSubmission?): Long? {
        if (submission != null) return submission.submittedAt
        val listed = entry.listed ?: return null
        return if (listed.isCompleted) listed.submittedAt?.let { it.time / 1000 } else null
    }

    /**
     * The course's name for the page's header: Home's row's, else that of any row of the same
     * course, for an assignment Home leaves out; empty while no row names it.
     */
    fun courseName(entry: MoodleAssignmentEntry, assignments: MoodleAccountAssignments?): String {
        entry.listed?.courseName?.takeIf { it.isNotEmpty() }?.let { return it }
        val courseId = entry.record.courseId.takeIf { it > 0 } ?: return ""
        val siblings = assignments?.records?.value.orEmpty().filter { it.courseId == courseId }.map { it.assignId.toString() }.toSet()
        return assignments?.listed?.value.orEmpty().firstOrNull { it.assignmentId in siblings }?.courseName.orEmpty()
    }
}

/** The badge beside the submission's heading. */
internal sealed interface SubmissionBadge {
    data object NotSubmitted : SubmissionBadge

    /** Handed in, with no due date or no time to measure against it. */
    data object Submitted : SubmissionBadge
    data class Early(val seconds: Long) : SubmissionBadge
    data class Late(val seconds: Long) : SubmissionBadge
}

/** The line under the due time. */
internal sealed interface DeadlineLine {
    /** [urgent] while the account has not handed it in. */
    data class Left(val seconds: Long, val urgent: Boolean) : DeadlineLine

    /** Passed, with the assignment handed in or not one Moodle takes online. */
    data object Passed : DeadlineLine

    /** Passed without the account handing it in. */
    data class Overdue(val seconds: Long) : DeadlineLine
}

/** A grade split for the grade card: the points, over the most they could be where Moodle writes them so. */
internal data class Score(val value: String, val max: String?)

/** The rules behind the page's deadline, badge and grade; ported from iOS `MoodleAssignmentView+Status`. */
internal object AssignmentPageStatus {
    /**
     * Whether the account handed it in: the fetched submission says, else Home's row. Null while
     * neither has answered, and for an assignment Moodle takes nothing online for.
     */
    fun isSubmitted(entry: MoodleAssignmentEntry, submission: MoodleAssignmentSubmission?): Boolean? {
        if (entry.record.noSubmissions) return null
        return submission?.isSubmitted ?: entry.listed?.isCompleted
    }

    /** Null while nothing says whether it is handed in. */
    fun badge(isSubmitted: Boolean?, submittedAtSeconds: Long?, dueSeconds: Long?): SubmissionBadge? {
        isSubmitted ?: return null
        if (!isSubmitted) return SubmissionBadge.NotSubmitted
        if (dueSeconds == null || submittedAtSeconds == null) return SubmissionBadge.Submitted
        return if (submittedAtSeconds > dueSeconds) {
            SubmissionBadge.Late(submittedAtSeconds - dueSeconds)
        } else {
            SubmissionBadge.Early(dueSeconds - submittedAtSeconds)
        }
    }

    fun deadline(dueSeconds: Long, isSubmitted: Boolean?, nowSeconds: Long): DeadlineLine {
        if (dueSeconds >= nowSeconds) return DeadlineLine.Left(dueSeconds - nowSeconds, urgent = isSubmitted == false)
        return if (isSubmitted == false) DeadlineLine.Overdue(nowSeconds - dueSeconds) else DeadlineLine.Passed
    }

    /** The card shows what was handed in, or a draft's files and text, under its time. */
    fun showsCard(submission: MoodleAssignmentSubmission): Boolean =
        submission.files.isNotEmpty() || submission.onlineText != null || submission.submittedAt != null

    private val NUMBER = Regex("-?[0-9]+([.,][0-9]+)?")

    /**
     * A grade of points over a maximum, as Moodle writes "85.00 / 100.00", splits into the two,
     * each without the zeros ending its decimals. Any other grade, such as a letter, a percentage
     * or a scale's word, stays whole.
     */
    fun score(grade: String): Score {
        val parts = grade.split("/").map { it.trim() }
        if (parts.size != 2 || !parts.all { NUMBER.matches(it) }) return Score(grade, null)
        return Score(withoutTrailingZeros(parts[0]), withoutTrailingZeros(parts[1]))
    }

    private fun withoutTrailingZeros(number: String): String {
        if (number.none { it == '.' || it == ',' }) return number
        return number.trimEnd('0').trimEnd('.', ',')
    }

    /**
     * [seconds] in at most two of days, hours and minutes, rounded down so the time left never
     * reads longer than it is; under a minute reads as one minute. "1 day, 4 hr" in English.
     */
    fun durationText(seconds: Long, locale: Locale = Locale.getDefault()): String {
        val total = maxOf(seconds, 60L)
        val units = listOf(
            MeasureUnit.DAY to total / 86_400,
            MeasureUnit.HOUR to total % 86_400 / 3_600,
            MeasureUnit.MINUTE to total % 3_600 / 60,
        ).filter { it.second > 0 }.take(2).map { (unit, amount) -> Measure(amount, unit) }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(*units.toTypedArray())
    }
}

/**
 * One assignment: its course and title, a card with the due time and the group, its description
 * and attachments, then what the account handed in with the comments on it, and the grade. The
 * app submits nothing, so the button at the foot opens the assignment on Moodle. The page fetches
 * the submission when it opens and on each return to the app, and shows the last copy meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleAssignmentScreen(
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: MoodleAssignmentViewModel = hiltViewModel(),
) {
    val assignments by viewModel.assignments.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel.signedIn, held = assignments, isCurrent = { it.isCurrent() }, onClose = onBack)
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val store by viewModel.store.collectAsStateWithLifecycle()
    val entry = (lookup as? MoodleAssignmentLookup.Found)?.entry
    // An id the cache does not know is not this account's homework, so a link from elsewhere opens nothing.
    if (lookup == MoodleAssignmentLookup.Missing) LaunchedEffect(Unit) { onBack() }
    val feed = remember(assignments, entry?.record?.assignId) { entry?.let { assignments?.submission(it.record.assignId) } }
    val submission = feed?.state?.collectAsStateWithLifecycle()?.value ?: MoodleFeed.State()
    // On each return to the app too, as a submission made on Moodle shows once the app is back;
    // the view model asks Moodle only once the copy is old, or at once after its own hand-off.
    LifecycleResumeEffect(entry?.record?.assignId, assignments) {
        entry?.let { viewModel.refreshOnResume(it) }
        onPauseOrDispose { }
    }

    // One for the whole page, so of every file tapped only the latest opens, and a page opened
    // on Moodle stops a file still downloading from opening over it.
    val files = store?.let { rememberMoodleFileOpener(it, browserPreference) }
    Scaffold(
        topBar = {
            MoodleTopBar(
                title = "",
                onBack = onBack,
                moodlePath = entry?.let(AssignmentPageRules::moodlePath),
                willOpenPage = { files?.forgetPendingOpen() },
            )
        },
        bottomBar = {
            entry?.let {
                AssignmentSubmitButton(it, onOpen = {
                    files?.forgetPendingOpen()
                    viewModel.noteMoodleHandOff()
                })
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (entry == null) {
                MoodleFirstLoad(failure = null, onRetry = {})
            } else {
                AssignmentPageBody(
                    entry = entry,
                    courseName = AssignmentPageRules.courseName(entry, assignments),
                    submission = submission,
                    store = store,
                    browserPreference = browserPreference,
                    files = files,
                    onRetry = { viewModel.refresh(entry, force = true) },
                )
            }
        }
    }
}

/** The page's body, under its bar and over its button: everything the page says of [entry]. */
@Composable
internal fun AssignmentPageBody(
    entry: MoodleAssignmentEntry,
    courseName: String,
    submission: MoodleFeed.State<MoodleAssignmentSubmission>,
    store: MoodleCourseStore?,
    browserPreference: String,
    // The page's one opener, over [store]; without it the files open as links on the site.
    files: MoodleFileOpener? = null,
    onRetry: () -> Unit,
) {
    val record = entry.record
    val value = submission.value
    val moodlePath = AssignmentPageRules.moodlePath(entry)
    val isSubmitted = AssignmentPageStatus.isSubmitted(entry, value)
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (courseName.isNotEmpty()) {
                Text(
                    text = courseName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
            Text(
                text = record.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        SummaryCard(entry, isSubmitted, value?.groupName)
        submission.failureBesideCopy?.let { MoodleRefreshFailure(it, onRetry) }
        val description = rememberMoodleMarkdown(record.intro)
        if (!description.isNullOrBlank()) {
            PageSection(stringResource(R.string.assignment_detail_description)) {
                MoodleMarkdown(description, browserPreference, Modifier.fillMaxWidth())
            }
        }
        if (record.introAttachments.isNotEmpty()) {
            PageSection(stringResource(R.string.school_mail_attachments)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        AssignmentFileRows(store, files, record.introAttachments, MoodleFileOwner.Module(record.cmid), browserPreference, moodlePath)
                    }
                }
            }
        }
        if (!record.noSubmissions) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SubmissionSection(
                entry = entry,
                submission = submission,
                badge = AssignmentPageStatus.badge(
                    isSubmitted,
                    AssignmentPageRules.submittedSeconds(entry, value),
                    AssignmentPageRules.dueSeconds(entry),
                ),
                store = store,
                files = files,
                browserPreference = browserPreference,
                moodlePath = moodlePath,
                onRetry = onRetry,
            )
        }
        value?.grade?.let { GradeSection(it, browserPreference) }
    }
}

// --- Due time and group ---

/** The due date as a tile with the time and what is left of it, and the group for a team assignment. */
@Composable
private fun SummaryCard(entry: MoodleAssignmentEntry, isSubmitted: Boolean?, group: String?) {
    val due = AssignmentPageRules.dueSeconds(entry)
    if (due == null && group == null) return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (due != null) {
                // The time left moves on while the page stays open, on the app's clock.
                val clockVersion by rememberAppClockVersion()
                val deadline = remember(due, isSubmitted, clockVersion) {
                    AssignmentPageStatus.deadline(due, isSubmitted, AppClock.nowMillis() / 1000)
                }
                DeadlineRow(due, deadline)
            }
            if (group != null) {
                if (due != null) HorizontalDivider(color = cardDividerColor())
                GroupRow(group)
            }
        }
    }
}

@Composable
private fun DeadlineRow(dueSeconds: Long, deadline: DeadlineLine) {
    val overdue = deadline is DeadlineLine.Overdue
    val text = when (deadline) {
        is DeadlineLine.Left -> stringResource(R.string.assignment_detail_time_left, AssignmentPageStatus.durationText(deadline.seconds))
        DeadlineLine.Passed -> stringResource(R.string.assignment_detail_due_passed)
        is DeadlineLine.Overdue -> stringResource(R.string.assignment_detail_overdue_by, AssignmentPageStatus.durationText(deadline.seconds))
    }
    val tint = when (deadline) {
        is DeadlineLine.Left -> if (deadline.urgent) MoodleOrange else MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        DeadlineLine.Passed -> MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        is DeadlineLine.Overdue -> BadgeRed
    }
    val emphasized = overdue || (deadline as? DeadlineLine.Left)?.urgent == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CalendarTile(dueSeconds, overdue)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.assignment_detail_due),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
            Text(
                text = MoodleDates.weekdayTime(dueSeconds),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = tint,
            )
        }
    }
}

/** The month over the day, washed red once the deadline is missed. */
@Composable
private fun CalendarTile(dueSeconds: Long, overdue: Boolean) {
    Column(
        modifier = Modifier
            .background(
                if (overdue) BadgeRed.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                RoundedCornerShape(12.dp),
            )
            .defaultMinSize(minWidth = 56.dp, minHeight = 60.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = MoodleDates.month(dueSeconds),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (overdue) BadgeRed else MaterialTheme.colorScheme.primary,
        )
        Text(
            text = MoodleDates.dayOfMonth(dueSeconds).toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GroupRow(group: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.assignment_detail_group),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
        Text(
            text = group,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// --- Submission ---

@Composable
private fun SubmissionSection(
    entry: MoodleAssignmentEntry,
    submission: MoodleFeed.State<MoodleAssignmentSubmission>,
    badge: SubmissionBadge?,
    store: MoodleCourseStore?,
    files: MoodleFileOpener?,
    browserPreference: String,
    moodlePath: String?,
    onRetry: () -> Unit,
) {
    val value = submission.value
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.assignment_detail_submission), Modifier.weight(1f))
            badge?.let { BadgeChip(it) }
        }
        if (value == null) {
            MoodleFirstLoad(failure = if (submission.isRefreshing) null else submission.failure, onRetry = onRetry)
        } else {
            if (AssignmentPageStatus.showsCard(value)) {
                SubmissionCard(entry.record.assignId, value, store, files, browserPreference, moodlePath)
            }
            value.comments?.takeIf { it.isNotEmpty() }?.let { CommentList(it, browserPreference) }
        }
    }
}

/** A pill with the badge's word and mark: a tick for a hand-in, an empty circle for none. */
@Composable
private fun BadgeChip(badge: SubmissionBadge) {
    val text = when (badge) {
        SubmissionBadge.NotSubmitted -> stringResource(R.string.assignment_detail_not_submitted)
        SubmissionBadge.Submitted -> stringResource(R.string.assignment_status_submitted)
        is SubmissionBadge.Early -> stringResource(R.string.assignment_detail_submitted_early, AssignmentPageStatus.durationText(badge.seconds))
        is SubmissionBadge.Late -> stringResource(R.string.assignment_detail_submitted_late_by, AssignmentPageStatus.durationText(badge.seconds))
    }
    val tint = when (badge) {
        SubmissionBadge.NotSubmitted -> MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        SubmissionBadge.Submitted, is SubmissionBadge.Early -> BadgeGreen
        is SubmissionBadge.Late -> BadgeRed
    }
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (badge == SubmissionBadge.NotSubmitted) Icons.Outlined.RadioButtonUnchecked else Icons.Filled.Check,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(text = text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}

/** The time it was handed in, or a draft's, over the files and the text. */
@Composable
private fun SubmissionCard(
    assignId: Int,
    submission: MoodleAssignmentSubmission,
    store: MoodleCourseStore?,
    files: MoodleFileOpener?,
    browserPreference: String,
    moodlePath: String?,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            TimeRow(
                label = stringResource(
                    if (submission.isSubmitted) R.string.assignment_detail_submitted_at else R.string.assignment_detail_draft,
                ),
                time = submission.modified?.let { MoodleDates.dayTime(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (submission.files.isNotEmpty()) {
                HorizontalDivider(color = cardDividerColor())
                AssignmentFileRows(store, files, submission.files, MoodleFileOwner.Submission(assignId), browserPreference, moodlePath)
            }
            submission.onlineText?.let { html ->
                HorizontalDivider(color = cardDividerColor())
                rememberMoodleMarkdown(html)?.let {
                    MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}

/**
 * [files] as the materials and announcements list theirs, one under the other with a line between:
 * downloaded through [store] and opened from the copy by the page's [opener], with the download's
 * progress on the row. A page whose assignment names no course has no store to download through,
 * so its files open as their own links on the site, with no key in them.
 */
@Composable
private fun AssignmentFileRows(
    store: MoodleCourseStore?,
    opener: MoodleFileOpener?,
    files: List<MoodleFile>,
    owner: MoodleFileOwner,
    browserPreference: String,
    moodlePath: String?,
) {
    val context = LocalContext.current
    val snapshot = store?.downloads?.snapshot?.collectAsStateWithLifecycle()?.value
    val openInMoodle = {
        opener?.forgetPendingOpen()
        moodlePath?.let { openMoodlePage(context, it) }
        Unit
    }
    files.forEachIndexed { index, file ->
        if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = MoodleRowTextInset), color = cardDividerColor())
        val ref = MoodleFileRef(file, owner)
        val state = if (store != null && snapshot != null) store.downloadState(ref, snapshot) else MoodleDownloadState.NotDownloaded
        MoodleFileRow(
            title = file.name,
            file = file,
            state = state,
            onTap = {
                if (opener != null) {
                    opener.tap(ref, state)
                } else {
                    openMoodleContentLink(context, MoodleFileUrls.webUrl(file.url) ?: file.url, browserPreference)
                }
            },
            onShare = {
                if (opener != null) opener.share(ref) else shareMoodleLink(context, file.name, MoodleFileUrls.webUrl(file.url) ?: file.url)
            },
            onOpenInMoodle = openInMoodle,
        )
    }
}

@Composable
private fun CommentList(comments: List<MoodleSubmissionComment>, browserPreference: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.assignment_detail_comments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.semantics { heading() },
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column {
                comments.forEachIndexed { index, comment ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = cardDividerColor())
                    CommentItem(comment, browserPreference)
                }
            }
        }
    }
}

@Composable
private fun CommentItem(comment: MoodleSubmissionComment, browserPreference: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = listOf(MoodlePersonName.displayName(comment.authorName), MoodleDates.dayTime(comment.created))
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        rememberMoodleMarkdown(comment.content)?.let {
            MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

// --- Grade ---

@Composable
private fun GradeSection(grade: MoodleAssignmentGrade, browserPreference: String) {
    PageSection(stringResource(R.string.assignment_detail_grade)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                grade.grade?.let { ScoreRow(AssignmentPageStatus.score(it)) }
                grade.gradedAt?.let { TimeRow(stringResource(R.string.assignment_detail_graded_at), MoodleDates.dayTime(it)) }
                grade.feedback?.let { html ->
                    if (grade.grade != null || grade.gradedAt != null) HorizontalDivider(color = cardDividerColor())
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.assignment_detail_feedback),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        )
                        rememberMoodleMarkdown(html)?.let { MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreRow(score: Score) {
    val spoken = score.max?.let { "${score.value}, " + stringResource(R.string.course_moodle_grade_out_of, it) } ?: score.value
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // One element to TalkBack, read as "85, out of 100".
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text(
            text = score.value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        score.max?.let {
            Text(
                text = "/ $it",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

// --- Shared pieces ---

@Composable
private fun PageSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/** A label with its time on the right, in small grey type. */
@Composable
private fun TimeRow(label: String, time: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.weight(1f),
        )
        if (time != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * The button at the foot, which opens the assignment on Moodle to submit; nothing without a page
 * to open. [onOpen] is told first, so the page fetches the submission again on the way back.
 */
@Composable
internal fun AssignmentSubmitButton(entry: MoodleAssignmentEntry, onOpen: () -> Unit = {}) {
    val path = AssignmentPageRules.submitPath(entry) ?: return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = {
                onOpen()
                openMoodlePage(context, path)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.assignment_detail_submit_on_moodle))
        }
    }
}

/** Home's badge tints for a hand-in and a missed deadline; see `AssignmentItem`. */
private val BadgeGreen = Color(0xFF34C759)
private val BadgeRed = Color(0xFFFF3B30)
