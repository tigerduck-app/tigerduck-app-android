package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.Prompt
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import java.text.NumberFormat
import java.time.Instant
import java.util.Date

/**
 * Presents the import [viewModel] holds, over whichever page is showing: the iOS scanner page
 * mapped to Material 3 (the camera, and the flashlight, Choose from photos and Enter link below
 * it), the confirm dialog, and the prompts. The page stays up behind them, so a refused code can
 * be followed by another; it closes once the person is saved, which [onImported] hears, or when
 * the user closes it, or for the user's own share sheet ([onShare], null with nothing to share).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTableImportHost(
    viewModel: ClassTableImportViewModel,
    onImported: () -> Unit,
    onShare: (() -> Unit)? = null,
    refreshShare: () -> Unit = {},
    shareSource: Any? = null,
) {
    val model = viewModel.session.collectAsStateWithLifecycle().value ?: return
    val latestOnImported by rememberUpdatedState(onImported)
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.readPicture(uri)
    }
    ClassTableImportPage(
        model = model,
        onClose = viewModel::end,
        onFinished = {
            viewModel.end()
            // Not for one a link left while it was being written: the link's page stays on screen.
            if (!model.state.value.leftForLink) latestOnImported()
        },
        onPickPicture = {
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onShare = onShare,
        refreshShare = refreshShare,
        shareSource = shareSource,
    )
}

/**
 * [model]'s import page, confirm dialog and prompts. [onClose] is the user leaving, which nothing
 * offers while the person is being saved; [onFinished] is the import done, saved or called off by
 * a sign-in change. [onShare], null when the user has no class table to share, closes the page
 * for the user's own share sheet, as the iOS scanner's QR button does; [refreshShare] reads what
 * there is to share as a scanner opens, and again whenever [shareSource], what it is read from,
 * changes. [camera] is the scanner's, which tests replace.
 */
