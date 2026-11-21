package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.screen.mail.ForgotMailPasswordLink
import org.ntust.app.tigerduck.ui.screen.mail.MailAccountViewModel
import org.ntust.app.tigerduck.ui.screen.mail.messageRes

/** Which of the Account section's sign-in sheets is open. The screen holds it: the sheets sit outside its list. */
@Stable
internal class AccountSheets {
    var ntust by mutableStateOf(false)
    var library by mutableStateOf(false)
    var mail by mutableStateOf(false)
}

/** The Account section: the school system, the library and School Mail, each its own account. */
@Composable
internal fun AccountSection(viewModel: SettingsViewModel, mail: MailAccountViewModel, sheets: AccountSheets) {
    val isNtustLoggedIn by viewModel.isNtustLoggedIn.collectAsStateWithLifecycle()
    val isNtustLoggingIn by viewModel.isNtustLoggingIn.collectAsStateWithLifecycle()
    val isLibraryLoggedIn by viewModel.isLibraryLoggedIn.collectAsStateWithLifecycle()
    val libIsLoggingIn by viewModel.libIsLoggingIn.collectAsStateWithLifecycle()
    val isMailSignedIn by mail.signedIn.collectAsStateWithLifecycle()
    val mailAuthFailed by mail.authFailed.collectAsStateWithLifecycle()
    val isMailSigningIn by mail.signingIn.collectAsStateWithLifecycle()

    // Consume the deep-link signal raised by the signed-out empty-state lock
    // icons. Capture it into local state so we can pulse exactly once and
    // re-arm on a future entry, even if the user backs out and taps again.
    var highlightNtustRow by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.appState.pendingNtustSignInHighlight) {
        if (viewModel.appState.pendingNtustSignInHighlight) {
            highlightNtustRow = true
            viewModel.appState.pendingNtustSignInHighlight = false
        }
    }

    SettingsSectionCard(SettingsSection.ACCOUNT) {
        val accountButtonMinWidth = rememberAccountButtonMinWidth()
        AccountRow(
            title = SettingsRow.SCHOOL_ACCOUNT.label(),
            isLoggedIn = isNtustLoggedIn,
            subtitle = if (isNtustLoggedIn) viewModel.ntustStudentId else null,
            isLoggingIn = isNtustLoggingIn,
            onLogin = { sheets.ntust = true },
            // Asks first when the sign-out would delete what only this phone has,
            // the imported class tables among it.
            onLogout = { viewModel.requestNtustSignOut() },
            actionMinWidth = accountButtonMinWidth,
            highlight = highlightNtustRow,
            onHighlightConsumed = { highlightNtustRow = false },
        )
        HorizontalDivider()
        AccountRow(
            title = SettingsRow.LIBRARY_ACCOUNT.label(),
            isLoggedIn = isLibraryLoggedIn,
            subtitle = if (isLibraryLoggedIn) viewModel.libraryUsername else null,
            isLoggingIn = libIsLoggingIn,
            onLogin = { sheets.library = true },
            onLogout = { viewModel.logoutLibrary() },
            actionMinWidth = accountButtonMinWidth,
        )
        HorizontalDivider()
        // Its own account (spec §7.1). A rejected password shows as
        // signed out so the button offers "Sign in" again.
        AccountRow(
            title = SettingsRow.MAIL_ACCOUNT.label(),
            isLoggedIn = isMailSignedIn && !mailAuthFailed,
            subtitle = if (isMailSignedIn) mail.studentId else null,
            isLoggingIn = isMailSigningIn,
            onLogin = {
                mail.clearError()
                sheets.mail = true
            },
            onLogout = { mail.signOut() },
            actionMinWidth = accountButtonMinWidth,
        )
    }
}

