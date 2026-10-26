package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShare
import org.ntust.app.tigerduck.data.sharing.ClassTableShare.Selection
import org.ntust.app.tigerduck.data.sharing.ClassTableShareQrCode
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.rememberDetentSheetState
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * Big enough to scan from a phone's screenshot after a chat app has halved it: the code for
 * eight terms of ten courses still gets about four pixels a module there.
 */
private val CODE_SIDE = 260.dp

/** The header's icon buttons, a whole touch target each. */
private val HEADER_BUTTON_SIZE = 48.dp

/**
 * The QR code of the user's class table, its caption "B11300000 · 114-2", the term to share
 * ("All semesters" or one term) and the TAT-compatible switch, as the iOS share sheet has them.
 * Its header holds the way to the import scanner at the start, which [onScan] opens once the
 * sheet has closed, and Copy link beside Done at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClassTableShareSheet(
    share: ClassTableShare,
    labelFor: (semester: String) -> String,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
) {
    val sheetState = rememberDetentSheetState()
    // The scanner opens only once this sheet is gone, as on iOS.
    var scanOnceClosed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var state by remember(share) { mutableStateOf(share.initialState) }
    // One share time for the sheet, so the code stays put while it is open.
    val sharedAt = rememberSaveable { AppClock.instant().toEpochMilli() }
    val code = remember(share, state) { share.code(state, java.time.Instant.ofEpochMilli(sharedAt)) }
    val qr = remember(code) { code?.let(ClassTableShareQrCode::bitmap) }
    val allSemesters = stringResource(R.string.class_table_share_all_semesters)
    fun label(selection: Selection) = when (selection) {
        Selection.AllSemesters -> allSemesters
        is Selection.Semester -> labelFor(selection.code)
    }
    val close: () -> Unit = { scope.launch { sheetState.hide() } }

    DetentSheet(
        // Not for a sheet gone meanwhile, as a link closes it: it never comes to rest closed, and
        // the scanner would open over the link's page.
        onDismissed = {
            onDismiss()
            if (scanOnceClosed) onScan()
        },
        state = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onClick@{
                        // Once: a second request would cancel the first's close, whose clean-up
                        // would then clear the flag for both.
                        if (scanOnceClosed) return@onClick
                        scanOnceClosed = true
                        scope.launch {
                            try {
                                sheetState.hide()
                            } finally {
                                // Caught on its way down and pulled back up: a later Done only closes it.
                                if (!sheetState.isClosed) scanOnceClosed = false
                            }
                        }
                    },
                    modifier = Modifier.size(HEADER_BUTTON_SIZE),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.class_table_import))
                }
                Text(
                    text = stringResource(R.string.class_table_share),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                CopyLinkButton(link = code?.let(ClassTableShare::link))
                TextButton(onClick = close) { Text(stringResource(R.string.action_done)) }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = CODE_SIDE)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        // White in dark mode too: a scanner wants dark modules on light.
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.a11y_class_table_share_qr),
                            // Each module a crisp square, not a blur of its neighbours.
                            filterQuality = FilterQuality.None,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.library_qr_generate_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.DarkGray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Text(
                    text = "${share.studentId} · ${label(state.selection)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            SemesterField(
                options = share.options,
                selected = state.selection,
                label = ::label,
                onPick = { state = share.select(state, it) },
            )
            val offersTat = share.offersTat(state.selection)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .toggleable(
                        value = state.tatCompatible,
                        enabled = offersTat,
                        role = Role.Switch,
                        onValueChange = { state = share.setTatCompatible(state, it) },
                    )
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.class_table_share_tat_compatible),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (offersTat) 1f else ContentAlpha.DISABLED,
                    ),
                    modifier = Modifier.weight(1f),
                )
                // The row toggles; the switch only shows the value.
                Switch(checked = state.tatCompatible, onCheckedChange = null, enabled = offersTat)
            }
        }
    }
}

/**
 * The user's own share sheet the import scanner handed over to ([share]), which the navigation
 * shows once the scanner is gone, as iOS's root presents both. It is no page's: an import opened
 * over it, as a share link opens one, closes it ([onDismiss]), and so does a sign-out, as the
 * code names the account that left.
 */
@Composable
internal fun ScannerShareSheet(
    share: ClassTableShare?,
    importOpen: Boolean,
    signedIn: Boolean,
    labelFor: (semester: String) -> String,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
) {
    val closes = share != null && (importOpen || !signedIn)
    LaunchedEffect(closes) { if (closes) onDismiss() }
    if (share != null && !closes) ClassTableShareSheet(share, labelFor, onDismiss, onScan)
}

/**
 * Copies [link], the code shown as a link to paste, and turns into a checkmark for 1.5 s; TalkBack
 * hears it copied once, as the course code's copy says it. Off while no code can be written.
 */
@Composable
private fun CopyLinkButton(link: String?) {
    val context = LocalContext.current
    val view = LocalView.current
    val copiedMessage = stringResource(R.string.class_table_share_link_copied)
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    // Keyed on the count, so a second copy inside the window restarts the timer.
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        copied = true
        delay(1_500)
        copied = false
    }
    // Another term or the TAT switch shows another code: the checkmark was for the one before.
    LaunchedEffect(link) { copied = false }
    IconButton(
        onClick = {
            if (link == null) return@IconButton
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("class_table_share_link", link))
            // The checkmark is invisible to TalkBack.
            @Suppress("DEPRECATION")
            view.announceForAccessibility(copiedMessage)
            copies++
        },
        enabled = link != null,
        modifier = Modifier
            .size(HEADER_BUTTON_SIZE)
            .semantics { if (copied) stateDescription = copiedMessage },
    ) {
        Icon(
            if (copied) Icons.Filled.Check else Icons.Filled.Link,
            contentDescription = stringResource(R.string.class_table_share_copy_link),
        )
    }
}

/**
 * A row like the TAT switch's under it, its term and menu drawn as the class table's own
 * semester picker draws them.
 */
@Composable
private fun SemesterField(
    options: List<Selection>,
    selected: Selection,
    label: (Selection) -> String,
    onPick: (Selection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val fieldLabel = stringResource(R.string.class_table_semester_picker_label)
    val current = label(selected)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                role = Role.DropdownList
                contentDescription = fieldLabel
                stateDescription = current
            }
            .clickable { expanded = true }
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = fieldLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(12.dp),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label(option),
                                color = if (option == selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            expanded = false
                            onPick(option)
                        },
                    )
                }
            }
        }
    }
}
