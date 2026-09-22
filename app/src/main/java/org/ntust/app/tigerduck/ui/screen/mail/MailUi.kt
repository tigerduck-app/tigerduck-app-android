package org.ntust.app.tigerduck.ui.screen.mail

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.MailError

/** Where students reset the mail password (spec §7.1). */
const val WEBMAIL_URL = "https://mail.ntust.edu.tw"

@StringRes
fun MailError.messageRes(): Int = when (this) {
    is MailError.AuthFailed -> R.string.school_mail_error_auth
    is MailError.Network -> R.string.school_mail_error_network
    is MailError.Certificate -> R.string.school_mail_error_certificate
    is MailError.ServerBusy -> R.string.school_mail_error_busy
    // Reaches the user from the source view of an outsized mail, where "something
    // went wrong" would be actively misleading: nothing went wrong, the mail is
    // simply past what the app will hold in memory.
    is MailError.TooLarge -> R.string.school_mail_error_too_large
    // No dedicated copy for these yet: they either aren't reachable from sign-in
    // (SearchUnsupported, FolderChanged surface in the list/search screens, not
    // built yet) or are internal-only (Protocol, DemoMode never reaches the UI).
    is MailError.SearchUnsupported,
    is MailError.Protocol,
    is MailError.DemoMode,
    is MailError.FolderChanged,
    -> R.string.school_mail_error_generic
}

/** Opens a URL the way the rest of the app does: Custom Tabs for "inApp", the browser otherwise. */
fun openMailLink(context: Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    runCatching {
        if (browserPreference == "inApp") {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun ForgotMailPasswordLink(browserPreference: String) {
    val context = LocalContext.current
    TextButton(
        onClick = { openMailLink(context, WEBMAIL_URL, browserPreference) },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(stringResource(R.string.school_mail_forgot_password), style = MaterialTheme.typography.labelMedium)
    }
}
