// Row primitives shared across Settings and its sub-screens — toggle,
// picker, link, and the account row with its width measurement. These
// are the vocabulary every settings surface is written in, which is why
// they are `internal` rather than private to the main screen.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.tigerDuckSwitchColors

internal val SettingRowHeight = 56.dp
internal val SubSettingsBarHeight = 48.dp
/**
 * About section row that swaps a chevron for a spinner while a manual
 * "Check for updates" call is in flight. Disabled while checking so a
 * stuck-finger user can't fire a second concurrent query.
 */
@Composable
internal fun CheckForUpdatesRow(isChecking: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(enabled = !isChecking) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_check_for_updates),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
/**
 * Width that fits whichever of "Sign in" / "Sign out" is wider, so the
 * two buttons line up when both are visible (one row logged-in, one not).
 * Adds the M3 button horizontal content padding (24.dp each side).
 */
@Composable
internal fun rememberAccountButtonMinWidth(): Dp {
    val loginText = stringResource(R.string.action_sign_in)
    val logoutText = stringResource(R.string.action_sign_out)
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidthPx = remember(loginText, logoutText, style) {
        maxOf(
            measurer.measure(loginText, style).size.width,
            measurer.measure(logoutText, style).size.width,
        )
    }
    return with(density) { labelWidthPx.toDp() } + 48.dp
}
@Composable
internal fun AccountRow(
    title: String,
    isLoggedIn: Boolean,
    subtitle: String?,
    isLoggingIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    actionMinWidth: Dp,
    highlight: Boolean = false,
    onHighlightConsumed: () -> Unit = {},
) {
    // Two-pulse attention flash when an off-screen surface (e.g. a
    // signed-out empty state) deep-links here to surface the "Sign in"
    // action. Uses keyframes so the row briefly tints with the accent
    // container, fades, tints again, then settles back — enough motion
    // to catch the eye without being noisy.
    val highlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        highlightAlpha.snapTo(0f)
        highlightAlpha.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 2200
                0f at 0
                1f at 250
                0f at 900
                1f at 1200
                0f at 1900
            },
        )
        onHighlightConsumed()
    }
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
        .copy(alpha = 0.55f * highlightAlpha.value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlightColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isLoggedIn) Color(0xFF34C759) else Color(0xFFFF3B30))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        }
        if (isLoggingIn) {
            Box(
                modifier = Modifier
                    .widthIn(min = actionMinWidth)
                    .height(ButtonDefaults.MinHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else if (isLoggedIn) {
            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.widthIn(min = actionMinWidth),
            ) { Text(stringResource(R.string.action_sign_out)) }
        } else {
            Button(
                onClick = onLogin,
                modifier = Modifier.widthIn(min = actionMinWidth),
            ) { Text(stringResource(R.string.action_sign_in)) }
        }
    }
}
@Composable
internal fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
    }
}
@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else ContentAlpha.DISABLED,
                ),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) ContentAlpha.SECONDARY else ContentAlpha.DISABLED,
                    ),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = tigerDuckSwitchColors(),
        )
    }
}
@Composable
internal fun SettingsPickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // `heightIn` (not `height`) so a long label that wraps to two
            // lines can grow the row instead of getting its descenders
            // clipped — e.g. Mandarin labels like "中文教室名稱顯示方式"
            // are tall enough to need the extra room.
            .heightIn(min = SettingRowHeight)
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(12.dp)
            ) {
                options.forEach { (key, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        },
                        leadingIcon = {
                            RadioButton(
                                selected = selectedKey == key,
                                onClick = null
                            )
                        }
                    )
                }
            }
        }
    }
}
@Composable
internal fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp)
        )
    }
}
@Composable
internal fun SettingsLinkRowWithValue(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp)
        )
    }
}
