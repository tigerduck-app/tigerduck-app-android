package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@Composable
fun SyncIndicator(
    isLoading: Boolean,
    showCheckmark: Boolean,
    modifier: Modifier = Modifier,
    dragProgress: Float = 0f,
    isLocalOnly: Boolean = false,
) {
    val loadingLabel = stringResource(R.string.refreshing_message)
    val successLabel = stringResource(R.string.sync_success_content_description)
    val statusLabel = when {
        isLoading -> loadingLabel
        showCheckmark -> successLabel
        else -> ""
    }
    Box(
        modifier = modifier
            .size(20.dp)
            // Always attach the semantics node so TalkBack sees an existing
            // live-region whose contentDescription changes — node creation
            // alone does not reliably trigger a polite announcement.
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = statusLabel
            },
    ) {
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
                    contentDescription = null,
                    tint = if (isLocalOnly) Color(0xFFFFCC00) else Color(0xFF34C759),
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
