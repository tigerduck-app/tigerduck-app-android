package org.ntust.app.tigerduck.ui.screen.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.files.ViewerKind

/**
 * A downloaded file, shown by its kind's viewer under a bar with Share or Save and Open in
 * another app. A file its viewer can't read says so, and why when the viewer knows, then the
 * error it failed on, with Open in another app beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileViewerScreen(
    file: ViewedFile,
    browserPreference: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onOpenElsewhere: () -> Unit,
) {
    var failed by rememberSaveable(file.uri) { mutableStateOf(false) }
    var reason by rememberSaveable(file.uri) { mutableStateOf<Int?>(null) }
    // Described as it happens, with the file's name and link to hide, and kept as text, which a
    // recreated screen can have back.
    var details by rememberSaveable(file.uri) { mutableStateOf<String?>(null) }
    var unsupportedEncoding by rememberSaveable(file.uri) { mutableStateOf(false) }
    val privateParts = remember(file) { ViewerErrors.privateParts(file.name, file.uri.toString(), file.uri.path) }
    fun failWith(failure: ViewerFailure) {
        val shown = ViewerErrors.shown(failure, privateParts)
        details = shown.details
        unsupportedEncoding = shown.unsupportedEncoding
        failed = true
    }
    val fail: (ViewerFailure) -> Unit = ::failWith
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.school_mail_attachment_share))
                    }
                    IconButton(onClick = onOpenElsewhere) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.file_viewer_open_elsewhere),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (failed) {
                CannotShow(reason, details?.let { ShownFailure(it, unsupportedEncoding) }, onOpenElsewhere)
            } else {
                when (file.kind) {
                    ViewerKind.PDF -> PdfViewer(file.uri, onFailed = fail)
                    ViewerKind.IMAGE -> ImageViewer(file.uri, onFailed = fail)
                    ViewerKind.VIDEO -> MediaViewer(file.uri, audio = false, onFailed = fail)
                    ViewerKind.AUDIO -> MediaViewer(file.uri, audio = true, onFailed = fail)
                    ViewerKind.TEXT -> TextViewer(file.uri, markdown = false, browserPreference, onFailed = fail)
                    ViewerKind.MARKDOWN -> TextViewer(file.uri, markdown = true, browserPreference, onFailed = fail)
                    ViewerKind.DOCX, ViewerKind.PPTX -> OfficeViewer(file.uri, file.kind, onFailed = fail)
                    ViewerKind.ARCHIVE -> ArchiveViewer(file, onFailed = { why, failure -> reason = why; failWith(failure) }, onOpenElsewhere)
                }
            }
        }
    }
}

/** The page while a viewer reads its file. */
@Composable
internal fun Reading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * A file its viewer couldn't read: saying so, and why when [reason] says or the device can't
 * decode it, then the error itself, raw ([ViewerErrors]), so a screenshot of it says why; and
 * offering another app.
 */
@Composable
internal fun CannotShow(reason: Int?, shown: ShownFailure?, onOpenElsewhere: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.file_viewer_cannot_show),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (reason != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (shown?.unsupportedEncoding == true) {
            Spacer(Modifier.height(8.dp))
            // TODO(#544): translate this, or decode the file ourselves
            Text(
                "This device can't play this video's format.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (shown != null) {
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                // A screenful on screen too, wrapped lines counted, so Open in another app stays near.
                Text(
                    shown.details,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = ViewerErrors.MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenElsewhere) { Text(stringResource(R.string.file_viewer_open_elsewhere)) }
    }
}
