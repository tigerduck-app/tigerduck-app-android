package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.R

/**
 * "Moodle, right in your class table": a course's sheet scrolls down to its
 * Moodle section, and Open in Moodle opens the Moodle app or, without it, the
 * web page already signed in. The demo plays both endings in turn.
 */
internal fun moodleCoursePage() = WhatsNewPage.Feature(
    id = "moodle-course",
    visual = WhatsNewVisual.Custom { animate -> MoodleCourseDemo(animate) },
    title = WhatsNewText(
        en = "Moodle, right in your class table",
        zhHant = "在課表裡就能看 Moodle",
    ),
    body = WhatsNewText(
        en = "Open a course and scroll down for its announcements, materials, assignments, grades and members.\nOpen in Moodle takes you to the Moodle app, or, without it, to the web page with you already signed in.",
        zhHant = "點開課程往下滑，就能看到公告、教材、作業、成績與成員。\n點「在 Moodle 開啟」會打開 Moodle App；沒有安裝 App 時，會直接開啟已經幫你登入的網頁。",
    ),
)

/** "Every assignment detail": an assignment opens on its due time, attachments and submission. */
internal fun moodleAssignmentPage() = WhatsNewPage.Feature(
    id = "moodle-assignment",
    visual = WhatsNewVisual.Custom { animate -> AssignmentDemo(animate) },
    title = WhatsNewText(
        en = "Every assignment detail",
        zhHant = "作業細節一目了然",
    ),
    body = WhatsNewText(
        en = "Open an assignment to see its attachments, due time, submission status and grade. Attachments download and open right there.",
        zhHant = "點開作業就能看到附件、截止時間、繳交狀態與評分，附件也能直接下載查看。",
    ),
)

private val courseName = WhatsNewText(en = "Calculus", zhHant = "微積分")
private val moodleAppCaption = WhatsNewText(en = "Moodle app", zhHant = "Moodle App")
private val noAppCaption = WhatsNewText(en = "Without the Moodle app", zhHant = "沒有 Moodle App 時")
private val courseDemoDescription = WhatsNewText(
    en = "Demo: a course opens from the class table, scrolls down to Moodle, then opens in the Moodle app, or signed in on the web.",
    zhHant = "示範：從課表點開課程，往下滑到 Moodle，再於 Moodle App 開啟，或開啟已登入的網頁。",
)

// The course demo's steps, as parts of its loop.
private const val COURSE_LOOP_MILLIS = 10_000
private const val SHEET_UP = 0.10f
private const val SHEET_HALF = 0.20f
private const val DRAG_FROM = 0.25f
private const val DRAG_TO = 0.40f
private const val APP_FROM = 0.50f
private const val BROWSER_FROM = 0.73f
private const val SHEET_GONE = 0.94f

@Composable
private fun MoodleCourseDemo(animate: Boolean) {
    // Still: the sheet up, its Moodle section in view.
    val clock = demoClock(animate, COURSE_LOOP_MILLIS, still = 0.425f)
    val t = { clock.value }
    Column(Modifier.demoDescription(courseDemoDescription.resolve()), horizontalAlignment = Alignment.CenterHorizontally) {
        MockScreen {
            MockClassTable()
            // The sheet: below the screen, then at half, then dragged up.
            CourseSheet(
                Modifier.offset {
                    val now = t()
                    val top = when {
                        now >= SHEET_GONE -> MockScreenHeight
                        now < DRAG_FROM -> lerp(MockScreenHeight, 120.dp, span(now, SHEET_UP, SHEET_HALF))
                        else -> lerp(120.dp, 16.dp, span(now, DRAG_FROM, DRAG_TO))
                    }
                    IntOffset(0, top.roundToPx())
                },
                scroll = { span(t(), DRAG_FROM, DRAG_TO) },
            )
            MoodleAppScreen(Modifier.graphicsLayer { alpha = window(t(), APP_FROM, BROWSER_FROM + 0.01f, fade = 0.03f) })
            SignedInBrowser(Modifier.graphicsLayer { alpha = window(t(), BROWSER_FROM, 0.99f, fade = 0.03f) })
            // Tap a course, drag the sheet up, tap Open in Moodle.
            Fingertip(84.dp, 96.dp, shown = { window(t(), 0.02f, 0.10f) }, press = { span(t(), 0.07f, 0.10f) })
            DragFingertip(t)
            Fingertip(196.dp, 40.dp, shown = { window(t(), 0.43f, 0.50f) }, press = { span(t(), 0.47f, 0.50f) })
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            DemoCaption(moodleAppCaption.resolve(), alpha = { window(t(), APP_FROM, BROWSER_FROM + 0.01f, fade = 0.03f) })
            DemoCaption(noAppCaption.resolve(), alpha = { window(t(), BROWSER_FROM, 0.99f, fade = 0.03f) })
        }
    }
}

