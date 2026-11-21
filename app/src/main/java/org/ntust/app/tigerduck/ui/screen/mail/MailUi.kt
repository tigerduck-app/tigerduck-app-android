package org.ntust.app.tigerduck.ui.screen.mail

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.ui.openLink

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
    is MailError.SendUnconfirmed -> R.string.school_mail_send_unconfirmed
    // No dedicated copy for these yet: they either aren't reachable from sign-in
    // (SearchUnsupported, FolderChanged surface in the list/search screens, not
    // built yet) or are internal-only (Protocol, DemoMode never reach the UI;
    // Abandoned is let go by the page that asked before it could).
    is MailError.SearchUnsupported,
    is MailError.Protocol,
    is MailError.DemoMode,
    is MailError.FolderChanged,
    is MailError.Abandoned,
    -> R.string.school_mail_error_generic
}

@Composable
fun ForgotMailPasswordLink(browserPreference: String) {
    val context = LocalContext.current
    TextButton(
        onClick = { openLink(context, WEBMAIL_URL, browserPreference) },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(stringResource(R.string.school_mail_forgot_password), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * The unread dot before a mail's sender, written in [senderFont]: 7dp at the default text size,
 * growing with the sender's text as the iPhone's does.
 */
@Composable
internal fun MailUnreadDot(senderFont: TextUnit) {
    Box(Modifier.size(unreadDotSize(LocalDensity.current, senderFont)).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
}

/** The dot's 7dp beside [senderFont] unscaled, in step with that text as [density] scales it. */
internal fun unreadDotSize(density: Density, senderFont: TextUnit): Dp =
    with(density) { senderFont.toDp() } * (UNREAD_DOT_DP / senderFont.value)

private const val UNREAD_DOT_DP = 7f
