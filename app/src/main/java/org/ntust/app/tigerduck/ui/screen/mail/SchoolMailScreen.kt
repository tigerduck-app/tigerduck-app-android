package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailRow
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.OutlinedAccountIdField
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.PasswordTrailingIcons
import org.ntust.app.tigerduck.ui.component.SecureScreen
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.statusText
import org.ntust.app.tigerduck.ui.screen.settings.LoginSheet
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spec §6.2 — the Announcements list pattern, over the school inbox. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SchoolMailScreen(
    browserPreference: String,
    onOpenMessage: (folder: String, uid: Long) -> Unit,
    onEditDraft: (folder: String, uid: Long) -> Unit,
    onCompose: () -> Unit,
    onOpenGuide: () -> Unit,
    viewModel: SchoolMailListViewModel = hiltViewModel(),
    accountViewModel: MailAccountViewModel = hiltViewModel(),
) {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val authFailed by viewModel.authFailed.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signingIn by accountViewModel.signingIn.collectAsStateWithLifecycle()
    val signInError by accountViewModel.error.collectAsStateWithLifecycle()
    var showReauthSheet by remember { mutableStateOf(false) }

    LaunchedEffect(signedIn, authFailed) {
        if (signedIn && !authFailed) {
            showReauthSheet = false
            viewModel.load()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keyed on authFailed too: the poll stops while the password is rejected (spec §7.4),
    // so signing in again has to re-add the observer for polling to start back up.
    DisposableEffect(lifecycleOwner, signedIn, authFailed) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (signedIn && !authFailed) viewModel.startPolling()
                Lifecycle.Event.ON_PAUSE -> viewModel.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPolling()
        }
    }

    if (!signedIn) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeader(title = stringResource(R.string.feature_school_mail))
            SchoolMailLoginCard(
                initialUsername = accountViewModel.studentId.orEmpty(),
                isLoggingIn = signingIn,
                error = signInError?.let { stringResource(it.messageRes()) },
                browserPreference = browserPreference,
                onSubmit = accountViewModel::signIn,
            )
            TextButton(onClick = onOpenGuide, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.school_mail_use_other_app))
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val isLoading = state.loadState is SchoolMailListViewModel.LoadState.Loading
    val currentDisplayed by rememberUpdatedState(state.displayed)
    LaunchedEffect(listState) {
        // Rows are keyed by (folder, uid) -- in 所有信件 a UID alone names two different mails --
        // so the last visible *row* is found by matching those keys against what is displayed,
        // rather than by picking out one key type from among the header and spacer items.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNullTo(mutableSetOf()) { it.key as? String } }
            .map { keys -> currentDisplayed.lastOrNull { it.key in keys } }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { row -> viewModel.loadMoreIfNeeded(row) }
    }

    TigerPullToRefresh(
        isRefreshing = isLoading,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
        refreshingMessage = stringResource(R.string.refreshing_message),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            stickyHeader(key = "headers") {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    PageHeader(title = stringResource(R.string.feature_school_mail)) {
                        val failed = state.loadState as? SchoolMailListViewModel.LoadState.Failed
                        val mailStatus = when {
                            failed != null || authFailed -> ServerStatus.FAILED
                            state.loadState is SchoolMailListViewModel.LoadState.Loaded -> ServerStatus.OK
                            else -> ServerStatus.UNKNOWN
                        }
                        SyncStatusDot(
                            status = mailStatus,
                            label = stringResource(R.string.feature_school_mail),
                            icon = Icons.Filled.Mail,
                            // The state of the mail server, in the same words every other row of
                            // this dot uses -- not the student ID, which is an identity and says
                            // nothing about whether anything is reaching the server. The specific
                            // failure still has a home: the list itself shows the error.
                            text = statusText(mailStatus),
                            isLoading = isLoading,
                        )
                        IconButton(onClick = { viewModel.setUnreadOnly(!state.unreadOnly) }) {
                            Icon(
                                if (state.unreadOnly) Icons.Filled.FilterAlt else Icons.Filled.FilterAltOff,
                                contentDescription = stringResource(R.string.school_mail_unread_only),
                            )
                        }
                        IconButton(onClick = onOpenGuide) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.school_mail_use_other_app))
                        }
                        IconButton(onClick = onCompose) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.school_mail_compose))
                        }
                    }
                    if (authFailed) {
                        AuthFailedBanner(onSignInAgain = {
                            accountViewModel.clearError()
                            showReauthSheet = true
                        })
                    }
                    OutlinedTextField(
                        value = state.searchText,
                        onValueChange = viewModel::setSearchText,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.school_mail_search_prompt)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                    )
                    FolderChips(state = state, onSelect = viewModel::selectFolder)
                    if (state.searchLocalOnly) {
                        Text(
                            stringResource(R.string.school_mail_search_local_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            val displayed = state.displayed
            val failed = state.loadState as? SchoolMailListViewModel.LoadState.Failed
            when {
                displayed.isEmpty() && failed != null -> item(key = "failed") {
                    Box(Modifier.fillParentMaxHeight(0.6f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyStateView(
                            icon = Icons.Filled.Mail,
                            title = stringResource(R.string.school_mail_load_failed_title),
                            message = stringResource(failed.error.messageRes()),
                        )
                    }
                }
                displayed.isEmpty() && state.loadState is SchoolMailListViewModel.LoadState.Loaded -> item(key = "empty") {
                    Box(Modifier.fillParentMaxHeight(0.6f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyStateView(
                            icon = Icons.Filled.Mail,
                            title = stringResource(R.string.school_mail_empty_title),
                            message = stringResource(R.string.school_mail_empty_message),
                        )
                    }
                }
                else -> {
                    items(displayed, key = { it.key }) { row ->
                        SwipeableMailCard(
                            row = row,
                            // The row's own folder decides everything, never the selected chip:
                            // opened from 所有信件, a 寄件備份 mail has to behave exactly as it
                            // would had the user opened 寄件備份 itself.
                            onClick = {
                                if (state.kindOf(row.folder) == SpecialFolder.DRAFTS) onEditDraft(row.folder, row.uid)
                                else onOpenMessage(row.folder, row.uid)
                            },
                            onToggleRead = { viewModel.toggleRead(row) },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    if (state.isPaginating || state.isSearching) {
                        item(key = "spinner") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    if (showReauthSheet) {
        LoginSheet(
            title = stringResource(R.string.school_mail_account_title),
            subtitle = stringResource(R.string.school_mail_sign_in_note),
            usernamePlaceholder = stringResource(R.string.sign_in_student_id),
            passwordPlaceholder = stringResource(R.string.sign_in_password),
            initialUsername = accountViewModel.studentId.orEmpty(),
            uppercaseInput = true,
            isLoggingIn = signingIn,
            loginError = signInError?.let { stringResource(it.messageRes()) },
            onLogin = { u, p -> accountViewModel.signIn(u, p) },
            onDismiss = { showReauthSheet = false },
            footer = { ForgotMailPasswordLink(browserPreference) },
        )
    }
}

@Composable
private fun FolderChips(state: SchoolMailListViewModel.UiState, onSelect: (FolderSelection) -> Unit) {
    var showOthers by remember { mutableStateOf(false) }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(state.chips, key = { it.key }) { chip ->
            FilterChip(
                selected = chip.selection == state.selected,
                onClick = { onSelect(chip.selection) },
                label = { Text(stringResource(chip.kind?.labelRes() ?: R.string.school_mail_folder_all)) },
            )
        }
        if (state.others.isNotEmpty()) {
            item(key = "others") {
                Box {
                    val selectedName = (state.selected as? FolderSelection.Real)?.name
                    val otherSelected = selectedName != null && selectedName in state.others
                    FilterChip(
                        selected = otherSelected,
                        onClick = { showOthers = true },
                        label = {
                            Text(if (otherSelected && selectedName != null) selectedName else stringResource(R.string.school_mail_folder_more))
                        },
                    )
                    DropdownMenu(
                        expanded = showOthers,
                        onDismissRequest = { showOthers = false },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        state.others.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    showOthers = false
                                    onSelect(FolderSelection.Real(name))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

fun SpecialFolder.labelRes(): Int = when (this) {
    SpecialFolder.INBOX -> R.string.school_mail_folder_inbox
    SpecialFolder.SENT -> R.string.school_mail_folder_sent
    SpecialFolder.DRAFTS -> R.string.school_mail_folder_drafts
    SpecialFolder.JUNK -> R.string.school_mail_folder_junk
    SpecialFolder.TRASH -> R.string.school_mail_folder_trash
}

/** Mirrors Announcements' BulletinCard: 12dp surfaceVariant card, 7dp unread dot, SemiBold when unread. */
@Composable
private fun MailCard(message: MailSummary, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val unread = !message.flags.seen
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(cs.primary))
                    Spacer(Modifier.width(6.dp))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.from?.display?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.school_mail_no_sender),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (MailWarnings.isExternalSender(message.from, message.returnPath)) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFFFF9500).copy(alpha = 0.18f)) {
                            Text(
                                stringResource(R.string.school_mail_external_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF9500),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (message.hasAttachments) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, tint = cs.outline, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(MailDateFormat.short(message.sentAt ?: message.receivedAt), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = message.subject.ifBlank { stringResource(R.string.school_mail_no_subject) },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Same gesture as SwipeableBulletinCard: either direction toggles read (100dp threshold, 0.6× damping). */
@Composable
private fun SwipeableMailCard(row: MailRow, onClick: () -> Unit, onToggleRead: () -> Unit, modifier: Modifier = Modifier) {
    val message = row.summary
    val latestToggle by rememberUpdatedState(onToggleRead)
    val thresholdPx = with(LocalDensity.current) { 100.dp.toPx() }
    // Keyed on (folder, uid): two folders can hand out the same UID, and a swipe in progress
    // must not carry over to the other mail when the list re-merges.
    val offset = remember(row.key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val icon = if (message.flags.seen) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Check
    val iconDescription = stringResource(if (message.flags.seen) R.string.school_mail_mark_unread else R.string.school_mail_mark_read)

    Box(modifier.fillMaxWidth()) {
        val progress = (abs(offset.value) / thresholdPx).coerceIn(0f, 1f)
        if (offset.value != 0f) {
            Box(
                Modifier.matchParentSize().padding(horizontal = 20.dp),
                contentAlignment = if (offset.value > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(icon, iconDescription, tint = Color(0xFF34C759), modifier = Modifier.size(26.dp).alpha(progress).scale(0.5f + 0.5f * progress))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(row.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(offset.value) >= thresholdPx) {
                                    offset.animateTo(if (offset.value > 0) 2000f else -2000f, tween(200))
                                    latestToggle()
                                    offset.snapTo(0f)
                                } else {
                                    offset.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                        onHorizontalDrag = { _, delta ->
                            val signed = if (isRtl) -delta else delta
                            scope.launch { offset.snapTo(offset.value + signed * 0.6f) }
                        },
                    )
                },
        ) { MailCard(message, onClick) }
    }
}

@Composable
private fun AuthFailedBanner(onSignInAgain: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.school_mail_auth_failed_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignInAgain) { Text(stringResource(R.string.action_sign_in_again)) }
        }
    }
}

/** Library's LoginPromptCard, for the school mail account (spec §7.1). */
@Composable
private fun SchoolMailLoginCard(
    initialUsername: String,
    isLoggingIn: Boolean,
    error: String?,
    browserPreference: String,
    onSubmit: (String, String) -> Unit,
) {
    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    SecureScreen(secure = passwordVisible)
    val canSubmit = username.isNotBlank() && password.isNotBlank() && !isLoggingIn
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.school_mail_sign_in_prompt_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                stringResource(R.string.school_mail_sign_in_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
            OutlinedAccountIdField(
                value = username,
                onValueChange = { raw -> username = raw.filter { !it.isWhitespace() }.uppercase() },
                label = stringResource(R.string.sign_in_student_id),
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Next,
                enabled = !isLoggingIn,
                autofillHint = android.view.View.AUTOFILL_HINT_USERNAME,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.sign_in_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = if (!isLoggingIn) {
                    {
                        PasswordTrailingIcons(
                            password = password,
                            passwordVisible = passwordVisible,
                            onClear = { password = ""; passwordVisible = false },
                            onToggleVisibility = { passwordVisible = !passwordVisible },
                        )
                    }
                } else null,
                enabled = !isLoggingIn,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (canSubmit) onSubmit(username, password) }),
            )
            if (error != null) {
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { onSubmit(username, password) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (isLoggingIn) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.action_sign_in))
                }
            }
            ForgotMailPasswordLink(browserPreference)
        }
    }
}
