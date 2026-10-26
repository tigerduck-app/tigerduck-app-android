package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.ui.AppProcessing

/**
 * The app-wide spinner, mounted once at the app's root: [ProcessingOverlay] while any of
 * [processing]'s holds is started. It comes up only once work has run [graceMs], so a wait that
 * ends at once never flashes it, and goes the moment the last hold stops. Collected for as long as
 * it is composed, not only while the app is in front, so coming back from a page opened meanwhile
 * never shows a spinner left from before. While [questionShowing], a question the work may be
 * waiting on such as a pin-mismatch prompt, it stays down, so nothing covers the answer.
 */
@Composable
fun AppProcessingHost(processing: AppProcessing, graceMs: Long = PROCESSING_GRACE_MS, questionShowing: Boolean = false) {
    val busy = processing.holds.collectAsState().value > 0
    var graceOver by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        graceOver = false
        if (busy) {
            delay(graceMs)
            graceOver = true
        }
    }
    if (busy && graceOver && !questionShowing) ProcessingOverlay()
}

/**
 * A spinner and the line saying the app is busy and not to close it, in a window of its own over
 * a scrim, so nothing under it takes a touch. Back does not take it down: it stays exactly as long
 * as the work does, which [AppProcessingHost] keeps short, and Back would otherwise only move the
 * screen under it while the work goes on. TalkBack reads the line as it comes up.
 */
@Composable
fun ProcessingOverlay(text: String = stringResource(R.string.app_processing_keep_open)) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        ProcessingCard(text)
    }
}

/** The card [ProcessingOverlay] shows: the spinner over its line, read together. */
@Composable
internal fun ProcessingCard(text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 200.dp)
                .padding(horizontal = 32.dp, vertical = 28.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Its progress is the card's, so TalkBack stops once, on the line.
            CircularProgressIndicator(Modifier.clearAndSetSemantics {})
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** How long work runs before the spinner comes up, so a wait that ends at once never flashes it. */
const val PROCESSING_GRACE_MS = 300L
