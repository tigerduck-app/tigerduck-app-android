package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.first
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.rememberHostWindow

/**
 * What's New over the whole screen: [WhatsNewFlow.steps] one at a time, in a window of its own
 * that slides up as it opens and back down as it closes. The button on the last page closes it,
 * the only way out of a flow an update shows; one the person opened again themselves, [skippable],
 * also closes on the system back gesture from any page. [onDismiss] runs once it has slid away,
 * once, so the caller records the flow as seen in one place.
 */
@Composable
fun WhatsNewScreen(flow: WhatsNewFlow, onDismiss: () -> Unit, skippable: Boolean = false) {
    val context = LocalContext.current
    // A Binder call, so read once rather than per recomposition.
    val animate = remember(context) { animationsEnabled(context) }
    // Read here, in the activity's composition, from the same app-locale-aware configuration the
    // summary was looked up with, so page copy and the summary always agree on the language.
    val language = WhatsNewLanguage.of(LocalConfiguration.current.locales[0].toLanguageTag())
    // One that comes back with the activity, as for a change of dark mode, is up already, and
    // one closed on its way down is gone at once.
    var opened by rememberSaveable { mutableStateOf(false) }
    var closing by rememberSaveable { mutableStateOf(false) }
    val shown = remember { MutableTransitionState(opened && !closing) }
    val dismissed by rememberUpdatedState(onDismiss)
    LaunchedEffect(shown) {
        if (!closing) {
            shown.targetState = true
            opened = true
        }
        snapshotFlow { !shown.targetState && !shown.currentState && shown.isIdle }.first { it }
        dismissed()
    }
    // A second close on the way down changes nothing, so a double tap can't dismiss twice.
    val close: () -> Unit = {
        closing = true
        shown.targetState = false
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = skippable,
            dismissOnClickOutside = false,
        ),
    ) {
        SystemBarIcons(onLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f)
        AnimatedVisibility(
            visibleState = shown,
            enter = if (animate) slideInVertically { it } else EnterTransition.None,
            exit = if (animate) slideOutVertically { it } else ExitTransition.None,
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalWhatsNewLanguage provides language) {
                    WhatsNewFlowContent(
                        flow = flow,
                        animate = animate,
                        onFinish = close,
                        backGoesBack = !skippable,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
    }
}

/** The back arrow from the second page on, and the page dots. */
@Composable
internal fun WhatsNewTopBar(flow: WhatsNewFlow, current: Int, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
    ) {
        if (current > 0) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        }
        if (flow.stepCount > 1) {
            val language = LocalWhatsNewLanguage.current
            PageDots(
                count = flow.stepCount,
                current = current,
                description = pagePositionDescription(
                    step = current,
                    count = flow.stepCount,
                    title = stepTitle(flow, current, language),
                    language = language,
                ),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Dark status and navigation bar icons over a light page, light ones over a dark page: the page
 * draws under both bars, in a window whose bars nothing else sets.
 */
@Composable
private fun SystemBarIcons(onLight: Boolean) {
    val window = rememberHostWindow() ?: return
    SideEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) window.clearBarColors()
        WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = onLight
            isAppearanceLightNavigationBars = onLight
        }
    }
}

/**
 * Clear bars, so the page shows through them: Android 15 makes them so itself, but before it a
 * dialog's bars take the theme's color. With three-button navigation the system still keeps the
 * buttons readable over the page.
 */
@Suppress("DEPRECATION")
private fun Window.clearBarColors() {
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
}

/** The heading of step [step]: a page's title, or the summary's. */
private fun stepTitle(flow: WhatsNewFlow, step: Int, language: WhatsNewLanguage): String? {
    val page = when (val shown = flow.steps.getOrNull(step)) {
        is WhatsNewStep.Summary -> return shown.summary.title
        is WhatsNewStep.Page -> shown.page
        null -> return null
    }
    val title = when (page) {
        is WhatsNewPage.Feature -> page.title
        is WhatsNewPage.OptIn -> page.instead()?.title ?: page.title
        is WhatsNewPage.Permission -> page.title
        is WhatsNewPage.Choice -> page.title
        is WhatsNewPage.Toggle -> page.title
        is WhatsNewPage.Custom -> null
    }
    return title?.resolve(language)
}

/**
 * False when the system "Remove animations" / animator duration scale is 0:
 * the screen appears and goes without sliding, the icon effects stop and page
 * changes fade instead of sliding.
 */
private fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) != 0f
