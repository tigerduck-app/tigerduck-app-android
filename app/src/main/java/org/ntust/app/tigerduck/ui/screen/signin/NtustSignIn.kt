package org.ntust.app.tigerduck.ui.screen.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.schoolRefusalMessage
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.screen.settings.LoginSheet

/** The warning colour the TigerSync status rows use too. */
private val WarningOrange = Color(0xFFFF9500)

/**
 * The school sign-in sheet any page can open with "Sign in again"
 * ([org.ntust.app.tigerduck.ui.AppState.ntustSignInSheetRequested]). Hosted once, above the pages.
 * It signs in as the stored account, so its student ID is fixed; another student signs out in
 * Settings first, where the sign-out asks about what goes with the account.
 */
@Composable
fun NtustSignInSheetHost(viewModel: NtustSignInViewModel = hiltViewModel()) {
    if (!viewModel.sheetRequested) return
    val isLoggingIn by viewModel.isLoggingIn.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val storedStudentId = viewModel.storedStudentId
    LoginSheet(
        title = stringResource(R.string.settings_account_ntust_system),
        usernamePlaceholder = stringResource(R.string.sign_in_student_id),
        passwordPlaceholder = stringResource(R.string.sign_in_password),
        initialUsername = storedStudentId.orEmpty(),
        usernameEditable = storedStudentId == null,
        uppercaseInput = true,
        isLoggingIn = isLoggingIn,
        loginError = loginError,
        onLogin = viewModel::signIn,
        onDismiss = viewModel::dismissSheet,
    )
}

/**
 * "Automatic sign-in failed", at the top of Home, the class table and Score, while the school
 * refuses the stored password (StoredPasswordLatch) and an account is signed in: the school's
 * reason, "Sign in again", and a close button that hides it for the rest of this run. Network
 * failures never show it. Mirrors the iPhone's `NTUSTReauthErrorBanner`.
 */
@Composable
fun AutoSignInFailedBanner(
    modifier: Modifier = Modifier,
    viewModel: NtustSignInViewModel = hiltViewModel(),
) {
    val rejection by viewModel.rejection.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val shown = bannerRejection(rejection, viewModel.dismissedRejection, signedIn) ?: return
    AutoSignInFailedCard(
        reason = LocalContext.current.schoolRefusalMessage(shown.reason),
        onSignInAgain = viewModel::requestSignIn,
        onDismiss = { viewModel.dismissBanner(shown) },
        modifier = modifier,
    )
}

@Composable
internal fun AutoSignInFailedCard(
    reason: String,
    onSignInAgain: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentCard(modifier = modifier) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = WarningOrange,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.common_auto_sign_in_failed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onSignInAgain) {
                    Text(stringResource(R.string.action_sign_in_again))
                }
            }
        }
    }
}
