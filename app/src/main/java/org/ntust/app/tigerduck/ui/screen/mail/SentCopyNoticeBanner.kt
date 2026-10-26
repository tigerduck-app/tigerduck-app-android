package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.mail.SentCopyNotice

/** The notice's words: one of the three reasons, the failed one with what went wrong under it. */
@Composable
internal fun sentCopyNoticeText(notice: SentCopyNotice): String = when (notice) {
    SentCopyNotice.NoFolder -> stringResource(R.string.school_mail_sent_copy_no_folder)
    is SentCopyNotice.Failed ->
        stringResource(R.string.school_mail_sent_copy_failed) + "\n" + stringResource(notice.error.messageRes())
    SentCopyNotice.Unknown -> stringResource(R.string.school_mail_sent_copy_unknown)
}

/**
 * A mail went out, but its copy did not reach the sent folder. A card like the sign-in-failed
 * banner, not the load-failed state -- nothing failed to load -- and it stays until "Got it",
 * since it is about one send and asks nothing urgent. A polite live region, so TalkBack says it.
 */
@Composable
internal fun SentCopyNoticeBanner(notice: SentCopyNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        // Polite: it appears as compose closes, with nothing focused on it, so TalkBack reads it
        // once the screen has settled rather than leaving it unannounced.
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Color(0xFFFF9500))
            Spacer(Modifier.width(12.dp))
            Text(
                sentCopyNoticeText(notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_acknowledged)) }
        }
    }
}
