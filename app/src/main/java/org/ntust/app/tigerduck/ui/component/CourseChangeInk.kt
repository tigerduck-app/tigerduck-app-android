package org.ntust.app.tigerduck.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.timeline.CourseChangeWords
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/** The color a school's course change is drawn in, light and dark, as the iPhone's `changeInk`. */
val CourseChangeInkLight = Color(0xFF7C2D12)
val CourseChangeInkDark = Color(0xFFFDBA74)

/** [CourseChangeInkLight] or [CourseChangeInkDark] for the app's theme. */
fun courseChangeInk(): Color = if (TigerDuckTheme.isDarkMode) CourseChangeInkDark else CourseChangeInkLight

/** The words the course detail's 課程異動 list writes, in the app's language. */
@Composable
fun courseChangeWords(): CourseChangeWords = CourseChangeWords(
    cancelled = stringResource(R.string.course_card_cancelled),
    assessment = stringResource(R.string.course_change_assessment),
    online = stringResource(R.string.course_change_online),
)
