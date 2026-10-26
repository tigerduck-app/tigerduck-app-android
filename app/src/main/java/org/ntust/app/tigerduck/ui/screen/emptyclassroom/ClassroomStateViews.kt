package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.time.LocalDate

/**
 * A glyph and a short title in a card, for a failure, a day without data or a holiday, with the
 * one action that leads on from it.
 */
@Composable
internal fun ClassroomStateCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            action()
        }
    }
}

/** The button under no data and a holiday, which moves to the next teaching day. */
@Composable
internal fun NextTeachingDayButton(day: LocalDate, onSelect: (LocalDate) -> Unit) {
    val locale = finderLocale()
    Button(
        onClick = { onSelect(day) },
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Text(dateText(day, locale))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp).size(ButtonDefaults.IconSize),
        )
    }
}

/** Rows of grey shapes where the rooms will be, pulsing gently unless animations are off. */
@Composable
internal fun ClassroomSkeleton(modifier: Modifier = Modifier) {
    val animate = android.provider.Settings.Global.getFloat(
        LocalContext.current.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) != 0f
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "skeletonAlpha",
        )
        alpha
    } else {
        1f
    }
    val shape = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = pulse }
            // Read as an indeterminate progress bar, as the system's own spinner is.
            .clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(width = 28.dp, height = 12.dp)
                .background(shape.copy(alpha = 0.12f), RoundedCornerShape(50)),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            repeat(3) { index ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(width = 68.dp, height = 14.dp).background(shape.copy(alpha = 0.14f), RoundedCornerShape(50)))
                    Box(Modifier.weight(1f).height(6.dp).background(shape.copy(alpha = 0.08f), RoundedCornerShape(50)))
                    Box(Modifier.width(40.dp).height(12.dp).background(shape.copy(alpha = 0.1f), RoundedCornerShape(50)))
                }
            }
        }
    }
}
