// The rows a Moodle file, link or folder shows as, in a course's materials and under a post, and
// what a tap on one does: download with a progress ring, open the copy, or stop the download.

package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleAttachment
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.data.moodle.MoodleFileKind
import org.ntust.app.tigerduck.data.moodle.MoodleFileRef
import org.ntust.app.tigerduck.data.moodle.MoodleMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.io.File
import java.text.NumberFormat

/**
 * What a screen of Moodle files does with them. A file that finishes downloading opens only
 * while the screen is on top and only for the latest action, so several taps in a row do not
 * open one file after another, and a Share does not also open the file. Ported from iOS
 * `MoodleFileOpener`.
 */
@Stable
class MoodleFileOpener internal constructor(
    private val store: MoodleCourseStore,
    private val context: Context,
    private val scope: CoroutineScope,
    private val browserPreference: () -> String,
    /** Hands a copy to another app, or with share to the share sheet; tests stand in for it. */
    private val present: (copy: File, file: MoodleFile, share: Boolean) -> Unit = { copy, file, share ->
        // The system's chooser tells the reader itself when no app takes the file.
        launchMoodleFile(context, copy, moodleFileType(file.mimeType, file.name), share = share)
    },
) {
    internal var isOnScreen = false

    /** Moves on with every tap, share and link, so a download's end acts only on the latest. */
    private var latestAction = 0

    /**
     * A tap on [ref]'s row: stops its download, opens its copy, or downloads it and then opens it.
     * [opened] runs each time it opens, for its view on Moodle.
     */
    fun tap(ref: MoodleFileRef, state: MoodleDownloadState, opened: () -> Unit = {}) {
        val action = ++latestAction
        when (state) {
            is MoodleDownloadState.Downloading -> store.cancelDownload(ref)
            is MoodleDownloadState.Downloaded -> {
                present(state.file, ref.file, false)
                opened()
            }
            else -> scope.launch {
                val result = store.download(ref)
                if (!isLatest(action)) return@launch
                when (result) {
                    is MoodleAttachment.Local -> {
                        present(result.file, ref.file, false)
                        opened()
                    }
                    is MoodleAttachment.Link -> {
                        openMoodleContentLink(context, result.url, browserPreference())
                        opened()
                    }
                    is MoodleAttachment.Failed -> toast(result.error)
                    MoodleAttachment.Dropped -> Unit
                }
            }
        }
    }

    /** [ref] in the share sheet, downloaded first when there is no copy: the copy, never a link. */
    fun share(ref: MoodleFileRef) {
        val action = ++latestAction
        scope.launch {
            when (val result = store.download(ref)) {
                is MoodleAttachment.Local -> if (isLatest(action)) present(result.file, ref.file, true)
                is MoodleAttachment.Link -> if (isLatest(action)) shareMoodleLink(context, ref.file.name, result.url)
                is MoodleAttachment.Failed -> if (isLatest(action)) toast(result.error)
                MoodleAttachment.Dropped -> Unit
            }
        }
    }

    /** For a link or a Moodle page about to open: a file still downloading then no longer opens over it. */
    fun forgetPendingOpen() {
        latestAction++
    }

    private fun isLatest(action: Int) = isOnScreen && latestAction == action

    private fun toast(error: Throwable) {
        Toast.makeText(context, moodleErrorText(context.resources, error), Toast.LENGTH_SHORT).show()
    }
}

/** A [MoodleFileOpener] for [store], on screen while the screen is resumed. */
@Composable
internal fun rememberMoodleFileOpener(store: MoodleCourseStore, browserPreference: String): MoodleFileOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestPreference by rememberUpdatedState(browserPreference)
    val opener = remember(store, context, scope) { MoodleFileOpener(store, context, scope, browserPreference = { latestPreference }) }
    LifecycleResumeEffect(opener) {
        opener.isOnScreen = true
        onPauseOrDispose { opener.isOnScreen = false }
    }
    return opener
}

/** The leading inset of a row's text, which the line between rows starts under. */
internal val MoodleRowTextInset: Dp = 16.dp + 28.dp + 16.dp

/** How far a folder's files sit in under it. */
internal val MoodleFolderIndent: Dp = 28.dp

