package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleLookup
import org.ntust.app.tigerduck.data.moodle.MoodleMemberPlacement
import org.ntust.app.tigerduck.data.moodle.MoodleMembers
import org.ntust.app.tigerduck.data.moodle.departmentKey
import org.ntust.app.tigerduck.data.moodle.name
import org.ntust.app.tigerduck.data.moodle.studentId
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * One member of a course: their profile, "Open in Moodle" for their page in the course, and
 * their email opening a new School Mail message where the account has School Mail, else the
 * system's mail app. A long press on the address copies it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleMemberScreen(
    memberId: Int,
    onBack: () -> Unit,
    onComposeSchoolMail: (address: String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val store by viewModel.store.collectAsStateWithLifecycle()
    val schoolMail by viewModel.schoolMailSignedIn.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val current = store
    val members = current?.members?.state?.collectAsStateWithLifecycle()?.value?.value
    val pictures = current?.pictures?.collectAsStateWithLifecycle()?.value.orEmpty()
    val member = members?.members?.firstOrNull { it.id == memberId }
    LaunchedEffect(member?.pictureUrl) { if (member != null) current.loadPicture(member) }
    // Opened with no members cached, as after the system ended the app: fetched, or its failure
    // shown with Retry; a member no longer in the course closes the page.
    var lookup by remember { mutableStateOf<MoodleLookup?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(current, attempt) {
        val store = current ?: return@LaunchedEffect
        lookup = null
        lookup = store.ensureMember(memberId)
    }
    if (lookup == MoodleLookup.Missing) LaunchedEffect(Unit) { onBack() }

    Scaffold(
        topBar = {
            MoodleTopBar(
                title = "",
                onBack = onBack,
                moodlePath = current?.let { MoodlePage.profile(memberId, it.courseId) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (member == null) {
                MoodleFirstLoad(failure = (lookup as? MoodleLookup.Failed)?.error, onRetry = { attempt++ })
            } else {
                MemberProfile(
                    member = member,
                    picture = member.pictureUrl?.let { pictures[it] },
                    onWrite = { address ->
                        if (schoolMail) onComposeSchoolMail(address) else writeWithMailApp(context, address)
                    },
                )
            }
        }
    }
}

private fun writeWithMailApp(context: Context, address: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, "mailto:$address".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** One row of the profile card: a field shows only when Moodle gives it. */
internal data class MemberRow(val label: String, val value: String, val isEmail: Boolean = false)

@Composable
internal fun memberRows(member: MoodleMember): List<MemberRow> {
    val studentIdLabel = stringResource(R.string.sign_in_student_id)
    val departmentLabel = stringResource(R.string.course_member_department)
    val emailLabel = stringResource(R.string.course_member_email)
    val groupsLabel = stringResource(R.string.course_member_groups)
    val locale = LocalConfiguration.current.locales[0]
    // The department by its official name, from the student ID; Moodle's own field is not shown (#697).
    val department = member.departmentKey?.let { departmentNameRes(it, short = false) }?.let { stringResource(it) }
    return buildList {
        member.studentId?.let { add(MemberRow(studentIdLabel, it)) }
        department?.let { add(MemberRow(departmentLabel, it)) }
        member.email?.let { add(MemberRow(emailLabel, it, isEmail = true)) }
        if (member.groups.isNotEmpty()) add(MemberRow(groupsLabel, MoodleMembers.groupsText(member.groups, locale)))
        member.fields.forEach { add(MemberRow(it.name, it.value)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberProfile(
    member: MoodleMember,
    picture: androidx.compose.ui.graphics.ImageBitmap?,
    onWrite: (String) -> Unit,
) {
    val rows = memberRows(member)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MoodleAvatar(member.name, picture, 88.dp)
            Spacer(Modifier.width(4.dp))
            SelectionContainer {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = placementTitle(MoodleMemberPlacement.of(member)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
        if (rows.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { row -> ProfileRow(row, onWrite) }
                }
            }
        }
        if (member.description.isNotEmpty()) {
            SelectionContainer(Modifier.fillMaxWidth()) {
                Text(
                    text = member.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (member.interests.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                member.interests.forEach { interest ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(
                            text = "#$interest",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileRow(row: MemberRow, onWrite: (String) -> Unit) {
    val context = LocalContext.current
    // The system's own "Copy", localized by the platform.
    val copyLabel = stringResource(android.R.string.copy)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        Spacer(Modifier.width(16.dp))
        if (row.isEmail) {
            // Kept on one line and cut in the middle: wrapped, an address breaks where it has no hyphen.
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = copyLabel,
                        onLongClick = { copyAddress(context, row.value) },
                        onClick = { onWrite(row.value) },
                    ),
            )
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Android 13 and later confirm a copy themselves; earlier versions copy without a word. */
private fun copyAddress(context: Context, address: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("email", address))
}
