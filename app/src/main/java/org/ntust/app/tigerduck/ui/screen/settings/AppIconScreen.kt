package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.appicon.AppIcon
import org.ntust.app.tigerduck.appicon.LauncherIcons
import org.ntust.app.tigerduck.appicon.choose
import org.ntust.app.tigerduck.library.LibraryQrShortcut
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold

private const val COLUMNS = 3
private val IconSize = 72.dp
private val RingWidth = 3.dp
private val RingGap = 4.dp

/**
 * App icon, under Other settings: each icon [AppIcon] offers, drawn as the launcher draws it, three
 * to a row, as on the iPhone (tigerduck-app#1026). A tap puts the icon on the launcher at once and
 * keeps the pick ([choose]). The library QR shortcut is published again with it, so
 * it rides on the new entry rather than leaving with the old one.
 */
@Composable
fun AppIconScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val choice = viewModel.prefs.appIcon
    val chosen by choice.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PageScaffold(title = stringResource(R.string.settings_app_icon), onBack = onBack) { padding ->
        AppIconChoices(chosen, Modifier.padding(padding)) { icon ->
            if (choice.choose(icon, LauncherIcons(context)::show)) LibraryQrShortcut.publish(context)
        }
    }
}

/** The icons, [chosen] marked with a ring in the launcher's shape and a check. */
@Composable
internal fun AppIconChoices(chosen: AppIcon, modifier: Modifier = Modifier, onChoose: (AppIcon) -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = 32.dp),
    ) {
        ContentCard {
            Column(Modifier.padding(vertical = 12.dp).selectableGroup()) {
                AppIcon.entries.chunked(COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        row.forEach { icon ->
                            AppIconCell(icon, icon == chosen, Modifier.weight(1f)) { onChoose(icon) }
                        }
                        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconCell(icon: AppIcon, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconPreview(icon, selected)
        Spacer(Modifier.height(8.dp))
        // One line, as the launcher labels icons.
        Text(
            stringResource(icon.title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppIconPreview(icon: AppIcon, selected: Boolean) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val drawn = remember(icon, sizePx) { drawIcon(context, icon, sizePx) }
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.size(IconSize + (RingWidth + RingGap) * 2), contentAlignment = Alignment.Center) {
        drawn?.let { Image(it.image, contentDescription = null, modifier = Modifier.size(IconSize)) }
        if (selected) {
            Box(Modifier.matchParentSize().border(RingWidth, accent, drawn?.shape ?: CircleShape))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/** [icon] as the launcher draws it, in the device's icon shape, with that shape for its ring. */
private class DrawnIcon(val image: ImageBitmap, val shape: Shape)

private fun drawIcon(context: Context, icon: AppIcon, sizePx: Int): DrawnIcon? {
    val drawable = ContextCompat.getDrawable(context, icon.icon) ?: return null
    drawable.setBounds(0, 0, sizePx, sizePx)
    val bitmap = createBitmap(sizePx, sizePx)
    drawable.draw(Canvas(bitmap))
    val mask = (drawable as? AdaptiveIconDrawable)?.iconMask?.let { android.graphics.Path(it) }
    return DrawnIcon(bitmap.asImageBitmap(), mask?.let { IconMaskShape(it, sizePx.toFloat()) } ?: CircleShape)
}

/** The launcher's icon shape, [mask] drawn at [maskSize], stretched to whatever it outlines. */
private class IconMaskShape(private val mask: android.graphics.Path, private val maskSize: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = android.graphics.Path(mask)
        path.transform(Matrix().apply { setScale(size.width / maskSize, size.height / maskSize) })
        return Outline.Generic(path.asComposePath())
    }
}
