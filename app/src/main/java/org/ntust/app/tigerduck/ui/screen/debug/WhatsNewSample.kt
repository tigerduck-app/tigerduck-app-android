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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewEffect
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewFlow
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewLanguage
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewPage
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewText
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewVisual

/**
 * A What's New flow with one page of every kind and a summary, so the sheet
 * can be exercised before any release registers real pages. Copy is written
 * in both What's New languages, as a real page's is, so the sample also shows
 * the language switch. Answers change nothing in the app; they are reported
 * through [onEvent].
 */
@Composable
fun rememberWhatsNewSampleFlow(onEvent: (String) -> Unit): WhatsNewFlow {
    val report by rememberUpdatedState(onEvent)
    val layout = remember { mutableStateOf("classic") }
    val switch = remember { mutableStateOf(false) }
    val language = WhatsNewLanguage.of(LocalConfiguration.current.locales[0].toLanguageTag())
    return remember(language) {
        WhatsNewFlow(
            pages = listOf(
                WhatsNewPage.Feature(
                    id = "sample-feature-icon",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Email, WhatsNewEffect.Bounce),
                    title = text("Feature page", "功能頁"),
                    body = text("An icon with a looping effect, a title and a few lines of text.", "一個帶循環動畫的圖示，加上標題與幾行說明。"),
                ),
                WhatsNewPage.Feature(
                    id = "sample-feature-demo",
                    visual = WhatsNewVisual.Custom { animate -> SampleInboxDemo(animate) },
                    title = text("Custom demo", "自訂示範"),
                    body = text("A hand-built mock of a screen in place of the icon.", "以手刻的畫面示意取代圖示。"),
                ),
                WhatsNewPage.OptIn(
                    id = "sample-opt-in",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Dashboard, WhatsNewEffect.Wiggle),
                    title = text("Opt in", "選擇加入"),
                    body = text("Offers a change. Turn On applies it, Not Now leaves things as they are.", "提供一項變更。「開啟」會套用，「暫時不要」則維持原狀。"),
                    confirmLabel = text("Turn On", "開啟"),
                    declineLabel = text("Not Now", "暫時不要"),
                    apply = { report("Opt-in page: applied.") },
                ),
                WhatsNewPage.Permission(
                    id = "sample-permission",
                    visual = WhatsNewVisual.Icon(Icons.Filled.Notifications, WhatsNewEffect.Pulse),
                    title = text("Ask for a permission", "請求權限"),
                    body = text("Turn On brings up the system notification prompt (Android 13+).", "「開啟通知」會跳出系統的通知權限提示（Android 13 以上）。"),
                    confirmLabel = text("Turn On Notifications", "開啟通知"),
                    declineLabel = text("Not Now", "暫時不要"),
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
                    title = text("Pick between looks", "選擇外觀"),
                    body = text("Selecting a card applies it right away.", "點選卡片後會立即套用。"),
                    options = listOf(
                        WhatsNewPage.Choice.Option(
                            id = "classic",
                            label = text("Classic", "經典"),
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
                            label = text("Recommended", "推薦"),
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
                    title = text("Toggle", "開關"),
                    body = text("A switch under the demo, applied as it's flipped.", "示範下方的開關，切換時立即套用。"),
                    label = text("Sample setting", "範例設定"),
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
                            // A custom page picks its own copy with the
                            // flow's language.
                            Text(
                                text("Custom page", "自訂頁面").resolve(context.language),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text(
                                    "Draws its own body and hides the standard Next button. " +
                                        "This one moves on from its own button.",
                                    "自行繪製內容並隱藏標準的「下一步」按鈕，由頁面自己的按鈕前往下一頁。",
                                ).resolve(context.language),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = context.advance) {
                                Text(text("Go to the summary", "前往摘要").resolve(context.language))
                            }
                        }
                    },
                ),
            ),
            // A real summary arrives already resolved from whatsnew.json;
            // the sample resolves its own the same way.
            summary = WhatsNewSummary(
                versionCode = 0,
                title = text("What's new in the sample", "範例的新功能").resolve(language),
                items = listOf(
                    WhatsNewSummaryItem(
                        text("School Mail", "校園信箱").resolve(language),
                        text("Send and receive your NTUST mail.", "收發臺科大信件。").resolve(language),
                        "mail",
                    ),
                    WhatsNewSummaryItem(
                        text("New bottom bar", "新的底部功能列").resolve(language),
                        text("A recommended set of tabs.", "一組推薦的項目。").resolve(language),
                        "layout",
                    ),
                    WhatsNewSummaryItem(
                        text("Unknown icon name", "未知的圖示名稱").resolve(language),
                        text("Falls back to a neutral glyph.", "改用中性的預設圖示。").resolve(language),
                        "nope",
                    ),
                    WhatsNewSummaryItem(
                        null,
                        text(
                            "A legacy plain-text highlight gets a dot.",
                            "舊格式的純文字重點會顯示圓點。",
                        ).resolve(language),
                        null,
                    ),
                ),
            ),
        )
    }
}

private fun text(en: String, zhHant: String) = WhatsNewText(en = en, zhHant = zhHant)

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
