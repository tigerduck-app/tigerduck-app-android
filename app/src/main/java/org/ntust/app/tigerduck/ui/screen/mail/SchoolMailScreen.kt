package org.ntust.app.tigerduck.ui.screen.mail

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import org.ntust.app.tigerduck.mail.MailDevServerSettings
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailRow
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.OutlinedAccountIdField
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.PasswordTrailingIcons
import org.ntust.app.tigerduck.ui.component.SearchDrawer
import org.ntust.app.tigerduck.ui.component.SecureScreen
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.readToggleIcon
import org.ntust.app.tigerduck.ui.component.rememberAppBarState
import org.ntust.app.tigerduck.ui.component.rememberChromeContentPadding
import org.ntust.app.tigerduck.ui.component.rememberSearchRevealState
import org.ntust.app.tigerduck.ui.component.scrollbar
import org.ntust.app.tigerduck.ui.component.statusText
import org.ntust.app.tigerduck.ui.screen.settings.LoginSheet
import org.ntust.app.tigerduck.ui.screen.settings.signInFieldValue
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spec §6.2 — the Announcements list pattern, over the school inbox. */
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

    // A failed swipe-to-toggle-read, or one flaky minute of the poll, says so once and is gone --
    // the same shape the message screen already uses for its own actions. Only a failed *load*
    // reaches the header dot and the "couldn't load" empty state.
    val context = LocalContext.current
    LaunchedEffect(state.actionError) {
        val error = state.actionError ?: return@LaunchedEffect
        Toast.makeText(context, error.messageRes(), Toast.LENGTH_SHORT).show()
        viewModel.dismissActionError()
    }

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
            DevMailServerBanner(accountViewModel.devServer)
            val addressSuffix = accountViewModel.signInAddressSuffix
            val prefill = accountViewModel.signInPrefill
            SchoolMailLoginCard(
                // Both fields come from the one place the rules live, and every one of them
                // matters: see MailAccountViewModel.signInPrefill. Nothing here submits --
                // filling the fields and stopping is the condition the prefill exists under.
                // Whatever lands stays ordinary editable text: a server that wants a bare
                // username, or a mail password of its own, still works by typing over it.
                initialUsername = prefill.username,
                initialPassword = prefill.password,
                isLoggingIn = signingIn,
                error = signInError?.let { stringResource(it.messageRes()) },
                browserPreference = browserPreference,
                usernameLabel = addressSuffix?.let { "you$it" } ?: stringResource(R.string.sign_in_student_id),
                uppercaseId = accountViewModel.devServer == null,
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
        // Rows are keyed by (folder, uid) -- in All mail a UID alone names two different mails --
        // so the last visible *row* is found by matching those keys against what is displayed,
        // rather than by picking out one key type from among the spinner and spacer items.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNullTo(mutableSetOf()) { it.key as? String } }
            .map { keys -> currentDisplayed.lastOrNull { it.key in keys } }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { row -> viewModel.loadMoreIfNeeded(row) }
    }

    val appBar = rememberAppBarState()
    val chromeScope = rememberCoroutineScope()
    val chromePadding = rememberChromeContentPadding(appBar)
    val searchReveal = rememberSearchRevealState()
    var searchFocused by remember { mutableStateOf(false) }
    // Pinning opens the drawer as well as holding it open, which is what puts the field on screen
    // for a search the view model was still carrying when this screen composed -- coming back from
    // a message -- and for a field that takes focus without a gesture having opened anything.
    searchReveal.pinned = searchFocused || state.searchText.isNotEmpty()

    TigerPullToRefresh(
        isRefreshing = isLoading,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
        refreshingMessage = stringResource(R.string.refreshing_message),
        appBar = appBar,
        searchReveal = searchReveal,
    ) {
        // Clipped, because the chrome overlay hides by translating up past this
        // box's top edge, and the root Scaffold has already padded that edge down
        // to the bottom of the status bar. Unclipped, the band of chrome that
        // lands behind the status bar stayed drawn there, under the clock.
        Box(Modifier.fillMaxSize().clipToBounds()) {
            LazyColumn(
                state = listState,
                // The thumb's track starts where the chrome overlay ends, or the bar would hide
                // it whenever the list is near its top.
                modifier = Modifier
                    .fillMaxSize()
                    .scrollbar(
                        listState,
                        topInsetPx = { appBar.heightPx + appBar.offsetPx },
                        // A fast scroll moves the list without a nested-scroll delta, so a bar it
                        // left hidden over the very top would uncover bare content padding.
                        onFastScrollStopped = {
                            if (!listState.canScrollBackward) chromeScope.launch { appBar.snapToRest() }
                        },
                    ),
                // The chrome is a sibling overlay now rather than the first item, so the list
                // keeps the room for it here instead. The overlay translates away on scroll
                // while this padding stays put, which is what lets the rows travel up under it.
                //
                // Measured lazily (see [ChromeContentPadding]): the chrome's height changes on
                // every frame the search drawer moves, and reading it in this composition body
                // would recompose the whole screen for the length of the gesture.
                contentPadding = chromePadding,
            ) {
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
                                mailDomain = viewModel.mailDomain,
                                // The row's own folder decides everything, never the selected chip:
                                // opened from All mail, a Sent mail has to behave exactly as it
                                // would had the user opened Sent itself.
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { appBar.heightPx = it.height.toFloat() }
                    .graphicsLayer { translationY = appBar.offsetPx }
                    .background(MaterialTheme.colorScheme.background),
            ) {
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
                DevMailServerBanner(accountViewModel.devServer)
                if (authFailed) {
                    AuthFailedBanner(onSignInAgain = {
                        accountViewModel.clearError()
                        showReauthSheet = true
                    })
                }
                SearchDrawer(searchReveal) {
                    OutlinedTextField(
                        value = state.searchText,
                        onValueChange = viewModel::setSearchText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .onFocusChanged { searchFocused = it.isFocused },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.school_mail_search_prompt)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                    )
                }
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
    }

    if (showReauthSheet) {
        val addressSuffix = accountViewModel.signInAddressSuffix
        val prefill = accountViewModel.signInPrefill
        LoginSheet(
            title = stringResource(R.string.school_mail_account_title),
            subtitle = stringResource(R.string.school_mail_sign_in_note),
            usernamePlaceholder = addressSuffix?.let { "you$it" } ?: stringResource(R.string.sign_in_student_id),
            passwordPlaceholder = stringResource(R.string.sign_in_password),
            // Re-auth starts from the saved mail ID, and from the stored NTUST password
            // where that ID is the NTUST one -- see MailAccountViewModel.signInPrefill.
            // Offered, never sent: the button is what signs in, here as everywhere.
            initialUsername = prefill.username,
            initialPassword = prefill.password,
            uppercaseInput = accountViewModel.devServer == null,
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
private fun MailCard(message: MailSummary, mailDomain: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val unread = !message.flags.seen
    // [modifier] lands on the same layout node as Surface's own clickable, so semantics set on
    // it merge into the single card node an accessibility service focuses.
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant, modifier = modifier.fillMaxWidth()) {
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
                    if (MailWarnings.isExternalSender(message.from, message.returnPath, mailDomain)) {
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

/**
 * Same gesture as SwipeableBulletinCard: either direction toggles read (100dp threshold, 0.6×
 * damping) -- plus a custom accessibility action carrying the same label, because an
 * accessibility service cannot perform a drag and read state is functional, not decorative.
 * Without it the card's only reachable action was opening the mail.
 */
@Composable
private fun SwipeableMailCard(
    row: MailRow,
    mailDomain: String,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = row.summary
    val latestToggle by rememberUpdatedState(onToggleRead)
    val thresholdPx = with(LocalDensity.current) { 100.dp.toPx() }
    // Keyed on (folder, uid): two folders can hand out the same UID, and a swipe in progress
    // must not carry over to the other mail when the list re-merges.
    val offset = remember(row.key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val icon = readToggleIcon(isRead = message.flags.seen)
    val iconDescription = stringResource(if (message.flags.seen) R.string.school_mail_mark_unread else R.string.school_mail_mark_read)
    // The same localized label the swipe icon announces: what the toggle is about to do.
    val readActions = remember(iconDescription) {
        listOf(CustomAccessibilityAction(iconDescription) { latestToggle(); true })
    }

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
        ) {
            MailCard(
                message,
                mailDomain,
                onClick,
                modifier = Modifier.semantics { customActions = readActions },
            )
        }
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

/**
 * Says out loud that this is not the school mailbox.
 *
 * The debug-only Developer -> Email override points the whole feature at another server,
 * and mail that looks entirely ordinary is exactly what a test mailbox produces -- someone
 * who forgot the override is on would otherwise read a test inbox as their school inbox and
 * act on it. Shown signed out as well as signed in, because the sign-in itself already goes
 * to the overridden server. [settings] is null whenever no override is in force, which in a
 * release build is always, so this renders nothing there.
 */
@Composable
private fun DevMailServerBanner(settings: MailDevServerSettings?) {
    if (settings == null) return
    val color = Color(0xFFFF9500)
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                // Developer strings in this app are English and not localized.
                "Developer mail server — not your school mailbox",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = color,
            )
            Text(
                "@${settings.domain} · IMAP ${settings.imap.host}:${settings.imap.port}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

/** Library's LoginPromptCard, for the school mail account (spec §7.1). */
@Composable
private fun SchoolMailLoginCard(
    initialUsername: String,
    /** What the password field starts from; never submitted, and never laid over an edit. */
    initialPassword: String,
    isLoggingIn: Boolean,
    error: String?,
    browserPreference: String,
    /**
     * What the field is asking for: the localized "student ID" against the school, and the
     * shape of an address on the overridden server while that is on.
     */
    usernameLabel: String,
    /** False while the debug mail-server override is on: only the school login name is uppercase. */
    uppercaseId: Boolean,
    onSubmit: (String, String) -> Unit,
) {
    // Null until edited, so a prefill only ever reaches an untouched field -- the same rule,
    // and the same helper, as the sheet the other two entry points use.
    var typedUsername by rememberSaveable { mutableStateOf<String?>(null) }
    var typedPassword by rememberSaveable { mutableStateOf<String?>(null) }
    val username = signInFieldValue(typedUsername, initialUsername)
    val password = signInFieldValue(typedPassword, initialPassword)
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
                onValueChange = { raw ->
                    typedUsername = raw.filter { !it.isWhitespace() }.let { if (uppercaseId) it.uppercase() else it }
                },
                label = usernameLabel,
                capitalization = if (uppercaseId) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
                enabled = !isLoggingIn,
                autofillHint = android.view.View.AUTOFILL_HINT_USERNAME,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { typedPassword = it },
                label = { Text(stringResource(R.string.sign_in_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = if (!isLoggingIn) {
                    {
                        PasswordTrailingIcons(
                            password = password,
                            passwordVisible = passwordVisible,
                            onClear = { typedPassword = ""; passwordVisible = false },
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
