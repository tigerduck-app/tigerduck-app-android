package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * Bottom sheet that lets the user assign a tile color to a class.
 * Shows a curated preset grid plus an HSV fine-tune panel for custom colors.
 *
 * @param usedByOthers maps preset colors that are currently assigned to OTHER
 *                     courses — used only to show a small indicator dot so the
 *                     user knows picking it will displace that course.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(
    courseName: String,
    currentColor: Color,
    presetPalette: List<Color>,
    usedByOthers: Set<Int>,
    onApply: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initialHsv = remember(currentColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(currentColor.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    var showCustom by remember { mutableStateOf(false) }

    val selectedColor = remember(hue, sat, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    }
    val selectedArgb = selectedColor.toArgb() or 0xFF000000.toInt()

    fun setHsvFrom(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "選擇顏色",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = courseName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(20.dp))

            // Preview — matches the alpha used on the schedule grid tile.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(selectedColor.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = courseName.ifBlank { "預覽" },
                    color = if (selectedColor.red * 0.299f + selectedColor.green * 0.587f + selectedColor.blue * 0.114f > 0.5f)
                        Color(0xFF1C1C1E) else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(Modifier.height(22.dp))

            SectionLabel(text = "預設（設選擇與它科重複顏色會導致該科顏色重新分配）")
            Spacer(Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 6
            ) {
                presetPalette.forEach { preset ->
                    val presetArgb = preset.toArgb() or 0xFF000000.toInt()
                    ColorSwatch(
                        color = preset,
                        selected = presetArgb == selectedArgb,
                        usedByOther = presetArgb in usedByOthers && presetArgb != selectedArgb,
                        onClick = { setHsvFrom(preset) }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showCustom = !showCustom }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel(text = "自訂顏色", modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (showCustom) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
            }

            if (showCustom) {
                Spacer(Modifier.height(8.dp))
                SatValSquare(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onSatValChange = { s, v -> sat = s; value = v }
                )
                Spacer(Modifier.height(12.dp))
                HueSlider(
                    hue = hue,
                    onHueChange = { hue = it }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "#" + String.format("%06X", selectedArgb and 0xFFFFFF),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.align(Alignment.End)
                )
            }

            Spacer(Modifier.height(22.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = { onApply(selectedColor) },
                    modifier = Modifier.weight(1f)
                ) { Text("套用") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = modifier
    )
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    usedByOther: Boolean,
    onClick: () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier.size(44.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick)
                .then(
                    if (selected) Modifier.border(3.dp, Color.White, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已選",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (usedByOther) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(surface)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(onSurface.copy(alpha = 0.55f))
                )
            }
        }
    }
}

@Composable
private fun SatValSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onSatValChange: (sat: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val pureHueColor = remember(hue) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    onSatValChange(s, v)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    onSatValChange(s, v)
                }
            }
    ) {
        drawRect(color = pureHueColor)
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        val cx = sat * size.width
        val cy = (1f - value) * size.height
        val inner = 8.dp.toPx()
        drawCircle(
            color = Color.Black.copy(alpha = 0.4f),
            radius = inner + 2.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = inner,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueStops = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val h = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                    onHueChange(h)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val h = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                    onHueChange(h)
                }
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(hueStops))
        val cx = (hue / 360f) * size.width
        val cy = size.height / 2
        val r = size.height / 2 - 2.dp.toPx()
        drawCircle(
            color = Color.Black.copy(alpha = 0.4f),
            radius = r + 2.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
