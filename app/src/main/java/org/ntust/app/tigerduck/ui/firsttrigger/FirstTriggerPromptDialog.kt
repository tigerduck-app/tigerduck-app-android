package org.ntust.app.tigerduck.ui.firsttrigger

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.haptics.HapticScenario

/**
 * Root-level host for the single pending first-trigger prompt. Apply once near
 * the app root; it observes [FirstTriggerPromptController.pending] and renders
 * the dialog whenever a prompt is queued.
 */
@Composable
fun FirstTriggerPromptHost(controller: FirstTriggerPromptController) {
    val pending by controller.pending.collectAsStateWithLifecycle()
    pending?.let { p ->
        FirstTriggerPromptDialog(
            key = p.key,
            onKeep = { controller.finish(accept = true) },
            onTurnOff = { controller.finish(accept = false) },
        )
    }
}

/** Per-key string + animation bundle for a first-trigger prompt. */
private data class FirstTriggerStrings(
    val titleRes: Int,
    val messageRes: Int,
    val keepRes: Int,
    val turnOffRes: Int,
    val accessibilityRes: Int,
)

private fun stringsFor(key: FirstTriggerPromptKey): FirstTriggerStrings = when (key) {
    FirstTriggerPromptKey.FLIP_TO_LIBRARY -> FirstTriggerStrings(
        titleRes = R.string.first_trigger_flip_to_library_title,
        messageRes = R.string.first_trigger_flip_to_library_message,
        keepRes = R.string.first_trigger_flip_to_library_keep,
        turnOffRes = R.string.first_trigger_flip_to_library_turn_off,
        accessibilityRes = R.string.first_trigger_phone_flip_accessibility,
    )
}

/**
 * The flip-to-library opt-in, built on the shared [TigerDuckDialog]: a
 * non-dismissable popup (the user MUST tap Keep or Turn off) whose hero phone
 * glyph flips around its long edge — the motion of laying the phone face-down —
 * to anchor the prompt to the gesture that fired it. Honors the system "remove
 * animations" setting.
 */
@Composable
private fun FirstTriggerPromptDialog(
    key: FirstTriggerPromptKey,
    onKeep: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val context = LocalContext.current
    val strings = stringsFor(key)

    // Anchor the prompt to the flip gesture with the same haptic the steady-state
    // navigation uses, so the prompt is felt as well as seen.
    LaunchedEffect(Unit) { Haptics.perform(context, HapticScenario.FlipToLibrary) }

    TigerDuckDialog(
        onDismissRequest = {},
        dismissable = false,
        title = stringResource(strings.titleRes),
        message = stringResource(strings.messageRes),
        confirmText = stringResource(strings.keepRes),
        onConfirm = onKeep,
        dismissText = stringResource(strings.turnOffRes),
        onDismiss = onTurnOff,
        icon = { PhoneFlipIcon(context, stringResource(strings.accessibilityRes)) },
    )
}

@Composable
private fun PhoneFlipIcon(context: Context, contentDescription: String) {
    val reduceMotion = animatorDurationScale(context) == 0f
    val transition = rememberInfiniteTransition(label = "phoneFlip")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phoneFlipAngle",
    )
    Icon(
        imageVector = Icons.Filled.Smartphone,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(88.dp)
            .graphicsLayer {
                rotationY = if (reduceMotion) 0f else angle
                cameraDistance = 12f * density
            },
    )
}

/**
 * The system "Remove animations" / Developer-options animator scale. Zero means
 * the user has asked for no animations, so the hero is shown static.
 */
private fun animatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
