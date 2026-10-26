package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.R

/**
 * "Share your class table": the share sheet's QR code, scanned on another
 * phone in TigerDuck, from Add class table in the same menu, which imports
 * it, or in any QR scanner, which opens it on the web.
 */
internal fun classTableSharePage() = WhatsNewPage.Feature(
    id = "class-table-share",
    visual = WhatsNewVisual.Custom { animate -> ShareDemo(animate) },
    title = WhatsNewText(
        en = "Share your class table",
        zhHant = "分享你的課表",
    ),
    body = WhatsNewText(
        en = "Choose Share class table under More actions, at the class table's top right, to show a QR code. To import it, choose Add class table in the same menu in TigerDuck and scan it; any QR scanner opens it on the web. TAT works too: turn on Compatible with TAT.",
        zhHant = "在課表右上角的「更多操作」選「分享課表」，就會出現 QR code。對方在 TigerDuck 的同一個選單選「加入課表」掃描就能匯入，用任何 QR code 掃描器掃也能打開網頁版。也支援臺科 TAT：打開「相容於 TAT」再讓對方掃描。",
    ),
)

private val inTigerDuckCaption = WhatsNewText(en = "Scanned in TigerDuck", zhHant = "用 TigerDuck 掃描")
private val anyScannerCaption = WhatsNewText(en = "Any QR scanner", zhHant = "用任何 QR code 掃描器")
private val tatCaption = WhatsNewText(en = "TAT works too", zhHant = "也支援臺科 TAT")
private val openInTigerDuck = WhatsNewText(en = "Open in TigerDuck", zhHant = "在 TigerDuck 開啟")
private val shareDemoDescription = WhatsNewText(
    en = "Demo: on one phone, More actions on the class table, then Share class table, shows a QR code; on another phone, More actions, then Add class table, opens TigerDuck's scanner, which imports it, and any QR scanner opens it on the web instead. Compatible with TAT then turns it into a code TAT reads.",
    zhHant = "示範：在一支手機的課表點「更多操作」、「分享課表」，出現課表 QR code；另一支在 TigerDuck 的課表點「更多操作」、「加入課表」打開掃描器，掃描後就能匯入；用任何 QR code 掃描器掃則會打開網頁版。再打開「相容於 TAT」，就換成 TAT 讀得懂的 QR code。",
)

private const val SHARE_LOOP_MILLIS = 18_000

/** [millis] into the loop, as a part of it. */
private fun at(millis: Int) = millis / SHARE_LOOP_MILLIS.toFloat()

// The left phone's steps: More actions, Share class table, the sheet; later, TAT.
private val MENU_OPEN = at(1_000)
private val SHARE_TAPPED = at(2_000)
private val SHEET_UP = at(2_700)
private val TAT_ON = at(13_400)
private val LEFT_RESET = at(17_460)

// The right phone's, once the code shows: in TigerDuck, More actions, Import
// class table, the scanner and the import; then any QR scanner and the web
// page; then the TAT code.
private val OTHER_MENU_OPEN = at(3_900)
private val IMPORT_TAPPED = at(5_100)
private val SCANNED = at(7_200)
private val IMPORT_SHOWN = at(9_600)
private val WEB_SHOWN = at(13_200)
private val PhoneWidth = 132.dp

/** The TAT-compatible code's look: another code than TigerDuck's own. */
private const val TAT_SEED = 0x1B873593
private val PhoneHeight = 220.dp

