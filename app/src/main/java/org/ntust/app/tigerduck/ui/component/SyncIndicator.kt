package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@Composable
fun SyncIndicator(
    isLoading: Boolean,
    showCheckmark: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(20.dp)) {
    AnimatedContent(
        targetState = when {
            isLoading -> "loading"
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
                contentDescription = "同步成功",
                tint = Color(0xFF34C759),
                modifier = Modifier.size(20.dp)
            )
            else -> Spacer(Modifier.size(20.dp))
        }
    }
    }
}