/**
 * A file: an icon and colour by what it holds, its title, "date · size", and a mark for its
 * download, which a downloaded copy shows none of. A long press offers Share and "Open in Moodle".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MoodleFileRow(
    title: String,
    file: MoodleFile,
    state: MoodleDownloadState,
    onTap: () -> Unit,
    onShare: () -> Unit,
    onOpenInMoodle: () -> Unit,
    indent: Dp = 0.dp,
) {
    val context = LocalContext.current
    val kind = MoodleFileKind.of(file.mimeType, file.name)
    val subline = remember(file) { fileSubline(context, file) }
    val cancel = stringResource(R.string.action_cancel)
    val shareLabel = stringResource(R.string.school_mail_attachment_share)
    val openLabel = stringResource(R.string.action_open_in_moodle)
    val percent = (state as? MoodleDownloadState.Downloading)?.let {
        NumberFormat.getPercentInstance().format(it.progress.toDouble())
    }
    val failure = (state as? MoodleDownloadState.Failed)?.let { moodleErrorMessage(it.error) }
    var menu by remember { mutableStateOf(false) }
    Box {
        MoodleRowLayout(
            indent = indent,
            modifier = Modifier
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = { menu = true },
                    onClick = onTap,
                )
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = title
                    stateDescription = listOfNotNull(subline, percent, failure).joinToString(", ")
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(cancel) { onTap(); true }
                            .takeIf { state is MoodleDownloadState.Downloading },
                        CustomAccessibilityAction(shareLabel) { onShare(); true },
                        CustomAccessibilityAction(openLabel) { onOpenInMoodle(); true },
                    )
                },
            icon = { Icon(kind.icon(), contentDescription = null, tint = kind.color(), modifier = Modifier.size(24.dp)) },
            trailing = { DownloadMark(state) },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
        MoodleRowMenu(expanded = menu, onDismiss = { menu = false }, onShare = onShare, onOpenInMoodle = onOpenInMoodle)
    }
}

/** A web link: a teal link icon, its title, its site and an outward arrow. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MoodleLinkRow(
    title: String,
    url: String,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onOpenInMoodle: () -> Unit,
) {
    val site = remember(url) { MoodleMaterials.site(url) }
    val shareLabel = stringResource(R.string.school_mail_attachment_share)
    val openLabel = stringResource(R.string.action_open_in_moodle)
    var menu by remember { mutableStateOf(false) }
    Box {
        MoodleRowLayout(
            modifier = Modifier
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = { menu = true },
                    onClick = onOpen,
                )
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = title
                    stateDescription = site
                    customActions = listOf(
                        CustomAccessibilityAction(shareLabel) { onShare(); true },
                        CustomAccessibilityAction(openLabel) { onOpenInMoodle(); true },
                    )
                },
            icon = { Icon(Icons.Outlined.Link, contentDescription = null, tint = MoodleTeal, modifier = Modifier.size(24.dp)) },
            trailing = {
                Icon(
                    Icons.Filled.NorthEast,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.size(16.dp),
                )
            },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                site,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MoodleRowMenu(expanded = menu, onDismiss = { menu = false }, onShare = onShare, onOpenInMoodle = onOpenInMoodle)
    }
}

/** Not a button: a folder only heads the files listed under it. */
@Composable
internal fun MoodleFolderRow(name: String, fileCount: Int) {
    val count = remember(fileCount) { NumberFormat.getIntegerInstance().format(fileCount) }
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
    MoodleRowLayout(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = name
            stateDescription = count
        },
        icon = { Icon(Icons.Filled.Folder, contentDescription = null, tint = secondary, modifier = Modifier.size(24.dp)) },
        trailing = { Text(count, style = MaterialTheme.typography.bodySmall, color = secondary) },
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The trailing mark keeps its width however the text wraps, so the marks line up down a card. */
@Composable
private fun MoodleRowLayout(
    modifier: Modifier = Modifier,
    indent: Dp = 0.dp,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp + indent, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { text() }
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}

/** A downloaded copy shows no mark, as in the Files app. */
@Composable
private fun DownloadMark(state: MoodleDownloadState) {
    when (state) {
        MoodleDownloadState.NotDownloaded -> Icon(
            Icons.Outlined.ArrowCircleDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.size(24.dp),
        )
        is MoodleDownloadState.Downloading -> Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            )
            Icon(
                Icons.Filled.Stop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp),
            )
        }
        is MoodleDownloadState.Downloaded -> Unit
        is MoodleDownloadState.Failed -> Icon(
            Icons.Filled.Error,
            contentDescription = null,
            tint = MoodleOrange,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun MoodleRowMenu(expanded: Boolean, onDismiss: () -> Unit, onShare: () -> Unit, onOpenInMoodle: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.school_mail_attachment_share)) },
            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
            onClick = {
                onDismiss()
                onShare()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open_in_moodle)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenInMoodle()
            },
        )
    }
}

/** "Date · size", the date with its year when it is not this year's. */
private fun fileSubline(context: Context, file: MoodleFile): String {
    val size = Formatter.formatShortFileSize(context, file.size)
    val modified = file.modified ?: return size
    return "${MoodleDates.date(modified, System.currentTimeMillis())} · $size"
}

/** The link icon's teal, as iOS draws it. */
internal val MoodleTeal = Color(0xFF30B0C7)

private fun MoodleFileKind.icon(): ImageVector = when (this) {
    MoodleFileKind.PDF -> Icons.Outlined.PictureAsPdf
    MoodleFileKind.DOCUMENT -> Icons.Outlined.Description
    MoodleFileKind.PRESENTATION -> Icons.Outlined.Slideshow
    MoodleFileKind.SPREADSHEET -> Icons.Outlined.TableChart
    MoodleFileKind.IMAGE -> Icons.Outlined.Image
    MoodleFileKind.VIDEO -> Icons.Outlined.Movie
    MoodleFileKind.AUDIO -> Icons.Outlined.AudioFile
    MoodleFileKind.ARCHIVE -> Icons.Outlined.FolderZip
    MoodleFileKind.TEXT -> Icons.AutoMirrored.Outlined.Article
    MoodleFileKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** The colours of iOS's file icons, readable on a light and a dark card alike. */
@Composable
private fun MoodleFileKind.color(): Color = when (this) {
    MoodleFileKind.PDF -> Color(0xFFE5483D)
    MoodleFileKind.DOCUMENT -> Color(0xFF2F7CF6)
    MoodleFileKind.PRESENTATION -> MoodleOrange
    MoodleFileKind.SPREADSHEET -> Color(0xFF30A84E)
    MoodleFileKind.IMAGE -> Color(0xFFA25BD8)
    MoodleFileKind.VIDEO, MoodleFileKind.AUDIO -> Color(0xFFF0457A)
    MoodleFileKind.ARCHIVE, MoodleFileKind.TEXT, MoodleFileKind.OTHER ->
        MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
}
