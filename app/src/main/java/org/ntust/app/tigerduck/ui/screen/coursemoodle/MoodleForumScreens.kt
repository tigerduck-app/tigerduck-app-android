// A course's forums on Moodle, read-only: the list of its general forums, a forum's discussions,
// and a discussion's thread of posts. Ported from iOS MoodleForumsView and MoodleDiscussionView.

package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.data.moodle.MoodleFileOwner
import org.ntust.app.tigerduck.data.moodle.MoodleFileRef
import org.ntust.app.tigerduck.data.moodle.MoodleLookup
import org.ntust.app.tigerduck.data.moodle.MoodlePersonName
import org.ntust.app.tigerduck.data.moodle.MoodleThreadEntry
import org.ntust.app.tigerduck.data.moodle.MoodleThreads
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.moodle.MoodleHtmlText
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * A course's general forums in one card, each with its intro, its number of discussions and an
 * unread dot. The bar's "Open in Moodle" opens the course's forum index.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleForumsScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    Scaffold(
        topBar = {
            MoodleTopBar(
                title = stringResource(R.string.course_detail_forums_label),
                onBack = onBack,
                moodlePath = store?.let { MoodlePage.forums(it.courseId) },
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
            if (current == null) {
                MoodleFirstLoad(failure = null, onRetry = {})
            } else {
                ForumsList(current) { onOpenRoute(MoodleRoutes.forum(current.courseId, it.id)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForumsList(store: MoodleCourseStore, onOpen: (MoodleForum) -> Unit) {
    val state by store.forums.state.collectAsStateWithLifecycle()
    val forums by store.generalForums.collectAsStateWithLifecycle()
    MoodleAutoRefresh(store, store.forums.ttlMs, store::awaitCache, { store.forumsFetchedAtMs }) { store.refreshForums() }
    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { store.refreshForums() }
    val scope = rememberCoroutineScope()
    val covered = state.value?.courseIds?.contains(store.courseId) == true
    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!covered) {
                item {
                    MoodleFirstLoad(
                        failure = if (state.isRefreshing) null else state.failure,
                        onRetry = { scope.launch { store.refreshForums() } },
                    )
                }
            } else {
                state.failureBesideCopy?.let { failure ->
                    item(key = "failure") { MoodleRefreshFailure(failure, onRetry = { scope.launch { store.refreshForums() } }) }
                }
                if (forums.isNotEmpty()) {
                    item(key = "forums") {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                forums.forEachIndexed { index, forum ->
                                    if (index > 0) {
                                        HorizontalDivider(Modifier.padding(start = 56.dp), color = cardDividerColor())
                                    }
                                    MoodleCountRow(
                                        icon = Icons.AutoMirrored.Outlined.Chat,
                                        title = forum.name,
                                        count = forum.discussionCount,
                                        hasUnread = forum.unreadPostCount > 0,
                                        onClick = { onOpen(forum) },
                                        subtitle = forum.intro,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One forum's discussions: pinned first, then by their latest post, each with its author and
 * number of replies, a page of twenty at a time. Opened with nothing cached, as after the system
 * ended the app, the course's forums are fetched first; a forum that is gone closes the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleForumScreen(
    forumId: Int,
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val forums = store?.forums?.state?.collectAsStateWithLifecycle()?.value
    val forum = remember(store, forums) { store?.forum(forumId) }
    var lookup by remember { mutableStateOf<MoodleLookup?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(store, attempt) {
        val current = store ?: return@LaunchedEffect
        lookup = null
        lookup = current.ensureForum(forumId)
    }
    if (lookup == MoodleLookup.Missing) LaunchedEffect(Unit) { onBack() }
    Scaffold(
        topBar = { MoodleTopBar(forum?.name.orEmpty(), onBack, forum?.let { MoodlePage.forum(it.cmid) }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val current = store
            if (current == null || forum == null) {
                MoodleFirstLoad(failure = (lookup as? MoodleLookup.Failed)?.error, onRetry = { attempt++ })
            } else {
                DiscussionList(
                    pages = remember(current, forum.id) { current.discussions(forum) },
                    onOpen = { onOpenRoute(MoodleRoutes.discussion(current.courseId, forum.id, it.id)) },
                    showsFooter = true,
                )
            }
        }
    }
}

/**
 * A discussion's thread: every post as a card, replies indented under what they answer, three
 * levels at most, with guide lines. The first post shows at once from the forum's list, a spinner
 * while the replies load, and their error with Retry if they fail. Every opening fetches the
 * posts, and Moodle is told the discussion was read only once they show; the posts unread at
 * the opening keep their dot while the page is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleDiscussionScreen(
    forumId: Int,
    discussionId: Int,
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val forums = store?.forums?.state?.collectAsStateWithLifecycle()?.value
    val forum = remember(store, forums) { store?.forum(forumId) }
    val pages = remember(store, forum?.id) { forum?.let { store?.discussions(it) } }
    val list = pages?.list?.collectAsStateWithLifecycle()?.value
    val discussion = list?.discussions?.firstOrNull { it.id == discussionId }

    var lookup by remember { mutableStateOf<MoodleLookup?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(store, attempt) {
        val current = store ?: return@LaunchedEffect
        lookup = null
        lookup = current.ensureDiscussion(discussionId, forumId)
    }
    if (lookup == MoodleLookup.Missing) LaunchedEffect(Unit) { onBack() }

    val files = store?.let { rememberMoodleFileOpener(it, browserPreference) }
    Scaffold(
        topBar = { MoodleTopBar(title = "", onBack = onBack, moodlePath = null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    files?.forgetPendingOpen()
                    openMoodlePage(context, MoodlePage.discussion(discussionId))
                },
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
            if (current == null || forum == null || discussion == null || files == null) {
                MoodleFirstLoad(failure = (lookup as? MoodleLookup.Failed)?.error, onRetry = { attempt++ })
            } else {
                Thread(current, forum, discussion, files, browserPreference)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Thread(
    store: MoodleCourseStore,
    forum: MoodleForum,
    discussion: MoodleDiscussion,
    files: MoodleFileOpener,
    browserPreference: String,
) {
    val thread = remember(store, discussion.id) { store.thread(discussion.id) }
    val state by thread.state.collectAsStateWithLifecycle()
    val snapshot = store.downloads?.snapshot?.collectAsStateWithLifecycle()?.value
    // The posts unread when an opening loaded them; null until one has. A page coming back from
    // another, or rebuilt, is not another opening.
    var unreadAtOpening by rememberSaveable(discussion.id) { mutableStateOf<List<Int>?>(null) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Until an opening has loaded the posts, a refresh is that opening, so Moodle hears of the
    // view once they show. One opening runs at a time; a refresh meanwhile joins its fetch.
    suspend fun refresh() {
        if (unreadAtOpening != null || opening) {
            store.refreshThread(discussion.id)
            return
        }
        opening = true
        try {
            unreadAtOpening = store.openThread(discussion, forum)?.let { ArrayList(it) }
        } finally {
            opening = false
        }
    }

    LaunchedEffect(store, discussion.id) {
        if (unreadAtOpening == null) refresh()
    }
    MoodleAutoRefresh(thread, thread.ttlMs, { thread.awaitCache() }, { thread.state.value.fetchedAtMs }) { refresh() }

    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { refresh() }
    val entries = remember(discussion, state.value) { MoodleThreads.entries(discussion, state.value) }
    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
        ) {
            items(entries, key = { it.post.id }) { entry ->
                MoodlePostCard(
                    entry = entry,
                    isUnread = unreadAtOpening?.contains(entry.post.id) ?: entry.post.isUnread,
                    store = store,
                    discussion = discussion,
                    snapshot = snapshot,
                    files = files,
                    browserPreference = browserPreference,
                )
            }
            if (state.value == null) {
                item(key = "replies") {
                    RepliesPlaceholder(
                        failure = state.failure.takeIf { !state.isRefreshing },
                        hasReplies = discussion.replyCount > 0,
                        onRetry = { scope.launch { refresh() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RepliesPlaceholder(failure: Throwable?, hasReplies: Boolean, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (failure != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = moodleErrorMessage(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        } else if (hasReplies) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/** How far each level of replies sits in. */
private val ReplyIndent = 16.dp

@Composable
private fun MoodlePostCard(
    entry: MoodleThreadEntry,
    isUnread: Boolean,
    store: MoodleCourseStore,
    discussion: MoodleDiscussion,
    snapshot: org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads.Snapshot?,
    files: MoodleFileOpener,
    browserPreference: String,
) {
    val context = LocalContext.current
    val post = entry.post
    val isFirst = entry.depth == 0
    val guide = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .drawBehind {
                // A line down each level the reply is indented by.
                val stroke = 1.dp.toPx()
                for (level in 0 until entry.depth) {
                    val x = (ReplyIndent * level + ReplyIndent / 2).toPx()
                    val atX = if (layoutDirection == LayoutDirection.Rtl) size.width - x else x
                    drawLine(guide, Offset(atX, 0f), Offset(atX, size.height), stroke)
                }
            },
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(start = ReplyIndent * entry.depth)
                .fillMaxWidth(),
        ) {
            Column {
                Column(
                    modifier = Modifier.padding(if (isFirst) 16.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PostHeader(store, post, isUnread)
                    if (isFirst && post.subject.isNotEmpty()) {
                        Text(
                            text = post.subject,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    entry.replyingTo?.let { name -> ReplyingTo(name) }
                    if (post.isDeleted) {
                        Text(
                            text = remember(post.message) { MoodleHtmlText.plainText(post.message) },
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        )
                    } else {
                        rememberMoodleMarkdown(post.message)?.let { MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth()) }
                    }
                }
                if (!post.isDeleted) {
                    post.attachments.forEach { file ->
                        HorizontalDivider(Modifier.padding(start = MoodleRowTextInset), color = cardDividerColor())
                        val ref = MoodleFileRef(file, MoodleFileOwner.Post(post.id))
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
}

/** Avatar and name, an unread dot, and the time it was posted. */
@Composable
private fun PostHeader(store: MoodleCourseStore, post: org.ntust.app.tigerduck.network.model.moodle.MoodlePost, isUnread: Boolean) {
    val members by store.members.state.collectAsStateWithLifecycle()
    val pictures by store.pictures.collectAsStateWithLifecycle()
    val member = remember(members, post.authorId) { store.member(post.authorId) }
    LaunchedEffect(member?.pictureUrl) { member?.let { store.loadPicture(it) } }
    val name = post.authorName?.let(MoodlePersonName::displayName)?.takeIf { it.isNotEmpty() }
    val unreadLabel = stringResource(R.string.bulletin_unread_dot_label)
    val date = post.created?.let { remember(it) { MoodleDates.postDate(it, System.currentTimeMillis()) } }
    val spoken = listOfNotNull(name, unreadLabel.takeIf { isUnread }, post.created?.let(MoodleDates::dateTime))
        .joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        if (name != null) {
            MoodleAvatar(name, member?.pictureUrl?.let { pictures[it] }, 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        // The name takes the room the date leaves, the dot right after it.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (name != null) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (isUnread) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (date != null) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Whom a reply nested deeper than the levels shown answers. */
@Composable
private fun ReplyingTo(name: String) {
    val label = "${stringResource(R.string.school_mail_reply)} $name"
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
    ) {
        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = secondary, modifier = Modifier.size(14.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
