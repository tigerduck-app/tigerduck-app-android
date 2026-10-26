package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.core.strings.R

/**
 * "Want to see the new features page again?" — the first of a release's
 * pages: Settings › What's new shows them again any time, which the demo
 * walks to, from More.
 */
internal fun replayPage() = WhatsNewPage.Feature(
    id = "whats-new-replay",
    // About the sheet, so it opens a run that also replays an older release's pages.
    leadsTheRun = true,
    visual = WhatsNewVisual.Custom { animate -> ReplayDemo(animate) },
    title = WhatsNewText(
        en = "Want to see the new features page again?",
        zhHant = "想再次看到以下的新功能描述？",
    ),
    body = WhatsNewText(
        en = "Go to Settings › What’s new, any time.",
        zhHant = "隨時到「設定 › 新功能說明」就能再看一次。",
    ),
)

private val replayDemoDescription = WhatsNewText(
    en = "Demo: from More, Settings opens, scrolls down to What’s new, and the new features show again.",
    zhHant = "示範：從「更多」打開設定，往下滑到「新功能說明」，新功能說明就會再出現一次。",
)

private const val REPLAY_LOOP_MILLIS = 3_500

@Composable
private fun ReplayDemo(animate: Boolean) {
    PathDemo(
        description = replayDemoDescription.resolve(),
        animate = animate,
        featureMillis = REPLAY_LOOP_MILLIS,
        start = PathStart.SettingsGear,
        stops = listOf(
            PathStop(
                title = stringResource(R.string.feature_settings),
                section = stringResource(R.string.settings_section_about),
                rows = listOf(
                    stringResource(R.string.settings_check_server_status),
                    stringResource(R.string.settings_whats_new),
                    stringResource(R.string.settings_official_website),
                ),
                tapped = 1,
            ),
        ),
        // Still: the sheet up.
        featureStill = 0.6f,
    ) { t ->
        // The sheet rises over Settings, and stays.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = span(t(), 0f, 0.05f) }.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)))
        WhatsNewSheetMock(
            Modifier.offset { IntOffset(0, lerp(MockScreenHeight, 24.dp, span(t(), 0f, 0.3f)).roundToPx()) },
        )
    }
}

/** What's New as it opens: page dots, its sparkles, a title and a button. */
@Composable
private fun WhatsNewSheetMock(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .height(MockScreenHeight - 24.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(colors.surface)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { index ->
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (index == 0) colors.primary else colors.outlineVariant))
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(56.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
        }
        TextLine(0.6f, height = 8.dp, strong = true)
        TextLine(0.8f)
        TextLine(0.7f)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(50)).background(colors.primary))
    }
}
