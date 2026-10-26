package org.ntust.app.tigerduck.ui.screen.coursemoodle

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.data.moodle.MoodleFileOwner
import org.ntust.app.tigerduck.data.moodle.MoodleFileRef
import org.ntust.app.tigerduck.data.moodle.MoodleLookup
import org.ntust.app.tigerduck.data.moodle.MoodlePersonName
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * One announcement, laid out as a school bulletin is: author and time, title, the message, its
 * attachments, and a button to open it in Moodle. Its content came with the list, so it has
 * nothing to load. Opening it tells Moodle once that it was read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleAnnouncementScreen(
    discussionId: Int,
    browserPreference: String,
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val news = store?.newsForum?.collectAsStateWithLifecycle()?.value
    val pages = remember(store, news?.id) { news?.let { store?.discussions(it) } }
    val list = pages?.list?.collectAsStateWithLifecycle()?.value
    val discussion = list?.discussions?.firstOrNull { it.id == discussionId }

    // Opened with nothing cached to show it from, as after the system ended the app: fetched, or
    // its failure shown with Retry; one that is gone closes the page.
    var lookup by remember { mutableStateOf<MoodleLookup?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(store, attempt) {
        val current = store ?: return@LaunchedEffect
        lookup = null
        lookup = current.ensureAnnouncement(discussionId)
    }
    if (lookup == MoodleLookup.Missing) LaunchedEffect(Unit) { onBack() }

    // Coming back from a screen pushed over this one is not another opening.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(discussion != null) {
        val current = store
        if (discussion != null && current != null && !opened) {
            opened = true
            current.open(discussion, news)
        }
    }

    Scaffold(
        topBar = { MoodleTopBar(title = "", onBack = onBack, moodlePath = null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openMoodlePage(context, MoodlePage.discussion(discussionId)) },
                icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                text = { Text(stringResource(R.string.action_open_in_moodle)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val current = store
            if (discussion == null || current == null) {
                MoodleFirstLoad(failure = (lookup as? MoodleLookup.Failed)?.error, onRetry = { attempt++ })
            } else {
                AnnouncementBody(current, discussion, browserPreference, onOpenRoute)
            }
        }
    }
}

@Composable
private fun AnnouncementBody(
    store: MoodleCourseStore,
    discussion: MoodleDiscussion,
    browserPreference: String,
    onOpenRoute: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AuthorRow(store, discussion, onOpenRoute)
        Text(
            text = discussion.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rememberMoodleMarkdown(discussion.message)?.let { MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth()) }
        if (discussion.attachments.isNotEmpty()) {
            Attachments(store, discussion, browserPreference)
        }
    }
}

/** The author opens their member page where the course's members have them. */
@Composable
private fun AuthorRow(store: MoodleCourseStore, discussion: MoodleDiscussion, onOpenRoute: (String) -> Unit) {
    val members by store.members.state.collectAsStateWithLifecycle()
    val pictures by store.pictures.collectAsStateWithLifecycle()
    val member = remember(members, discussion.authorId) { store.member(discussion.authorId) }
    LaunchedEffect(member?.pictureUrl) { member?.let { store.loadPicture(it) } }
    val name = discussion.authorName?.let(MoodlePersonName::displayName).orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (name.isNotEmpty()) {
            MoodleAvatar(name, member?.pictureUrl?.let { pictures[it] }, 28.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (member != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (member != null) {
                        Modifier.clickable(role = Role.Button) {
                            onOpenRoute(MoodleRoutes.member(store.courseId, member.id))
                        }
                    } else {
                        Modifier
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = MoodleDates.dateTime(discussion.created),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 1,
        )
    }
}

/**
 * The files a post carries, under a heading: a tap downloads one into the app with a progress
 * ring, tapping again stops it, and the copy opens in another app; a file off the Moodle site
 * opens as its own link. A long press offers Share and "Open in Moodle".
 */
@Composable
private fun Attachments(store: MoodleCourseStore, discussion: MoodleDiscussion, browserPreference: String) {
    val context = LocalContext.current
    val files = rememberMoodleFileOpener(store, browserPreference)
    val snapshot = store.downloads?.snapshot?.collectAsStateWithLifecycle()?.value
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.school_mail_attachments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                discussion.attachments.forEachIndexed { index, file ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = MoodleRowTextInset),
                            color = cardDividerColor(),
                        )
                    }
                    val ref = MoodleFileRef(file, MoodleFileOwner.Post(discussion.postId))
                    val state = snapshot?.let { store.downloadState(ref, it) } ?: MoodleDownloadState.NotDownloaded
                    MoodleFileRow(
                        title = file.name,
                        file = file,
                        state = state,
                        onTap = { files.tap(ref, state) },
                        onShare = { files.share(ref) },
                        onOpenInMoodle = {
                            files.forgetPendingOpen()
                            openMoodlePage(context, MoodlePage.discussion(discussion.id))
                        },
                    )
                }
            }
        }
    }
}