@Composable
private fun ShareDemo(animate: Boolean) {
    // Still: the import, scanned in TigerDuck.
    val clock = demoClock(animate, SHARE_LOOP_MILLIS, still = 0.45f)
    val t = { clock.value }
    val leftReset = { 1f - span(t(), LEFT_RESET, 0.995f) }
    val menu = { window(t(), MENU_OPEN, SHEET_UP, fade = 0.02f) }
    val sheet = { span(t(), SHARE_TAPPED + 0.02f, SHEET_UP) * leftReset() }
    val tat = { span(t(), TAT_ON, TAT_ON + 0.03f) * leftReset() }
    val otherMenu = { window(t(), OTHER_MENU_OPEN, IMPORT_TAPPED + 0.02f, fade = 0.02f) }
    // The second phone: TigerDuck's scanner and the import; scanning again, the web page; then the TAT code.
    val scanning = {
        window(t(), IMPORT_TAPPED, SCANNED + 0.01f, fade = 0.02f) +
            window(t(), IMPORT_SHOWN - 0.01f, at(11_000), fade = 0.02f) +
            window(t(), WEB_SHOWN, 0.99f, fade = 0.02f)
    }
    val imported = { window(t(), SCANNED, IMPORT_SHOWN, fade = 0.02f) }
    val web = { window(t(), at(10_800), WEB_SHOWN + 0.01f, fade = 0.02f) }
    Column(Modifier.demoDescription(shareDemoDescription.resolve()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MockScreen(width = PhoneWidth, height = PhoneHeight) {
                MiniClassTable()
                ActionsMenu(Modifier.graphicsLayer { alpha = menu() })
                ShareSheet(
                    Modifier.graphicsLayer { translationY = (1f - sheet()) * size.height },
                    tat = tat,
                )
                // More actions, Share class table, then Compatible with TAT.
                Fingertip(118.dp, 14.dp, shown = { window(t(), 0.01f, MENU_OPEN + 0.01f) }, press = { span(t(), 0.04f, MENU_OPEN) })
                Fingertip("share", shown = { window(t(), MENU_OPEN + 0.02f, SHARE_TAPPED + 0.02f) }, press = { span(t(), SHARE_TAPPED - 0.03f, SHARE_TAPPED) })
                Fingertip("tat", shown = { window(t(), TAT_ON - 0.06f, TAT_ON + 0.04f) }, press = { span(t(), TAT_ON - 0.03f, TAT_ON) })
            }
            MockScreen(width = PhoneWidth, height = PhoneHeight) {
                // TigerDuck on the other phone: its own class table, where Add class table is.
                MiniClassTable()
                ActionsMenu(Modifier.graphicsLayer { alpha = otherMenu() })
                Scanner(Modifier.graphicsLayer { alpha = scanning().coerceAtMost(1f) }, sweep = { (t() * 9f) % 1f }, tat = tat)
                ImportConfirm(Modifier.graphicsLayer { alpha = imported() })
                WebPage(Modifier.graphicsLayer { alpha = web() })
                // More actions, then Add class table.
                Fingertip(118.dp, 14.dp, shown = { window(t(), SHEET_UP + 0.01f, OTHER_MENU_OPEN + 0.01f) }, press = { span(t(), OTHER_MENU_OPEN - 0.025f, OTHER_MENU_OPEN) })
                Fingertip("import", shown = { window(t(), OTHER_MENU_OPEN + 0.01f, IMPORT_TAPPED + 0.01f) }, press = { span(t(), IMPORT_TAPPED - 0.025f, IMPORT_TAPPED) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            DemoCaption(inTigerDuckCaption.resolve(), alpha = { window(t(), 0.0f, IMPORT_SHOWN, fade = 0.02f) })
            DemoCaption(anyScannerCaption.resolve(), alpha = { window(t(), IMPORT_SHOWN, TAT_ON, fade = 0.02f) })
            DemoCaption(tatCaption.resolve(), alpha = { window(t(), TAT_ON, 0.99f, fade = 0.02f) })
        }
    }
}

/** The class table behind the menu: its title, ⋮ in the corner, and a few courses. */
@Composable
private fun MiniClassTable() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TextLine(0.45f, height = 7.dp, strong = true) }
            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            listOf(
                Triple(0.dp, 4.dp, colors.primaryContainer),
                Triple(30.dp, 40.dp, colors.tertiaryContainer),
                Triple(60.dp, 10.dp, colors.secondaryContainer),
                Triple(90.dp, 70.dp, colors.primaryContainer),
                Triple(30.dp, 110.dp, colors.secondaryContainer),
            ).forEach { (x, y, fill) ->
                Box(Modifier.padding(start = x, top = y).width(24.dp).height(40.dp).clip(RoundedCornerShape(5.dp)).background(fill))
            }
        }
    }
}

