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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog

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
            onAccept = { controller.finish(accept = true) },
            onDecline = { controller.finish(accept = false) },
        )
    }
}

/**
 * Whether the [pending] first-trigger prompt may show now: not over What's New
 * or the update prompt, which it waits for rather than stacking on, as on the
 * iPhone. The flip-to-library one also waits while the Library page shows,
 * where it would cover the code: a flip there does not ask, but the debug
 * Triggers screen's replay can come due on it. Held, it stays pending, and
 * shows once the page is left.
 */
internal fun firstTriggerPromptMayShow(
    whatsNewShowing: Boolean,
    updatePromptShowing: Boolean,
    pending: FirstTriggerPromptKey?,
    libraryPageShown: Boolean,
): Boolean =
    !whatsNewShowing && !updatePromptShowing &&
        !(pending == FirstTriggerPromptKey.FLIP_TO_LIBRARY && libraryPageShown)

/** Per-key string + animation bundle for a first-trigger prompt. */
internal data class FirstTriggerStrings(
    val titleRes: Int,
    val messageRes: Int,
    val acceptRes: Int,
    val declineRes: Int,
    val accessibilityRes: Int,
)

/**
 * The flip-to-library question asks on a flip, in the words of its switch in
 * Settings, whether to turn the gesture on.
 */
internal fun stringsFor(key: FirstTriggerPromptKey): FirstTriggerStrings = when (key) {
    FirstTriggerPromptKey.FLIP_TO_LIBRARY -> FirstTriggerStrings(
        titleRes = R.string.first_trigger_flip_to_library_title,
        messageRes = R.string.settings_flip_to_library_summary,
        acceptRes = R.string.first_trigger_flip_to_library_turn_on,
        declineRes = R.string.permission_warning_action_later,
        accessibilityRes = R.string.first_trigger_phone_flip_accessibility,
    )
}

/**
 * The flip-to-library offer, built on the shared [TigerDuckDialog]: a
 * non-dismissable popup (the user MUST tap one of its two buttons) whose hero
 * phone glyph flips around its long edge — the motion of laying the phone
 * face-down — to show the gesture it offers. Honors the system "remove
 * animations" setting.
 */
@Composable
private fun FirstTriggerPromptDialog(
    key: FirstTriggerPromptKey,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    val strings = stringsFor(key)

    TigerDuckDialog(
        onDismissRequest = {},
        dismissable = false,
        title = stringResource(strings.titleRes),
        message = stringResource(strings.messageRes),
        confirmText = stringResource(strings.acceptRes),
        onConfirm = onAccept,
        dismissText = stringResource(strings.declineRes),
        onDismiss = onDecline,
        icon = { PhoneFlipIcon(context, stringResource(strings.accessibilityRes)) },
    )
}

@Composable
private fun PhoneFlipIcon(context: Context, contentDescription: String) {
    // Cache the Settings.Global read: it's a Binder/ContentProvider call, and
    // the infinite-transition below drives a recomposition every frame while
    // the dialog is visible. Without `remember`, every recomposition re-issues
    // the IPC.
    val reduceMotion = remember(context) { animatorDurationScale(context) == 0f }
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
