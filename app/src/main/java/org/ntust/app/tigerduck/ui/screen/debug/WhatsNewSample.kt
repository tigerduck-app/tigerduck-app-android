package org.ntust.app.tigerduck.ui.screen.debug

import android.Manifest
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewEffect
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewFlow
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewPage
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewText
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewVisual

/**
 * A What's New flow with one page of every kind and a summary, so the sheet
 * can be exercised before any release registers real pages. Debug-only copy,
 * hardcoded English like the rest of this screen. Answers change nothing in
 * the app; they are reported through [onEvent].
 */
@Composable
fun rememberWhatsNewSampleFlow(onEvent: (String) -> Unit): WhatsNewFlow {
    val report by rememberUpdatedState(onEvent)
    val layout = remember { mutableStateOf("classic") }
    val switch = remember { mutableStateOf(false) }
    return remember {
        WhatsNewFlow(
            pages = listOf(
                WhatsNewPage.Feature(
                    id = "sample-feature-icon",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Email, WhatsNewEffect.Bounce),
                    title = raw("Feature page"),
                    body = raw("An icon with a looping effect, a title and a few lines of text."),
                ),
                WhatsNewPage.Feature(
                    id = "sample-feature-demo",
                    visual = WhatsNewVisual.Custom { animate -> SampleInboxDemo(animate) },
                    title = raw("Custom demo"),
                    body = raw("A hand-built mock of a screen in place of the icon."),
                ),
                WhatsNewPage.OptIn(
                    id = "sample-opt-in",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Dashboard, WhatsNewEffect.Wiggle),
                    title = raw("Opt in"),
                    body = raw("Offers a change. Turn On applies it, Not Now leaves things as they are."),
                    confirmLabel = raw("Turn On"),
                    declineLabel = raw("Not Now"),
                    apply = { report("Opt-in page: applied.") },
                ),
                WhatsNewPage.Permission(
                    id = "sample-permission",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Notifications, WhatsNewEffect.Pulse),
                    title = raw("Ask for a permission"),
                    body = raw("Turn On brings up the system notification prompt (Android 13+)."),
                    confirmLabel = raw("Turn On Notifications"),
                    declineLabel = raw("Not Now"),
                    permissions = listOfNotNull(
                        Manifest.permission.POST_NOTIFICATIONS
                            .takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
                    ),
                    minSdk = Build.VERSION_CODES.TIRAMISU,
                    onResult = { granted -> report("Permission page: granted = $granted.") },
                ),
                WhatsNewPage.Choice(
                    id = "sample-choice",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Sync, WhatsNewEffect.Rotate),
                    title = raw("Pick between looks"),
                    body = raw("Selecting a card applies it right away."),
                    options = listOf(
                        WhatsNewPage.Choice.Option(
                            id = "classic",
                            label = raw("Classic"),
                            preview = {
                                SampleBottomBar(
                                    listOf(
                                        Icons.Filled.Home,
                                        Icons.Filled.TableChart,
                                        Icons.Filled.CalendarMonth,
                                    ),
                                )
                            },
                        ),
                        WhatsNewPage.Choice.Option(
                            id = "recommended",
                            label = raw("Recommended"),
                            preview = {
                                SampleBottomBar(
                                    listOf(
                                        Icons.Filled.Home,
                                        Icons.Filled.TableChart,
                                        Icons.Filled.Email,
                                    ),
                                )
                            },
                        ),
                    ),
                    current = { layout.value },
                    select = {
                        layout.value = it
                        report("Choice page: picked $it.")
                    },
                ),
                WhatsNewPage.Toggle(
                    id = "sample-toggle",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Palette, WhatsNewEffect.Breathe),
                    title = raw("Toggle"),
                    body = raw("A switch under the demo, applied as it's flipped."),
                    label = raw("Sample setting"),
                    get = { switch.value },
                    set = {
                        switch.value = it
                        report("Toggle page: $it.")
                    },
                ),
                WhatsNewPage.Custom(
                    id = "sample-custom",
                    showsNextButton = false,
                    content = { context ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        ) {
                            Text(
                                "Custom page",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                "Draws its own body and hides the standard Next button. " +
                                    "This one moves on from its own button.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = context.advance) { Text("Go to the summary") }
                        }
                    },
                ),
            ),
            summary = WhatsNewSummary(
                versionCode = 0,
                title = "What's new in the sample",
                items = listOf(
                    WhatsNewSummaryItem("School Mail", "Send and receive your NTUST mail.", "mail"),
                    WhatsNewSummaryItem("New bottom bar", "A recommended set of tabs.", "layout"),
                    WhatsNewSummaryItem("Unknown icon name", "Falls back to a neutral glyph.", "nope"),
                    WhatsNewSummaryItem(null, "A legacy plain-text highlight gets a dot.", null),
                ),
            ),
        )
    }
}

private fun raw(text: String) = WhatsNewText.Raw(text)

@Composable
private fun SampleInboxDemo(animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "sampleInbox")
    val arrival by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "arrival",
    )
    val progress = if (animate) arrival else 1f
    Column(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The newest row slides in from above, then the rest settle under it.
        SampleRow(
            highlighted = true,
            modifier = Modifier.graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * -24.dp.toPx()
            },
        )
        SampleRow(highlighted = false)
        SampleRow(highlighted = false)
    }
}

@Composable
private fun SampleRow(highlighted: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (highlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
            )
        }
    }
}

@Composable
private fun SampleBottomBar(icons: List<ImageVector>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        icons.forEach {
            Icon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
