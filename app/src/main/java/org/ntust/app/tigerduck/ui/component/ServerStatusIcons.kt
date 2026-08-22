package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ServerStatusIcons(
    servers: List<ServerKind>,
    modifier: Modifier = Modifier,
) {
    val statuses by ServerStatusTracker.statuses.collectAsState()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        servers.forEach { server ->
            val status = statuses[server] ?: ServerStatus.UNKNOWN
            Icon(
                imageVector = server.icon,
                contentDescription = "${server.label}: ${status.name}",
                tint = status.color,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private val ServerKind.icon: ImageVector
    get() = when (this) {
        ServerKind.MOODLE -> Icons.Filled.School
        ServerKind.COURSE_SELECTION -> Icons.Outlined.Checklist
        ServerKind.BACKEND -> Icons.Filled.Cloud
    }

private val ServerKind.label: String
    get() = when (this) {
        ServerKind.MOODLE -> "Moodle"
        ServerKind.COURSE_SELECTION -> "Course Selection"
        ServerKind.BACKEND -> "TigerDuck Backend"
    }

private val ServerStatus.color: Color
    get() = when (this) {
        ServerStatus.UNKNOWN -> Color.Gray
        ServerStatus.OK -> Color(0xFF34C759)
        ServerStatus.FAILED -> Color.Red
    }