/** The class table's More actions menu, as the app's own: Share class table and Add class table among them. */
@Composable
private fun ActionsMenu(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .padding(start = 26.dp, top = 24.dp)
            .width(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerHigh)
            .padding(vertical = 4.dp),
    ) {
        MenuItem(Icons.Filled.Add, null)
        MenuItem(Icons.Filled.Groups, null)
        MenuItem(Icons.Filled.QrCode, stringResource(R.string.class_table_share), Modifier.tapTarget("share"))
        MenuItem(Icons.Filled.QrCodeScanner, stringResource(R.string.class_table_import), Modifier.tapTarget("import"))
        MenuItem(Icons.Filled.Share, stringResource(R.string.class_table_export_screenshot))
        MenuItem(Icons.Filled.Sync, null)
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(5.dp))
        if (label != null) MockText(label, size = 8.sp) else TextLine(0.6f, height = 4.dp)
    }
}

/** The share sheet: scanner, title and link along the top, the QR code under them; TAT's code once [tat]. */
@Composable
private fun ShareSheet(modifier: Modifier, tat: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().background(colors.surface).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            MockText(stringResource(R.string.class_table_share), Modifier.weight(1f), size = 11.sp, weight = FontWeight.Bold)
            Icon(Icons.Filled.Link, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.height(4.dp))
        Box {
            MockQr(96.dp, Modifier.border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)).graphicsLayer { alpha = 1f - tat() })
            MockQr(96.dp, Modifier.border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)).graphicsLayer { alpha = tat() }, seed = TAT_SEED)
        }
        TextLine(0.6f, height = 5.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MockText(stringResource(R.string.class_table_semester_picker_label), Modifier.weight(1f), size = 9.sp)
            MockText("114-1", size = 9.sp, color = colors.primary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MockText(stringResource(R.string.class_table_share_tat_compatible), Modifier.weight(1f), size = 9.sp)
            MockSwitch(tat, Modifier.tapTarget("tat"))
        }
    }
}

/** A camera finding the code: dark, a frame, the code inside it and a line sweeping by [sweep]. */
@Composable
private fun Scanner(modifier: Modifier, sweep: () -> Float, tat: () -> Float) {
    Box(modifier.fillMaxSize().background(Color(0xFF15171A)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(96.dp).border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            MockQr(64.dp, Modifier.graphicsLayer { alpha = 0.85f * (1f - tat()) })
            MockQr(64.dp, Modifier.graphicsLayer { alpha = 0.85f * tat() }, seed = TAT_SEED)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { translationY = sweep() * 92.dp.toPx() }
                    .background(Color(0xFF7BE495)),
            )
        }
    }
}

/** TigerDuck's import: what the code holds, and Import. */
@Composable
private fun ImportConfirm(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.35f)).padding(10.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MockText(stringResource(R.string.class_table_import), size = 11.sp, weight = FontWeight.Bold)
            ImportRow(stringResource(R.string.sign_in_student_id)) { TextLine(0.8f, strong = true) }
            ImportRow(stringResource(R.string.class_table_semester_picker_label)) { MockText("114-1", size = 9.sp) }
            ImportRow(stringResource(R.string.class_table_import_course_count)) { MockText("8", size = 9.sp) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(colors.primary).padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                MockText(stringResource(R.string.action_import), size = 10.sp, weight = FontWeight.SemiBold, color = colors.onPrimary)
            }
        }
    }
}

@Composable
private fun ImportRow(label: String, value: @Composable BoxScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MockText(label, Modifier.weight(1f), size = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd, content = value)
    }
}

/** The share page on the web: its address, the class table, and a way into the app. */
@Composable
private fun WebPage(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
            MockText("share.tigerduck.app", size = 8.sp, color = colors.onSurfaceVariant)
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextLine(0.5f, height = 7.dp, strong = true)
            // The class table, small: a few courses across the week.
            Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerLow)) {
                listOf(
                    Triple(0.dp, 4.dp, colors.primaryContainer),
                    Triple(28.dp, 30.dp, colors.tertiaryContainer),
                    Triple(56.dp, 8.dp, colors.secondaryContainer),
                    Triple(84.dp, 50.dp, colors.primaryContainer),
                ).forEach { (x, y, fill) ->
                    Box(
                        Modifier
                            .padding(start = x + 4.dp, top = y)
                            .width(24.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(fill),
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(colors.primary).padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                MockText(openInTigerDuck.resolve(), size = 9.sp, weight = FontWeight.SemiBold, color = colors.onPrimary)
            }
        }
    }
}
