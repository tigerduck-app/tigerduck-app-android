package org.ntust.app.tigerduck.ui.screen.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R

/**
 * The whole address of the page, its connection and its sign-in. Under a refused certificate, the
 * way to open the page at the student's risk, where the WebView can ([BrowserPageState.riskRefusal]).
 */
@Composable
internal fun BrowserAddressPage(
    state: BrowserPageState,
    url: String,
    actions: BrowserActions,
    onCertificate: () -> Unit,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PageTitle(stringResource(R.string.browser_address_title), onBack)
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                SelectionContainer {
                    Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            SideBySideOrStacked(spacing = 8.dp) {
                // Less padding than a button's own, so the three fit side by side at the usual sizes.
                val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                Button(onClick = { actions.copy(url) }, contentPadding = padding) { Text(stringResource(R.string.browser_copy_short), maxLines = 1) }
                OutlinedButton(onClick = { actions.share(url) }, contentPadding = padding) { Text(stringResource(R.string.browser_share_short), maxLines = 1) }
                OutlinedButton(onClick = { onDismiss(); actions.openExternal(url) }, contentPadding = padding) {
                    Text(stringResource(R.string.browser_open_external_short), maxLines = 1)
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column { ConnectionRows(state, actions, onCertificate, onDismiss) }
            }
        }
    }
}

/**
 * The connection, the sign-in and the way to the certificate. A browser opened at risk calls no
 * page's connection secure, nor anything else: it shows none.
 */
@Composable
private fun ConnectionRows(state: BrowserPageState, actions: BrowserActions, onCertificate: () -> Unit, onDismiss: () -> Unit) {
    when {
        // Encrypted, but to a certificate the browser refused: neither secure nor unencrypted.
        state.certificateRefused -> InfoRow(Icons.Filled.GppBad, stringResource(R.string.browser_certificate_invalid), detail = null)
        state.atRisk -> Unit
        state.isHttps -> InfoRow(
            Icons.Filled.Lock,
            stringResource(R.string.browser_connection_secure),
            stringResource(R.string.browser_connection_secure_detail),
        )
        else -> InfoRow(Icons.Filled.LockOpen, stringResource(R.string.browser_connection_insecure), detail = null)
    }
    if (state.riskRefusal != null) {
        HorizontalDivider()
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
            MenuRow(Icons.Filled.LockOpen, stringResource(R.string.browser_certificate_accept_risk), horizontalPadding = 16.dp, iconGap = 12.dp) {
                onDismiss()
                actions.acceptRisk()
            }
        }
    }
    if (state.signedIn) {
        HorizontalDivider()
        InfoRow(icon = Icons.Filled.Person, title = stringResource(R.string.browser_signed_in), detail = null)
    }
    if (state.certificateRefused || !state.atRisk || state.signedIn) HorizontalDivider()
    MenuRow(
        Icons.Filled.VerifiedUser,
        stringResource(R.string.browser_certificate),
        enabled = state.shownCertificate != null,
        showArrow = true,
        // InfoRow's, so its icon and text line up with the rows above.
        horizontalPadding = 16.dp,
        iconGap = 12.dp,
        onClick = onCertificate,
    )
}

/**
 * [content]'s buttons side by side in equal widths when each one's label fits its share, else one
 * under another at full width: no label is cut, whatever the language or the text size.
 */
@Composable
private fun SideBySideOrStacked(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val share = (width - gap * (measurables.size - 1)) / measurables.size
        if (measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(share)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { i, placeable -> placeable.placeRelative(i * (share + gap), (height - placeable.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            layout(width, placeables.sumOf { it.height } + gap * (placeables.size - 1)) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}
