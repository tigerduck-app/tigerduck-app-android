// Dialogs raised from Settings.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics

@Composable
internal fun LibraryWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(5) }
    var confirmEnabled by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(Unit) {
        Haptics.perform(
            view.context,
            HapticScenario.LibraryWarning,
        )

        for (i in 4 downTo 0) {
            delay(1000)
            countdown = i
        }
        confirmEnabled = true
    }

    val infiniteTransition = rememberInfiniteTransition(label = "flash")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flash_alpha"
    )

    TigerDuckDialog(
        onDismissRequest = onDismiss,
        icon = {
            // Flashing red warning icon + title to signal the destructive
            // weight of enabling the library feature. Kept in the icon slot
            // (rather than the plain `title`) so both can pulse together.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.Red.copy(alpha = flashAlpha),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    stringResource(R.string.settings_library_warning_title),
                    color = Color.Red.copy(alpha = flashAlpha),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        },
        message = stringResource(R.string.settings_library_warning_message),
        confirmText = if (confirmEnabled) stringResource(R.string.settings_library_warning_confirm)
        else stringResource(R.string.settings_library_warning_confirm_countdown, countdown),
        onConfirm = onConfirm,
        confirmEnabled = confirmEnabled,
        confirmColors = ButtonDefaults.buttonColors(
            containerColor = Color.Red,
            disabledContainerColor = Color.Red.copy(alpha = 0.35f),
        ),
        dismissText = stringResource(R.string.settings_library_warning_dismiss),
        onDismiss = onDismiss,
    )
}