/** The finger that drags the sheet up: from low on the sheet to near the top. */
@Composable
private fun DragFingertip(t: () -> Float) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .offset {
                val y = lerp(205.dp, 110.dp, span(t(), DRAG_FROM, DRAG_TO))
                IntOffset((110.dp - 14.dp).roundToPx(), (y - 14.dp).roundToPx())
            }
            .size(28.dp)
            .graphicsLayer { alpha = window(t(), 0.22f, 0.42f) * 0.9f }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
    )
}

/** The class table behind the sheet: a few courses in the week's grid. */
@Composable
private fun MockClassTable() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextLine(0.3f, height = 8.dp, strong = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { Box(Modifier.weight(1f)) { TextLine(0.5f, height = 5.dp) } }
        }
        Box(Modifier.fillMaxWidth().height(180.dp)) {
            CourseBlock(courseName.resolve(), colors.primaryContainer, colors.onPrimaryContainer, column = 1, row = 0, rows = 2)
            CourseBlock(null, colors.tertiaryContainer, colors.onTertiaryContainer, column = 0, row = 2, rows = 2)
            CourseBlock(null, colors.secondaryContainer, colors.onSecondaryContainer, column = 2, row = 3, rows = 2)
            CourseBlock(null, colors.tertiaryContainer, colors.onTertiaryContainer, column = 3, row = 1, rows = 1)
        }
    }
}

@Composable
private fun CourseBlock(name: String?, fill: Color, ink: Color, column: Int, row: Int, rows: Int) {
    // Four columns across 196dp, 6dp apart; rows of 34dp.
    val width = 44.5.dp
    Box(
        Modifier
            .offset(x = (width + 6.dp) * column, y = 34.dp * row)
            .width(width)
            .height(34.dp * rows - 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .padding(4.dp),
    ) {
        if (name != null) MockText(name, size = 10.sp, weight = FontWeight.SemiBold, color = ink)
        else TextLine(0.7f, height = 5.dp, color = ink.copy(alpha = 0.4f))
    }
}

/**
 * The course's sheet: its title row stays put while the rest scrolls up by
 * [scroll] (0 to 1) to the Moodle section.
 */
@Composable
private fun CourseSheet(modifier: Modifier, scroll: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .height(MockScreenHeight - 16.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 12.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(28.dp).height(4.dp).clip(CircleShape).background(colors.onSurfaceVariant.copy(alpha = 0.4f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MockText(courseName.resolve(), Modifier.weight(1f), size = 15.sp, weight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.primaryContainer))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            // As tall as it is, past the sheet's bottom, so scrolling brings the rest up.
            Column(
                Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .graphicsLayer { translationY = -scroll() * 96.dp.toPx() },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoCard(stringResource(R.string.course_detail_classroom_label), Modifier.weight(1f))
                    InfoCard(stringResource(R.string.course_detail_time_label), Modifier.weight(1f))
                }
                repeat(3) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { TextLine(0.5f) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { TextLine(0.6f, strong = true) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                MockText(stringResource(R.string.calendar_source_moodle), size = 13.sp, weight = FontWeight.Bold)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    MoodleRow(Icons.Outlined.Campaign, stringResource(R.string.feature_announcements), "3")
                    MoodleRow(Icons.Outlined.Folder, stringResource(R.string.course_detail_files_label), "12")
                    MoodleRow(Icons.AutoMirrored.Outlined.Assignment, stringResource(R.string.course_detail_assignments_label), "4")
                    MoodleRow(Icons.Outlined.BarChart, stringResource(R.string.course_detail_moodle_grades_label), "5")
                    MoodleRow(Icons.Outlined.Groups, stringResource(R.string.course_detail_members_label), "58")
                }
            }
        }
    }
}

@Composable
private fun InfoCard(label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MockText(label, size = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextLine(0.7f, height = 8.dp, strong = true)
    }
}

@Composable
private fun MoodleRow(icon: ImageVector, label: String, count: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        MockText(label, Modifier.weight(1f), size = 11.sp)
        MockText(count, size = 10.sp, color = colors.onSurfaceVariant)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

/** The Moodle app, as it opens: its orange bar over the course page. */
@Composable
private fun MoodleAppScreen(modifier: Modifier) {
    val orange = Color(0xFFF98012)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(44.dp).background(orange).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            MockText("moodle", size = 16.sp, weight = FontWeight.Bold, color = Color.White)
        }
        PageLines()
    }
}

/** The in-app browser on Moodle's page, signed in already. */
@Composable
private fun SignedInBrowser(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            MockText("moodle2.ntust.edu.tw", size = 10.sp, color = colors.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(colors.primaryContainer))
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(colors.secondaryContainer).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                MockText(stringResource(R.string.library_status_signed_in), size = 10.sp, color = colors.onSecondaryContainer)
            }
        }
        PageLines()
    }
}

