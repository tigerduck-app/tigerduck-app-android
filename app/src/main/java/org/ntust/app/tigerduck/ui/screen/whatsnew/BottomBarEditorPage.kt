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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.navigation.icon

/**
 * "Arrange your bottom bar" — where the bottom bar is customized: Settings ›
 * Customization › Customize bottom bar, where a tab is dragged into place.
 * Never shown before: 2.3.0's Mail question only said it could be changed.
 */
internal fun bottomBarEditorPage() = WhatsNewPage.Feature(
    id = "bottom-bar-editor",
    visual = WhatsNewVisual.Custom { animate -> BottomBarEditorDemo(animate) },
    title = WhatsNewText(
        en = "Arrange your bottom bar",
        zhHant = "底部功能列由你排",
    ),
    body = WhatsNewText(
        en = "In Settings › Customization › Customize bottom bar, add or remove tabs, or drag them into the order you like. More stays at the right end.",
        zhHant = "在「設定 › 自訂 › 自訂底部功能列」可以新增、移除或拖曳調整順序，「更多」固定在最右邊。",
    ),
)

private val editorDemoDescription = WhatsNewText(
    en = "Demo: from Settings, Customization, Customize bottom bar: Library is dragged above Mail, and the bottom bar follows.",
    zhHant = "示範：從設定的「自訂」進入「自訂底部功能列」，把圖書館拖到信箱上面，底部功能列跟著換順序。",
)

private const val EDITOR_LOOP_MILLIS = 5_000
private val EditorRowHeight = 26.dp
private val editorTabs = listOf(AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.SCHOOL_MAIL, AppFeature.LIBRARY)

// The drag, as parts of the editor's own part: Library picked up, moved, let go.
private const val PICK_UP = 0.15f
private const val LET_GO = 0.45f

@Composable
private fun BottomBarEditorDemo(animate: Boolean) {
    PathDemo(
        description = editorDemoDescription.resolve(),
        animate = animate,
        featureMillis = EDITOR_LOOP_MILLIS,
        start = PathStart.SettingsGear,
        stops = listOf(
            PathStop(
                title = stringResource(R.string.feature_settings),
                section = stringResource(R.string.settings_section_custom),
                rows = listOf(
                    stringResource(R.string.tab_editor_title),
                    stringResource(R.string.settings_accent_color),
                ),
                tapped = 0,
                scrolled = false,
            ),
        ),
        // Still: Library dropped above Mail.
        featureStill = 0.7f,
    ) { t -> BottomBarEditorScene(t) }
}

/** The editor: Library dragged from below Mail to above it through [t], the bar under it following. */
@Composable
private fun BottomBarEditorScene(t: () -> Float) {
    val colors = MaterialTheme.colorScheme
    val moved = { span(t(), PICK_UP, LET_GO) }
    // The order the bar shows: changed once Library is let go.
    val swapped by remember(t) { derivedStateOf { t() >= LET_GO } }
    Column(
        Modifier.fillMaxSize().background(colors.surfaceContainerLow).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
            MockText(stringResource(R.string.tab_editor_title), size = 12.sp, weight = FontWeight.Bold)
        }
        Box(Modifier.height(14.dp), contentAlignment = Alignment.CenterStart) {
            MockText(stringResource(R.string.tab_editor_section_current_tabs), size = 9.sp, weight = FontWeight.SemiBold, color = colors.primary)
        }
        Box(Modifier.fillMaxWidth().height(EditorRowHeight * 5).clip(RoundedCornerShape(10.dp)).background(colors.surface)) {
            editorTabs.forEachIndexed { index, feature ->
                // Mail moves down a row as Library passes it; Library rides the finger up.
                val y = { when (feature) {
                    AppFeature.LIBRARY -> EditorRowHeight * (3f - moved())
                    AppFeature.SCHOOL_MAIL -> EditorRowHeight * (2f + moved())
                    else -> EditorRowHeight * index
                } }
                EditorRow(
                    feature,
                    Modifier
                        .offset { IntOffset(0, y().roundToPx()) }
                        .graphicsLayer {
                            val lifted = if (feature == AppFeature.LIBRARY) window(t(), PICK_UP - 0.05f, LET_GO + 0.03f, fade = 0.04f) else 0f
                            shadowElevation = lifted * 6.dp.toPx()
                            scaleX = 1f + lifted * 0.03f
                            scaleY = scaleX
                        },
                )
            }
            Row(
                Modifier.offset(y = EditorRowHeight * 4).fillMaxWidth().height(EditorRowHeight).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppFeature.MORE.icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                MockText(stringResource(AppFeature.MORE.shortDisplayNameRes), Modifier.weight(1f), size = 10.sp)
                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        EditorBar(if (swapped) listOf(AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.LIBRARY, AppFeature.SCHOOL_MAIL) else editorTabs)
    }
    DragFingertip(t, moved)
}

/** The finger on Library's handle, from its row up to Mail's. */
@Composable
private fun DragFingertip(t: () -> Float, moved: () -> Float) {
    val color = MaterialTheme.colorScheme.onSurface
    val top = 8.dp + 20.dp + 6.dp + 14.dp + 6.dp
    Box(
        Modifier
            .offset {
                val y = top + EditorRowHeight * (3.5f - moved())
                IntOffset((198.dp - 14.dp).roundToPx(), (y - 14.dp).roundToPx())
            }
            .size(28.dp)
            .graphicsLayer { alpha = window(t(), PICK_UP - 0.07f, LET_GO + 0.05f) * 0.9f }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
    )
}

@Composable
private fun EditorRow(feature: AppFeature, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(EditorRowHeight).background(colors.surface).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.RemoveCircle, contentDescription = null, tint = colors.error, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Icon(feature.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        MockText(stringResource(feature.shortDisplayNameRes), Modifier.weight(1f), size = 10.sp)
        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

/** The bottom bar as [tabs] arrange it, More last. */
@Composable
private fun EditorBar(tabs: List<AppFeature>) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer).padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        (tabs + AppFeature.MORE).forEach { tab ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(tab.icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                MockText(stringResource(tab.shortDisplayNameRes), size = 7.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}