@Composable
internal fun ClassTableImportPage(
    model: ClassTableImportModel,
    onClose: () -> Unit,
    onFinished: () -> Unit,
    onPickPicture: () -> Unit,
    onShare: (() -> Unit)? = null,
    refreshShare: () -> Unit = {},
    shareSource: Any? = null,
    camera: @Composable (onScanned: (String) -> ClassTableImportModel.ScanAnswer, torch: ScannerTorch, modifier: Modifier) -> Unit =
        { onScanned, torch, modifier -> QrScannerCamera(onScanned = onScanned, torch = torch, modifier = modifier) },
) {
    // Keyed by the import: a link's import takes the place of the one on screen, and read for a
    // frame through the old one's state, called off, the new one would be closed with it.
    key(model) { ImportPage(model, onClose, onFinished, onPickPicture, onShare, refreshShare, shareSource, camera) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportPage(
    model: ClassTableImportModel,
    onClose: () -> Unit,
    onFinished: () -> Unit,
    onPickPicture: () -> Unit,
    onShare: (() -> Unit)?,
    refreshShare: () -> Unit,
    shareSource: Any?,
    camera: @Composable (onScanned: (String) -> ClassTableImportModel.ScanAnswer, torch: ScannerTorch, modifier: Modifier) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    // The terms the share button offers are read as the scanner opens, and again as what they are
    // read from changes, as when the class table's first load ends: the class table reads them
    // only as its ⋯ menu opens, and the tab's menu can open the scanner before that.
    LaunchedEffect(shareSource) { if (!model.state.value.fromLink) refreshShare() }
    var enteringLink by rememberSaveable { mutableStateOf(false) }
    val torch = remember { ScannerTorch() }
    val latestOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) latestOnFinished()
    }
    // Not while the person is being written: the import then ends saved, and closes itself.
    val close: () -> Unit = { if (!model.state.value.isSaving) onClose() }
    val latestOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(state.isLinkDone) {
        if (state.isLinkDone) latestOnClose()
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !state.isSaving,
        ),
    ) {
        // Dark whatever the app's theme, as on iOS: the page is the camera's picture. Under the
        // wallpaper's colors it takes their dark tones, which the nested theme picks for itself.
        TigerDuckAppTheme(
            darkTheme = true,
            accentColor = MaterialTheme.colorScheme.primary,
            onAccentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Scaffold(
                containerColor = Color.Black,
                contentColor = Color.White,
                topBar = {
                    // As the iOS scanner: the way to the user's own share sheet at the start,
                    // mirroring the share sheet's way here, and Close at the end, as its Done.
                    TopAppBar(
                        title = { Text(stringResource(R.string.class_table_import)) },
                        navigationIcon = {
                            // A link's page came for its code, not to scan, as on iOS.
                            if (onShare != null && !state.fromLink) {
                                IconButton(onClick = onShare, enabled = !state.isSaving) {
                                    Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.class_table_share))
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = close, enabled = !state.isSaving) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                    )
                },
                bottomBar = {
                    // A link's page has nothing to pick: the code came with it.
                    if (!state.fromLink) {
                        ScannerSources(
                            torch = torch,
                            enabled = state.isIdle,
                            onPickPicture = onPickPicture,
                            onEnterLink = { enteringLink = true },
                        )
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    // Not for a link: no one came to scan, so the camera is not asked for.
                    if (!state.fromLink) {
                        // A code the camera finds while a link is typed would open its screen
                        // under the dialog, as on iOS.
                        camera(
                            { text -> if (enteringLink) ClassTableImportModel.ScanAnswer.BUSY else model.scan(text) },
                            torch,
                            Modifier.fillMaxSize(),
                        )
                    }
                    // An update has no confirm dialog to wait in, so it waits here, as a picture does.
                    if (state.isReadingImage || (state.isLookingUp && state.pending == null)) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (enteringLink) {
        EnterLinkDialog(
            onImport = { text ->
                enteringLink = false
                model.linkOpened(text)
            },
            onDismiss = { enteringLink = false },
        )
    }

    state.pending?.let { code -> ImportConfirmDialog(model, code, state) }

    state.prompt?.let { prompt -> ImportPromptDialog(model, prompt) }
}

/**
 * The row under the camera, in iOS's order: the flashlight, Choose from photos and Enter link,
 * each an icon read by its label. The flashlight shows once a camera with a flash is bound; its
 * place is held until the camera answers, so the other two stay put on a phone that has one.
 */
@Composable
private fun ScannerSources(torch: ScannerTorch, enabled: Boolean, onPickPicture: () -> Unit, onEnterLink: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 24.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            torch.isAvailable -> TorchToggle(isOn = torch.isOn, onToggle = torch::toggle)
            torch.isPending -> Spacer(Modifier.size(SOURCE_BUTTON_SIZE))
        }
        FilledTonalIconButton(onClick = onPickPicture, enabled = enabled, modifier = Modifier.size(SOURCE_BUTTON_SIZE)) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = stringResource(R.string.class_table_import_choose_photo))
        }
        FilledTonalIconButton(onClick = onEnterLink, enabled = enabled, modifier = Modifier.size(SOURCE_BUTTON_SIZE)) {
            Icon(Icons.Outlined.Keyboard, contentDescription = stringResource(R.string.class_table_import_enter_link))
        }
    }
}

/** The flashlight, a switch TalkBack reads with its label and on or off; lit while the torch is. */
@Composable
private fun TorchToggle(isOn: Boolean, onToggle: () -> Unit) {
    val label = stringResource(R.string.class_table_import_flashlight)
    Surface(
        shape = CircleShape,
        color = if (isOn) Color.White else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isOn) Color.Black else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .size(SOURCE_BUTTON_SIZE)
            .clip(CircleShape)
            .toggleable(value = isOn, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (isOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, contentDescription = null)
        }
    }
}

private val SOURCE_BUTTON_SIZE = 56.dp

/**
 * A share link typed or pasted, which goes through the import as a scanned code; Import waits for
 * some text. iOS's Enter link alert.
 */
