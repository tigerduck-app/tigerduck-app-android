// "Export class table screenshot": the timetable drawn into a PNG off
// screen, then handed to the system share sheet.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.io.File

/**
 * A phone's width whatever the device, so an export from a tablet or a
 * foldable is still a picture that reads at a glance when it lands on a phone.
 */
private val EXPORT_WIDTH = 390.dp

/**
 * Drawn at a fixed 3x rather than the device's own density, so every phone
 * produces the same 1170px-wide image — sharp on any screen it is sent to.
 */
private const val EXPORT_DENSITY = 3f

/** Under cacheDir, and served by the app's FileProvider (`file_provider_paths.xml`). */
private const val EXPORT_DIR = "class_table_export"

/**
 * The class table as it goes into an exported image: whose it is and which
 * term above, then the same grid the screen draws.
 *
 * The grid is the live [TimetableGrid], not a copy, so the image keeps
 * whatever the screen shows — colours, custom and abbreviated names, the
 * start / 節 / end period column, the room hint when it is switched on. Only
 * the assignment badge stays out: it marks work still due, a reminder for
 * whoever is looking at the screen today, not part of the timetable someone
 * is being shown.
 */
@Composable
internal fun ClassTableExportCard(
    viewModel: ClassTableViewModel,
    courses: List<Course>,
    semesterLabel: String,
    studentId: String?,
    showRoomHints: Boolean,
    weekdays: List<Int>,
    periods: List<TimetablePeriod>,
) {
    Column(
        modifier = Modifier
            .width(EXPORT_WIDTH)
            // Opaque: the cells are translucent course colours, and over a
            // transparent PNG they would take on whatever the viewer puts
            // behind them.
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = semesterLabel,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (!studentId.isNullOrBlank()) {
                Text(
                    text = studentId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TimetableGrid(
            viewModel = viewModel,
            courses = courses,
            showRoomHints = showRoomHints,
            weekdays = weekdays,
            periods = periods,
            courseNosWithAssignments = emptySet(),
        )
    }
}

/**
 * Composes [content] where nothing can see it and hands back what it drew.
 *
 * The content is laid out at its own size inside a zero-size box, and its
 * drawing is recorded into a graphics layer that is never drawn itself — so
 * the screen does not flash the card while the export is made. Nothing in
 * here may be tapped: it has no area on screen to be tapped in.
 *
 * Captures a few frames after the first recording, not on it: a course name
 * that has to be shortened to fit settles one layout pass later (see
 * [ClassTableCourseNameText]), and every redraw records into the layer again.
 */
@Composable
internal fun OffscreenCapture(
    onCaptured: suspend (ImageBitmap) -> Unit,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val recorded = remember { CompletableDeferred<Unit>() }
    val fontScale = LocalDensity.current.fontScale
    Box(
        Modifier
            .size(0.dp)
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
    ) {
        Box(
            Modifier.drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                recorded.complete(Unit)
            }
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(EXPORT_DENSITY, fontScale),
                content = content,
            )
        }
    }
    LaunchedEffect(Unit) {
        recorded.await()
        repeat(2) { withFrameNanos { } }
        onCaptured(layer.toImageBitmap())
    }
}

/**
 * "課表_114-2_B11315000.png": the name the file keeps wherever it is saved or
 * sent, so it says what it is and whose. Underscores, never spaces — between
 * the parts and inside them ("Class_table") — so the name survives a URL or a
 * command line without quoting or %20. The student id is left out, not left
 * as a trailing underscore, when there is none.
 */
internal fun classTableExportFileName(title: String, semesterLabel: String, studentId: String?): String =
    listOf(title, semesterLabel, studentId.orEmpty())
        // Path separators, the characters FAT-formatted storage refuses, and
        // control characters — a translation could carry any of them.
        .map { part ->
            part.filterNot { it in "/\\:*?\"<>|" || it.isISOControl() }
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString("_")
        }
        .filter { it.isNotEmpty() }
        .joinToString("_") + ".png"

private val WHITESPACE = Regex("\\s+")

/** Writes [bitmap] as a PNG under the export directory and returns the file. */
internal suspend fun writeClassTableImage(context: Context, bitmap: ImageBitmap, fileName: String): File =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        // A layer may hand back a hardware bitmap, whose pixels live on the
        // GPU; copy them down before encoding.
        val android = bitmap.asAndroidBitmap().let {
            if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
        val written = file.outputStream().use { android.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(written) { "PNG encoding failed" }
        file
    }

/**
 * Hands [file] to the system share sheet, where the user can save it (Files,
 * Photos, Drive) or send it anywhere.
 */
internal fun shareClassTableImage(context: Context, file: File, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The ClipData is what carries the read grant to the share sheet itself,
    // which needs it to show the image's preview.
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
