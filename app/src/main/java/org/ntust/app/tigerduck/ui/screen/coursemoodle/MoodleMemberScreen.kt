package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser
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
    val openingPictures = current?.openingPictures?.collectAsStateWithLifecycle()?.value.orEmpty()
    val scope = rememberCoroutineScope()
    val member = members?.members?.firstOrNull { it.id == memberId }
    LaunchedEffect(member?.pictureUrl) { if (member != null) current.loadPicture(member) }
    // One's own picture can be changed where the site allows it: picked, cropped here, uploaded.
    val ownPage = member != null && isOwnPage(member, members.ownId)
    var canChangePicture by remember { mutableStateOf(false) }
    LaunchedEffect(current, ownPage) { canChangePicture = ownPage && current.canChangePicture() }
    val changingPicture = current?.changingPicture?.collectAsStateWithLifecycle()?.value == true
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val unreadable = stringResource(R.string.course_member_picture_unreadable)
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                openPickedPicture(
                    decode = { decodePickedPicture(context, uri) },
                    crop = { cropping = it },
                    unreadable = { snackbar.showSnackbar(unreadable) },
                )
            }
        }
    }
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
    // Moodle's profile editor, opened from the pencil: the members are fetched again once it
    // closes, and a failure says why, with Retry.
    val resources = LocalResources.current
    val retry = stringResource(R.string.action_retry)
    val openedEditor = rememberRefetchAfterEditor(
        refetch = current?.let { store ->
            suspend {
                fetchMembersUntilDone(
                    fetch = {
                        store.refreshMembers(joinRunning = false)
                        store.members.state.value.failure
                    },
                    retry = { failure ->
                        snackbar.showSnackbar(
                            moodleErrorText(resources, failure),
                            actionLabel = retry,
                            duration = SnackbarDuration.Long,
                        ) == SnackbarResult.ActionPerformed
                    },
                )
            }
        },
    )

    Scaffold(
        topBar = {
            MoodleTopBar(
                title = "",
                onBack = onBack,
                moodlePath = current?.let { MoodlePage.profile(memberId, it.courseId) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                MemberPage(
                    member = member,
                    picture = member.pictureUrl?.let { pictures[it] },
                    // Moodle's default picture, or none, opens nothing.
                    onOpenPicture = member.pictureUrl?.let {
                        {
                            scope.launch {
                                current.fullPicture(member)?.let { launchMoodleFile(context, it.file, it.mimeType) }
                            }
                        }
                    },
                    openingPicture = member.id in openingPictures,
                    onWrite = { address ->
                        if (schoolMail) onComposeSchoolMail(address) else writeWithMailApp(context, address)
                    },
                    // Moodle gives students no web service for the other profile fields.
                    onEditOnMoodle = if (ownPage) {
                        {
                            openedEditor()
                            editOwnProfile(context)
                        }
                    } else {
                        null
                    },
                    onChangePicture = if (canChangePicture) {
                        { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    } else {
                        null
                    },
                    changingPicture = changingPicture,
                )
            }
        }
    }
    cropping?.let { picked ->
        PictureCropDialog(
            picture = picked,
            onCancel = { cropping = null },
            onDone = { source ->
                cropping = null
                val store = current ?: return@PictureCropDialog
                scope.launch {
                    // Encoded in the account's scope with the upload, so leaving the page cannot drop it.
                    // The old picture stays on a refusal; Moodle's own error says why.
                    store.changePicture { PictureCrop.jpeg(picked, source) }
                        ?.let { snackbar.showSnackbar(moodleErrorText(context.resources, it)) }
                }
            },
        )
    }
}

/** A picked photo to [crop], once [decode] reads it, else the message that it cannot be read ([unreadable]). */
internal suspend fun openPickedPicture(
    decode: suspend () -> Bitmap?,
    crop: (Bitmap) -> Unit,
    unreadable: suspend () -> Unit,
) {
    val picture = decode()
    if (picture != null) crop(picture) else unreadable()
}

/**
 * Fetches the members again through [refetch] on the first return to the page after the returned
 * function marks the editor open, as the pencil does when it opens Moodle's profile editor: the
 * edit shows here, as on the iPhone (tigerduck-app#802, #806). No other return fetches. The mark
 * is saved, so a page the system recreated meanwhile still fetches, once [refetch] is there: null
 * until the course's store is. The store's fetch runs in its own scope, so leaving the page does
 * not stop it.
 */
@Composable
internal fun rememberRefetchAfterEditor(refetch: (suspend () -> Unit)?): () -> Unit {
    var editing by rememberSaveable { mutableStateOf(false) }
    // Returns from the editor, and the last one a fetch finished for.
    var returns by rememberSaveable { mutableIntStateOf(0) }
    var fetchedFor by rememberSaveable { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (editing) {
            editing = false
            returns++
        }
    }
    val latest by rememberUpdatedState(refetch)
    // A later return ends the wait for the earlier one's fetch, which may have read the profile
    // before the later edit, and fetches behind it.
    LaunchedEffect(returns, refetch != null) {
        val fetch = latest
        if (returns > fetchedFor && fetch != null) {
            val fetching = returns
            fetch()
            // Marked once done: a page recreated meanwhile fetches, and reports a failure, again.
            fetchedFor = fetching
        }
    }
    return remember { { editing = true } }
}

/**
 * Fetches through [fetch], which answers its failure or null, and again each time [retry] takes
 * the failure back, as the snackbar's Retry does.
 */
internal suspend fun fetchMembersUntilDone(fetch: suspend () -> Throwable?, retry: suspend (Throwable) -> Boolean) {
    while (true) {
        val failure = fetch() ?: return
        if (!retry(failure)) return
    }
}

/** Whether [member]'s page is the signed-in student's own, by [ownId], their Moodle user; none without it. */
internal fun isOwnPage(member: MoodleMember, ownId: Int?): Boolean = ownId != null && member.id == ownId

/** Moodle's profile editor for the signed-in account, on the website. */
internal fun ownProfileEditorUrl(): String = MoodlePage.webUrl(MoodlePage.PROFILE_EDITOR)

/**
 * The pencil: Moodle's profile editor in the TigerDuck browser, whatever "Open links with" says,
 * so it opens signed in and stays in the app. Moodle has no web service for these fields. Where
 * the browser cannot open (a WebView too old to clear it), [elsewhere] opens it as before.
 */
internal fun editOwnProfile(
    context: Context,
    elsewhere: (Context, String) -> Unit = { c, url -> MoodleWebPages.of(c, inApp = false).open(c, url, null) },
) {
    val url = ownProfileEditorUrl()
    if (!InAppBrowser.open(context, url)) elsewhere(context, url)
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

/**
 * A member's page below its top bar: their profile, and on the signed-in student's own page
 * ([onEditOnMoodle] set) a round pencil at the bottom right that opens Moodle's profile editor,
 * signed in. The pencil has no text, so its size does not depend on the language, as on the iPhone.
 */
@Composable
internal fun MemberPage(
    member: MoodleMember,
    picture: ImageBitmap?,
    onOpenPicture: (() -> Unit)?,
    openingPicture: Boolean,
    onWrite: (String) -> Unit,
    onEditOnMoodle: (() -> Unit)?,
    onChangePicture: (() -> Unit)? = null,
    changingPicture: Boolean = false,
) {
    Box(Modifier.fillMaxSize()) {
        // Room below the profile for the pencil, so it covers none of it at the end of the scroll.
        MemberProfile(
            member, picture, onOpenPicture, openingPicture, onWrite, onChangePicture, changingPicture,
            bottom = if (onEditOnMoodle != null) 96.dp else 32.dp,
        )
        if (onEditOnMoodle != null) {
            FloatingActionButton(
                onClick = onEditOnMoodle,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.course_member_edit_on_moodle))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberProfile(
    member: MoodleMember,
    picture: ImageBitmap?,
    onOpenPicture: (() -> Unit)?,
    openingPicture: Boolean,
    onWrite: (String) -> Unit,
    onChangePicture: (() -> Unit)?,
    changingPicture: Boolean,
    bottom: Dp,
) {
    val rows = memberRows(member)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MemberPicture(member.name, picture, onOpenPicture, openingPicture, onChangePicture, changingPicture)
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

/**
 * The picture at the top of a member's page. Their own picture is a button that opens it full
 * screen ([onOpen]; `MoodleCourseStore.fullPicture`), labelled for TalkBack, with a spinner while
 * it gets ready ([opening]), which takes no second tap; with [onOpen] null, as for Moodle's
 * default picture, it is a plain picture. As the iPhone's (tigerduck-app/tigerduck-app#741).
 *
 * On the signed-in student's own page, where the site allows it, a camera badge ([onChange])
 * picks a new picture (#446, as the iPhone's tigerduck-app#802); while it goes up ([changing])
 * the picture spins and neither takes a tap.
 */
@Composable
internal fun MemberPicture(
    name: String,
    picture: ImageBitmap?,
    onOpen: (() -> Unit)?,
    opening: Boolean,
    onChange: (() -> Unit)? = null,
    changing: Boolean = false,
) {
    val label = stringResource(R.string.course_member_profile_picture)
    val changeLabel = stringResource(R.string.course_member_change_picture)
    val button = if (onOpen == null) {
        Modifier
    } else {
        Modifier
            .clip(CircleShape)
            .clickable(enabled = !opening && !changing, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = label }
    }
    Box {
        Box(button, contentAlignment = Alignment.Center) {
            MoodleAvatar(name, picture, 88.dp)
            if (opening || changing) CircularProgressIndicator(Modifier.size(32.dp))
        }
        if (onChange != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !changing, role = Role.Button, onClick = onChange)
                    .semantics { contentDescription = changeLabel },
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
    }
}
