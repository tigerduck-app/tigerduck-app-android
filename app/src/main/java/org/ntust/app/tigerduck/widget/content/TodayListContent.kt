package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.glance.action.Action
import androidx.glance.text.Text
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState

@Composable
fun TodayListContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    Text(text = "Today")
}