@Composable
private fun EnterLinkDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val canImport = text.isNotBlank()
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.class_table_import_enter_link),
        confirmText = stringResource(R.string.action_import),
        onConfirm = { onImport(text) },
        confirmEnabled = canImport,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.class_table_import_link_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { if (canImport) onImport(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The step before a new person is saved: their nickname, and what the share code holds. Import
 * waits on the course lookup, which back and a tap outside leave alone; Cancel stops it.
 */
@Composable
private fun ImportConfirmDialog(model: ClassTableImportModel, code: ClassTableShareCode, state: ClassTableImportModel.State) {
    val context = LocalContext.current
    val rows = remember(code) {
        importConfirmRows(
            code = code,
            joinList = { android.icu.text.ListFormatter.getInstance().format(it) },
            formatCount = { NumberFormat.getIntegerInstance().format(it) },
            formatTime = { instant ->
                val date = Date.from(instant)
                android.text.format.DateFormat.getDateFormat(context).format(date) + " " +
                    android.text.format.DateFormat.getTimeFormat(context).format(date)
            },
        )
    }
    TigerDuckDialog(
        onDismissRequest = model::cancel,
        title = stringResource(R.string.class_table_import),
        confirmText = stringResource(R.string.action_import),
        onConfirm = model::importPending,
        confirmEnabled = state.canImport,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = model::cancel,
        // Cancel stops a lookup; once the person is being written it is too late.
        dismissEnabled = !state.isSaving,
        dismissable = !state.isLookingUp,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.nickname,
                onValueChange = model::setNickname,
                label = { Text(stringResource(R.string.class_table_nickname)) },
                singleLine = true,
                enabled = !state.isLookingUp,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.importPending() }),
                modifier = Modifier.fillMaxWidth(),
            )
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(row.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (state.isLookingUp) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ImportPromptDialog(model: ClassTableImportModel, prompt: Prompt) {
    val title = when (prompt) {
        Prompt.Invalid, Prompt.NeedsUpdate, Prompt.NoCode, Prompt.OwnCode, is Prompt.SaveFailed ->
            stringResource(R.string.class_table_import_failed)
        is Prompt.Replace -> stringResource(R.string.class_table_import_replace_title, prompt.job.nickname)
        is Prompt.LookupFailed -> stringResource(R.string.class_table_import_lookup_failed)
    }
    val message = when (prompt) {
        Prompt.Invalid -> stringResource(R.string.class_table_import_invalid_message)
        Prompt.NeedsUpdate -> stringResource(R.string.share_code_needs_update_message)
        Prompt.NoCode -> stringResource(R.string.class_table_import_no_code_message)
        Prompt.OwnCode -> stringResource(R.string.class_table_import_own_code_message)
        is Prompt.Replace -> stringResource(R.string.class_table_import_replace_message)
        is Prompt.LookupFailed, is Prompt.SaveFailed -> null
    }
    when (prompt) {
        Prompt.Invalid, Prompt.NeedsUpdate, Prompt.NoCode, Prompt.OwnCode -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            message = message,
            confirmText = stringResource(R.string.action_confirm),
        )
        is Prompt.Replace -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            message = message,
            confirmText = stringResource(R.string.action_update),
            onConfirm = { model.lookUp(prompt.job) },
            dismissText = stringResource(R.string.action_cancel),
        )
        is Prompt.LookupFailed -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            confirmText = stringResource(R.string.action_retry),
            onConfirm = { model.lookUp(prompt.job) },
            dismissText = stringResource(R.string.action_cancel),
        )
        is Prompt.SaveFailed -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            confirmText = stringResource(R.string.action_retry),
            onConfirm = { model.lookUp(prompt.job) },
            dismissText = stringResource(R.string.action_cancel),
        )
    }
}

/** One line of the confirm dialog: what it is, and what the code holds. */
internal data class ImportConfirmRow(@param:StringRes val title: Int, val value: String)

/**
 * In order: the student ID, the terms ("114-2, 115-1"), the number of courses and the share
 * time, which only our own format carries; TAT's may have no student ID either.
 */
internal fun importConfirmRows(
    code: ClassTableShareCode,
    joinList: (List<String>) -> String,
    formatCount: (Int) -> String,
    formatTime: (Instant) -> String,
): List<ImportConfirmRow> = buildList {
    code.studentId?.let { add(ImportConfirmRow(R.string.sign_in_student_id, it)) }
    add(ImportConfirmRow(R.string.class_table_semester_picker_label, joinList(code.terms.map { semesterLabel(it.semester) })))
    add(ImportConfirmRow(R.string.class_table_import_course_count, formatCount(code.terms.sumOf { it.courseNos.size })))
    code.sharedAt?.let { add(ImportConfirmRow(R.string.class_table_import_shared_at, formatTime(it))) }
}

/** `1151` as `115-1`, as the term picker shows it. */
private fun semesterLabel(semester: String) = semester.dropLast(1) + "-" + semester.takeLast(1)
