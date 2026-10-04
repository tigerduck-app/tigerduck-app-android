package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.navigation.icon

/**
 * "Reset your bottom bar?" — offers the tab editor's Reset defaults to
 * anyone whose bottom bar doesn't already show the default tabs. Confirm
 * writes [AppFeature.defaultTabs] through [AppState.configuredTabs], the
 * same path the editor's reset takes.
 */
internal fun resetBottomBarPage(appState: AppState) = WhatsNewPage.OptIn(
    id = "reset-bottom-bar",
    visual = WhatsNewVisual.Custom { animate -> BottomBarResetDemo(appState, animate) },
    title = WhatsNewText(
        en = "Reset your bottom bar?",
        zhHant = "要恢復預設的底部功能列嗎？",
    ),
    body = WhatsNewText(
        en = "Go back to the default tabs. You can customize them again anytime in Settings.",
        zhHant = "回到預設的項目，之後隨時可以在設定中重新自訂。",
    ),
    confirmLabel = WhatsNewText(en = "Reset Defaults", zhHant = "恢復預設"),
    declineLabel = WhatsNewText(en = "Keep Mine", zhHant = "保留目前設定"),
    apply = { appState.configuredTabs = AppFeature.defaultTabs },
    isApplicable = {
        bottomBarDiffersFromDefault(appState.configuredTabs, appState.libraryFeatureEnabled)
    },
)

/**
 * The tabs the bottom bar actually shows for [configured]: library tabs
 * drop out while the library opt-in is off, as in `AppNavigation`.
 */
internal fun visibleBottomBarTabs(
    configured: List<AppFeature>,
    libraryEnabled: Boolean,
): List<AppFeature> = configured.filter { !it.isLibraryRelated || libraryEnabled }

/**
 * Whether resetting would change what the user sees. Compares the visible
 * bar, not the stored list, so a library tab hidden by the opt-in doesn't
 * make an otherwise default bar look customized.
 */
internal fun bottomBarDiffersFromDefault(
    configured: List<AppFeature>,
    libraryEnabled: Boolean,
): Boolean = visibleBottomBarTabs(configured, libraryEnabled) != AppFeature.defaultTabs

/**
 * Whether the demo loops between the user's bar and the default one. Not
 * with animations off, and not when the two are the same bar — a replay for
 * someone on the defaults, or Back after confirming the reset — where a loop
 * would only swap a bar for itself.
 */
internal fun resetDemoLoops(visible: List<AppFeature>, animate: Boolean): Boolean =
    animate && visible != AppFeature.defaultTabs

/**
 * A mock bottom bar showing the user's tabs, then the default ones, and
 * back, on a loop. With animations off, or when the user's bar already is
 * the default one, it holds still on the default bar — the outcome the page
 * offers.
 */
@Composable
private fun BottomBarResetDemo(appState: AppState, animate: Boolean) {
    // Captured once, so confirming mid-loop doesn't swap the "before" bar.
    val current = remember {
        visibleBottomBarTabs(appState.configuredTabs, appState.libraryFeatureEnabled)
    }
    val loops = resetDemoLoops(current, animate)
    var showDefault by remember { mutableStateOf(!loops) }
    if (loops) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(DEMO_HOLD_MS)
                showDefault = !showDefault
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = showDefault,
            transitionSpec = {
                (fadeIn(tween(DEMO_SWAP_MS)) + slideInVertically(tween(DEMO_SWAP_MS)) { it / 3 })
                    .togetherWith(
                        fadeOut(tween(DEMO_SWAP_MS)) +
                            slideOutVertically(tween(DEMO_SWAP_MS)) { -it / 3 },
                    )
            },
            label = "bottomBarReset",
        ) { isDefault ->
            MockBottomBar(if (isDefault) AppFeature.defaultTabs else current)
        }
    }
}

/** One row of tab items styled like the app's NavigationBar, first tab selected. */
@Composable
private fun MockBottomBar(tabs: List<AppFeature>) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        (tabs + AppFeature.MORE).forEachIndexed { index, feature ->
            val selected = index == 0
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        )
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Icon(
                        feature.icon,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    stringResource(feature.shortDisplayNameRes),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val DEMO_HOLD_MS = 1600L
private const val DEMO_SWAP_MS = 350
