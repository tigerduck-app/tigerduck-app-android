package org.ntust.app.tigerduck.ui.component

import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/** The color a school's course change is drawn in, light and dark, as the iPhone's `changeInk`. */
val CourseChangeInkLight = Color(0xFF7C2D12)
val CourseChangeInkDark = Color(0xFFFDBA74)

/** [CourseChangeInkLight] or [CourseChangeInkDark] for the app's theme. */
fun courseChangeInk(): Color = if (TigerDuckTheme.isDarkMode) CourseChangeInkDark else CourseChangeInkLight
