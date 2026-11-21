package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

/** The row's title, as the catalog names it. */
@Composable
internal fun SettingsRow.label(): String = stringResource(title)

/** What the row's toast says while [state] closes its gate; null while it takes input. */
@Composable
internal fun SettingsRow.closedText(state: SettingsState): String? = closedReason(state)?.let { stringResource(it) }

/**
 * The tap of a row its gate closes: the row keeps its value, dimmed, and a tap says what it needs
 * in a toast rather than changing anything. TalkBack offers the same as the row's action, "double
 * tap to" the reason, which is where Android reads a hint; iOS reads it as the hint of a dimmed
 * control. TalkBack reads the toast out too, as VoiceOver hears iOS's announcement.
 */
@Composable
internal fun Modifier.explainsClosed(reason: String, role: Role? = null): Modifier {
    val context = LocalContext.current
    return clickable(onClickLabel = reason, role = role) { showClosedReason(context, reason) }
}

internal fun showClosedReason(context: Context, reason: String) {
    Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
}
