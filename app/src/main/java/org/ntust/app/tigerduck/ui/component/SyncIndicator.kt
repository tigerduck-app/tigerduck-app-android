package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@Composable
fun SyncIndicator(
    isLoading: Boolean,
    showCheckmark: Boolean,
    modifier: Modifier = Modifier,
    dragProgress: Float = 0f,
) {
    Box(modifier = modifier.size(20.dp)) {
        AnimatedContent(
            targetState = when {
                isLoading -> "loading"
                dragProgress > 0f -> "dragging"
                showCheckmark -> "checkmark"
                else -> "idle"
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sync_status"
        ) { state ->
            when (state) {
                "loading" -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
                "checkmark" -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.sync_success_content_description),
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(20.dp)
                )
                "dragging" -> DragArc(progress = dragProgress)
                else -> Spacer(Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DragArc(progress: Float) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    Canvas(modifier = Modifier.size(18.dp)) {
        val inset = strokePx / 2f
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = progress.coerceIn(0f, 1f) * 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokePx, size.height - strokePx),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}
