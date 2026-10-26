package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.navigation.icon

/**
 * "Your library pass": the library's virtual pass, opened from the bottom bar
 * or from More. Only for someone not signed in to the library: whoever is has
 * found it.
 */
internal fun libraryPage(isSignedIn: () -> Boolean) = WhatsNewPage.Feature(
    id = "library",
    visual = WhatsNewVisual.Custom { animate -> LibraryDemo(animate) },
    title = WhatsNewText(
        en = "Your library pass",
        zhHant = "圖書館入館證",
    ),
    body = WhatsNewText(
        en = "Sign in with your library account and your phone becomes your virtual pass. Open it from the bottom bar or from More.",
        zhHant = "登入圖書館帳號後，手機就是虛擬入館證。從底部功能列或「更多」都能打開。",
    ),
    isApplicable = { !isSignedIn() },
)

/**
 * "Add Library to the bottom bar?" — after [libraryPage], for the same
 * people, when the bar has room and no Library: Library goes last, left of
 * More. Confirm stores the new bar through [setTabs]: [AppState.configuredTabs],
 * the same path the tab editor takes.
 */
internal fun libraryBottomBarPage(
    isSignedIn: () -> Boolean,
    tabs: () -> List<AppFeature>,
    setTabs: (List<AppFeature>) -> Unit,
) = WhatsNewPage.OptIn(
    id = "library-bottom-bar",
    visual = WhatsNewVisual.Custom { LibraryBottomBarDemo(tabs) },
    title = WhatsNewText(
        en = "Add Library to the bottom bar?",
        zhHant = "要把圖書館加到底部功能列嗎？",
    ),
    body = WhatsNewText(
        en = "It goes left of More, so your pass is one tap away. You can customize the bar again anytime in Settings.",
        zhHant = "會放在「更多」左邊，入館證一點就到。之後也隨時可以在設定中重新自訂。",
    ),
    confirmLabel = WhatsNewText(en = "Add Library", zhHant = "加入圖書館"),
    declineLabel = WhatsNewText(en = "Keep Mine", zhHant = "保留目前設定"),
    apply = { tabsAddingLibrary(tabs())?.let(setTabs) },
    isApplicable = { !isSignedIn() && tabsAddingLibrary(tabs()) != null },
    // Answering 2.3.0's Mail question earlier in the same run can fill the bar.
    instead = { if (AppFeature.LIBRARY !in tabs() && tabsAddingLibrary(tabs()) == null) libraryInMorePage(tabs) else null },
)

/** In place of [libraryBottomBarPage] once the bar has filled up: where Library is instead. */
private fun libraryInMorePage(tabs: () -> List<AppFeature>) = WhatsNewPage.Feature(
    id = "library-in-more",
    visual = WhatsNewVisual.Custom {
        val current = remember { tabs() }
        BottomBarChangeDemo(before = current, after = current)
    },
    title = WhatsNewText(
        en = "Library is in More",
        zhHant = "圖書館在「更多」裡",
    ),
    body = WhatsNewText(
        en = "Your bottom bar is full. Open Library from More, or give it a place by customizing the bar in Settings.",
        zhHant = "底部功能列已經滿了。從「更多」就能打開圖書館，也可以在設定中自訂功能列，替它空出位置。",
    ),
)

/** [tabs] with Library last, which puts it left of More, or null when the bar has it or is full. */
internal fun tabsAddingLibrary(tabs: List<AppFeature>): List<AppFeature>? =
    if (AppFeature.LIBRARY in tabs || tabs.size >= AppFeature.MAX_CUSTOM_TABS) null
    else tabs + AppFeature.LIBRARY

/** The user's bar above the one with Library; captured once, so confirming doesn't redraw it mid-slide. */
@Composable
private fun LibraryBottomBarDemo(tabs: () -> List<AppFeature>) {
    val current = remember { tabs() }
    BottomBarChangeDemo(before = current, after = tabsAddingLibrary(current) ?: current)
}

private val fromBarCaption = WhatsNewText(en = "From the bottom bar", zhHant = "從底部功能列")
private val fromMoreCaption = WhatsNewText(en = "From More", zhHant = "從「更多」")
private val libraryDemoDescription = WhatsNewText(
    en = "Demo: the library pass opens from the bottom bar's Library tab, and from the Library card in More.",
    zhHant = "示範：從底部功能列的圖書館，或「更多」裡的圖書館卡片，打開虛擬入館證。",
)

