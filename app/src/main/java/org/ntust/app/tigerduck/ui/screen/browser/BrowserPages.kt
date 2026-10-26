package org.ntust.app.tigerduck.ui.screen.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.util.replaceIosArg

@Composable
private fun StatusPage(icon: ImageVector, title: String, message: String, modifier: Modifier, buttons: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(64.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Column(Modifier.widthIn(max = 280.dp).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons()
        }
    }
}

/** In place of a page that did not load: try again, or the phone's browser. A bad certificate offers neither way past it. */
@Composable
internal fun BrowserErrorPage(error: BrowserError, actions: BrowserActions, modifier: Modifier = Modifier) {
    val host = error.host.orEmpty()
    when (error) {
        is BrowserError.Unreachable -> StatusPage(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.browser_error_title),
            message = stringResource(R.string.browser_error_message).replaceIosArg(1, host),
            modifier = modifier,
        ) {
            Button(onClick = actions::retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_retry)) }
            OutlinedButton(onClick = actions::openCurrentExternal, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.browser_open_external)) }
        }
        is BrowserError.BadCertificate -> StatusPage(
            icon = Icons.Filled.GppBad,
            title = stringResource(R.string.browser_error_title),
            message = stringResource(R.string.browser_error_certificate).replaceIosArg(1, host),
            modifier = modifier,
        ) {
            Button(onClick = actions::close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.browser_close)) }
        }
    }
}

/**
 * The browser's sign-in screen, in place of the site's own sign-in page: it says so while the
 * browser signs in again by itself, and offers Retry once that failed.
 */
@Composable
internal fun BrowserSignInPage(signIn: SignInState, actions: BrowserActions, modifier: Modifier = Modifier) {
    StatusPage(
        icon = Icons.Filled.Person,
        title = stringResource(R.string.browser_sign_in_title).replaceIosArg(1, signIn.siteName),
        message = stringResource(if (signIn.working) R.string.browser_sign_in_working else R.string.browser_sign_in_failed_short),
        modifier = modifier,
    ) {
        if (signIn.working) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            Button(onClick = actions::retrySignIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