/** The Account section's sign-in sheets, each closed once its sign-in lands, and the school sign-out's question. */
@Composable
internal fun AccountSheetsHost(viewModel: SettingsViewModel, mail: MailAccountViewModel, sheets: AccountSheets, browserPreference: String) {
    val isNtustLoggedIn by viewModel.isNtustLoggedIn.collectAsStateWithLifecycle()
    val isLibraryLoggedIn by viewModel.isLibraryLoggedIn.collectAsStateWithLifecycle()
    val isMailSignedIn by mail.signedIn.collectAsStateWithLifecycle()
    val mailAuthFailed by mail.authFailed.collectAsStateWithLifecycle()
    LaunchedEffect(isNtustLoggedIn) { if (isNtustLoggedIn) sheets.ntust = false }
    LaunchedEffect(isLibraryLoggedIn) { if (isLibraryLoggedIn) sheets.library = false }
    LaunchedEffect(isMailSignedIn, mailAuthFailed) { if (isMailSignedIn && !mailAuthFailed) sheets.mail = false }

    if (sheets.ntust) NtustLoginSheet(viewModel) { sheets.ntust = false }
    if (sheets.library) LibraryLoginSheet(viewModel) { sheets.library = false }
    if (sheets.mail) MailLoginSheet(mail, browserPreference) { sheets.mail = false }

    // Every loss the sign-out would cause, one line each: the iPhone app asks about the imported
    // class tables, and this phone may also hold edits TigerSync does not keep.
    val signOutConfirmation by viewModel.signOutConfirmation.collectAsStateWithLifecycle()
    signOutConfirmation?.let { losses ->
        TigerDuckDialog(
            onDismissRequest = viewModel::dismissNtustSignOut,
            title = stringResource(R.string.action_sign_out),
            message = losses.sortedBy { it.ordinal }.map { stringResource(it.message) }.joinToString("\n"),
            confirmText = stringResource(R.string.action_sign_out),
            confirmColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            onConfirm = viewModel::confirmNtustSignOut,
            dismissText = stringResource(R.string.action_cancel),
        )
    }
}

@Composable
private fun NtustLoginSheet(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val isLoggingIn by viewModel.isNtustLoggingIn.collectAsStateWithLifecycle()
    val loginError by viewModel.ntustLoginError.collectAsStateWithLifecycle()
    LoginSheet(
        title = stringResource(R.string.settings_account_ntust_system),
        usernamePlaceholder = stringResource(R.string.sign_in_student_id),
        passwordPlaceholder = stringResource(R.string.sign_in_password),
        uppercaseInput = true,
        isLoggingIn = isLoggingIn,
        loginError = loginError,
        onLogin = { u, p -> viewModel.loginNtust(u, p) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun LibraryLoginSheet(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val isLoggingIn by viewModel.libIsLoggingIn.collectAsStateWithLifecycle()
    val loginError by viewModel.libLoginError.collectAsStateWithLifecycle()
    LoginSheet(
        title = stringResource(R.string.settings_account_library_system),
        subtitle = stringResource(R.string.settings_library_account_subtitle),
        usernamePlaceholder = stringResource(R.string.library_sign_in_username),
        passwordPlaceholder = stringResource(R.string.library_sign_in_password),
        initialUsername = viewModel.ntustStudentId.orEmpty(),
        isLoggingIn = isLoggingIn,
        loginError = loginError,
        onLogin = { u, p -> viewModel.loginLibrary(u, p) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun MailLoginSheet(mail: MailAccountViewModel, browserPreference: String, onDismiss: () -> Unit) {
    val isSigningIn by mail.signingIn.collectAsStateWithLifecycle()
    val error by mail.error.collectAsStateWithLifecycle()
    // Under the debug mail-server override the server wants a whole address, not the
    // bare ID the school takes, so the field says so and starts at the domain. The
    // same two lines are in SchoolMailScreen's card and re-auth sheet; this is the
    // third way into the same sign-in.
    val mailAddressSuffix = mail.signInAddressSuffix
    val mailPrefill = mail.signInPrefill
    LoginSheet(
        title = stringResource(R.string.school_mail_account_title),
        subtitle = stringResource(R.string.school_mail_sign_in_note),
        usernamePlaceholder = mailAddressSuffix?.let { "you$it" } ?: stringResource(R.string.sign_in_student_id),
        passwordPlaceholder = stringResource(R.string.sign_in_password),
        // The mail account's own ID where there is one, otherwise the stored NTUST ID
        // and password — never the library account, and never under the debug mail
        // server override. MailAccountViewModel.signInPrefill holds the whole rule,
        // including the condition it exists under: nothing is ever submitted for the
        // user. This row is the busiest of the three ways into this sign-in.
        initialUsername = mailPrefill.username,
        initialPassword = mailPrefill.password,
        uppercaseInput = mail.devServer == null,
        isLoggingIn = isSigningIn,
        loginError = error?.let { stringResource(it.messageRes()) },
        onLogin = { u, p -> mail.signIn(u, p) },
        onDismiss = onDismiss,
        footer = { ForgotMailPasswordLink(browserPreference) },
    )
}

/** A root section: its title over a card of its rows, or for one with no title, a gap. */
@Composable
internal fun SettingsSectionCard(section: SettingsSection, content: @Composable () -> Unit) {
    Column {
        section.title?.let { SectionHeader(stringResource(it)) } ?: Spacer(Modifier.height(12.dp))
        ContentCard { Column { content() } }
    }
}