private const val LIBRARY_LOOP_MILLIS = 9_000
private val demoBar = listOf(AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.SCHOOL_MAIL, AppFeature.LIBRARY, AppFeature.MORE)

@Composable
private fun LibraryDemo(animate: Boolean) {
    // Still: the pass, opened from the bar.
    val clock = demoClock(animate, LIBRARY_LOOP_MILLIS, still = 0.3f)
    val t = { clock.value }
    // The pass from the bar, then More's grid, then the pass from there.
    val passFromBar = { window(t(), 0.12f, 0.44f, fade = 0.03f) }
    val more = { window(t(), 0.56f, 0.71f, fade = 0.03f) }
    val passFromMore = { window(t(), 0.68f, 0.95f, fade = 0.03f) }
    Column(Modifier.demoDescription(libraryDemoDescription.resolve()), horizontalAlignment = Alignment.CenterHorizontally) {
        MockScreen {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    HomeLines()
                    MoreGrid(Modifier.graphicsLayer { alpha = more() })
                    LibraryPass(Modifier.graphicsLayer { alpha = maxOf(passFromBar(), passFromMore()) })
                }
                MiniBar(selected = { tab ->
                    when (tab) {
                        AppFeature.LIBRARY -> maxOf(passFromBar(), passFromMore())
                        AppFeature.MORE -> more()
                        AppFeature.HOME -> 1f - maxOf(passFromBar(), more(), passFromMore())
                        else -> 0f
                    }
                })
            }
            // The Library tab, the More tab, then More's Library card.
            Fingertip(154.dp, 228.dp, shown = { window(t(), 0.04f, 0.12f) }, press = { span(t(), 0.09f, 0.12f) })
            Fingertip(198.dp, 228.dp, shown = { window(t(), 0.48f, 0.56f) }, press = { span(t(), 0.53f, 0.56f) })
            Fingertip(160.dp, 110.dp, shown = { window(t(), 0.60f, 0.68f) }, press = { span(t(), 0.65f, 0.68f) })
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            DemoCaption(fromBarCaption.resolve(), alpha = { window(t(), 0.02f, 0.46f, fade = 0.03f) })
            DemoCaption(fromMoreCaption.resolve(), alpha = { window(t(), 0.47f, 0.97f, fade = 0.03f) })
        }
    }
}

@Composable
private fun HomeLines() {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextLine(0.35f, height = 9.dp, strong = true)
        repeat(3) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TextLine(0.5f, strong = true)
                TextLine(0.8f)
            }
        }
    }
}

/** More's grid of feature cards, its Library card picked out. */
@Composable
private fun MoreGrid(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val cards = listOf(AppFeature.CLASS_TABLE, AppFeature.CALENDAR, AppFeature.SCORE, AppFeature.LIBRARY, AppFeature.ANNOUNCEMENTS, AppFeature.EMPTY_CLASSROOM)
    Column(
        modifier.fillMaxSize().background(colors.surfaceContainerLow).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MockText(stringResource(AppFeature.MORE.shortDisplayNameRes), size = 14.sp, weight = FontWeight.Bold)
        cards.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { feature ->
                    val picked = feature == AppFeature.LIBRARY
                    Column(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (picked) colors.primaryContainer else colors.surface)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(feature.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                        MockText(stringResource(feature.displayNameRes), size = 10.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** The library page: its virtual pass, the QR code and the ring counting to its refresh. */
@Composable
private fun LibraryPass(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().background(colors.surface).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth()) { MockText(stringResource(R.string.feature_library), size = 14.sp, weight = FontWeight.Bold) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerLow).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MockText(stringResource(R.string.library_virtual_pass_title), size = 11.sp, weight = FontWeight.SemiBold)
            MockQr(84.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(progress = { 0.6f }, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(4.dp))
                MockText(stringResource(R.string.library_qr_refresh_in_seconds, 18), size = 9.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** The bottom bar, each tab's pill drawn as far as [selected] says (0 to 1). */
@Composable
private fun MiniBar(selected: (AppFeature) -> Float) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        demoBar.forEach { tab ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = selected(tab) }
                            .clip(RoundedCornerShape(50))
                            .background(colors.secondaryContainer),
                    )
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).size(16.dp),
                    )
                }
                MockText(stringResource(tab.shortDisplayNameRes), size = 8.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}