@Composable
private fun ColumnScope.PageLines() {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextLine(0.6f, height = 9.dp, strong = true)
        listOf(0.9f, 0.8f, 0.85f, 0.5f, 0.9f, 0.7f).forEach { TextLine(it) }
    }
}

private val assignmentName = WhatsNewText(en = "Homework 3", zhHant = "第三次作業")
private val twoDays = WhatsNewText(en = "2 days", zhHant = "2 天")
private val assignmentDemoDescription = WhatsNewText(
    en = "Demo: an assignment opens on its due time, attachments and submission, and an attachment downloads.",
    zhHant = "示範：點開作業，看到截止時間、附件與繳交狀態，並下載附件。",
)

private const val ASSIGNMENT_LOOP_MILLIS = 8_000
private const val DETAIL_IN = 0.12f
private const val DETAIL_SHOWN = 0.22f
private const val DOWNLOAD_FROM = 0.50f
private const val DOWNLOAD_TO = 0.70f
private const val DETAIL_OUT = 0.92f

@Composable
private fun AssignmentDemo(animate: Boolean) {
    // Still: the detail page, nothing downloading.
    val clock = demoClock(animate, ASSIGNMENT_LOOP_MILLIS, still = 0.35f)
    val t = { clock.value }
    MockScreen(assignmentDemoDescription.resolve()) {
        AssignmentList()
        AssignmentDetail(
            Modifier.graphicsLayer {
                val now = t()
                val shown = span(now, DETAIL_IN, DETAIL_SHOWN) * (1f - span(now, DETAIL_OUT, 0.99f))
                translationX = (1f - shown) * size.width
            },
            download = { span(t(), DOWNLOAD_FROM, DOWNLOAD_TO) },
        )
        Fingertip(110.dp, 58.dp, shown = { window(t(), 0.03f, 0.12f) }, press = { span(t(), 0.08f, 0.12f) })
        Fingertip(194.dp, 135.dp, shown = { window(t(), 0.40f, 0.50f) }, press = { span(t(), 0.46f, 0.50f) })
    }
}

/** Home's assignment list the detail opens from. */
@Composable
private fun AssignmentList() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MockText(stringResource(R.string.home_section_upcoming_assignments), size = 14.sp, weight = FontWeight.Bold)
        repeat(4) { index ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerLow).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index == 0) MockText(assignmentName.resolve(), size = 11.sp, weight = FontWeight.SemiBold)
                    else TextLine(0.6f, strong = true)
                    TextLine(0.4f, height = 5.dp)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** The assignment's page, its first attachment downloading by [download] (0 to 1). */
@Composable
private fun AssignmentDetail(modifier: Modifier, download: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().background(colors.surface).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        MockText(assignmentName.resolve(), size = 14.sp, weight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerLow).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                MockText("28", size = 11.sp, weight = FontWeight.Bold, color = colors.onPrimaryContainer)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                MockText(stringResource(R.string.assignment_detail_due), size = 9.sp, color = colors.onSurfaceVariant)
                MockText(stringResource(R.string.assignment_detail_time_left, twoDays.resolve()), size = 11.sp, weight = FontWeight.SemiBold, color = Color(0xFFE8890C))
            }
        }
        MockText(stringResource(R.string.school_mail_attachments), size = 11.sp, weight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerLow).padding(horizontal = 8.dp)) {
            AttachmentRow(Icons.Outlined.PictureAsPdf, download)
            AttachmentRow(Icons.Outlined.Description, download = { 0f })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MockText(stringResource(R.string.assignment_detail_submission), Modifier.weight(1f), size = 11.sp, weight = FontWeight.Bold)
            Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, colors.outlineVariant, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                MockText(stringResource(R.string.assignment_detail_not_submitted), size = 9.sp, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(colors.primary).padding(vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            MockText(stringResource(R.string.assignment_detail_submit_on_moodle), size = 11.sp, weight = FontWeight.SemiBold, color = colors.onPrimary)
        }
    }
}

/** An attachment: arrow to download, a ring while it does, a check once it's done. */
@Composable
private fun AttachmentRow(icon: ImageVector, download: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TextLine(0.7f, strong = true)
            TextLine(0.3f, height = 4.dp)
        }
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ArrowCircleDown, contentDescription = null, tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { alpha = if (download() == 0f) 1f else 0f })
            CircularProgressIndicator(
                progress = download,
                modifier = Modifier.size(16.dp).graphicsLayer { alpha = if (download() in 0.001f..0.999f) 1f else 0f },
                strokeWidth = 2.dp,
            )
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary,
                modifier = Modifier.size(16.dp).graphicsLayer { alpha = if (download() >= 1f) 1f else 0f })
        }
    }
}
