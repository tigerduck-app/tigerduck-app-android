package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/**
 * A course in a list: a square of the color [colorKey] takes, [title], and [details], such as its
 * number and teacher, on a line below. Read whole by TalkBack. [modifier] makes it a button, as
 * the conflict picker's rows are; a member's courses in common do nothing on a tap. As the
 * iPhone's `CourseListRow`.
 */
@Composable
fun CourseListRow(colorKey: String, title: String, details: List<String>, modifier: Modifier = Modifier) {
    val swatch = LocalVisualStylePolicy.current.courseAccentColor(
        courseColor = TigerDuckTheme.courseColorVibrant(colorKey),
        tint = TigerDuckTheme.courseColor(colorKey),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(swatch),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        }
    }
}
