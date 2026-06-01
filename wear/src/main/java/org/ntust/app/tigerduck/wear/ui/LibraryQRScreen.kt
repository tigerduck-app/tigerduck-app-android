package org.ntust.app.tigerduck.wear.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.LibraryApi
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.wear.BuildConfig
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.LibraryQRController
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.data.ScheduleRepository
import org.ntust.app.tigerduck.wear.data.WatchLibraryCredentialStore
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentColor
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding
import kotlin.math.abs

@Composable
fun LibraryQRScreen() {
    val context = LocalContext.current
    val store = remember(context) { WatchLibraryCredentialStore.get(context) }
    val snapshot by store.state.collectAsState(
        initial = WatchLibraryCredentialStore.LibrarySnapshot(null, null, 0L)
    )

    if (!snapshot.isLoggedIn) {
        ScreenScaffold {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = LocalScreenPadding.current),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ListHeader { Text(stringResource(R.string.library_virtual_pass_title)) }
                NotLoggedInState()
            }
        }
    } else {
        LoggedInState(username = snapshot.username)
    }
}

@Composable
private fun NotLoggedInState() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val helper = remember(context) { RemoteActivityHelper(context) }
    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable {
                scope.launch {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setData(Uri.parse("market://details?id=org.ntust.app.tigerduck"))
                    helper.startRemoteActivity(intent, null)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.library_login_qr_prompt),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.watch_open_phone_to_sync),
            color = Color.Gray,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoggedInState(username: String?) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    var isFullscreen by remember { mutableStateOf(false) }

    val qrPaddingRepo = remember(context) { ScheduleRepository.get(context) }
    val qrPaddingDp by qrPaddingRepo.qrPaddingDpFlow.collectAsState(
        initial = SchedulePersistence.DEFAULT_QR_PADDING_DP
    )
    val minSideDp = minOf(config.screenWidthDp, config.screenHeightDp)
    val qrSideDp = if (isFullscreen) {
        qrFullscreenSideDp(minSideDp, qrPaddingDp, config.isScreenRound)
    } else {
        (minSideDp * 0.55f).coerceIn(96f, 160f)
    }
    val qrSidePx = with(density) { qrSideDp.dp.roundToPx() }

    val store = remember(context) { WatchLibraryCredentialStore.get(context) }
    val service = remember(store) { LibraryService(store, isDebugBuild = BuildConfig.DEBUG) }
    val scope = rememberCoroutineScope()
    val controller = remember(service) { LibraryQRController(service, scope) }

    val bitmap by controller.qrBitmap.collectAsState()
    val patternBounds by controller.qrPatternBounds.collectAsState()
    val countdown by controller.countdown.collectAsState()
    val isLoading by controller.isLoading.collectAsState()
    val error by controller.error.collectAsState()

    // Re-render the bitmap at the new size when the user toggles fullscreen,
    // otherwise the upscaled small QR is mushy under a scanner.
    DisposableEffect(controller, qrSidePx) {
        controller.start(qrSidePx)
        onDispose { controller.stop() }
    }

    // Hold the screen at full brightness while a QR is visible on either
    // mode — librarians' scanners read poorly through the dim default.
    KeepScreenBright(active = bitmap != null)

    // Keep the QR — a credential-equivalent token — out of screenshots and
    // screen recordings the whole time the logged-in page is on screen
    // (issue #88).
    SecureScreen()

    if (isFullscreen) {
        FullscreenQR(
            bitmap = bitmap,
            patternBounds = patternBounds,
            qrSideDp = qrSideDp.dp,
            onExit = { isFullscreen = false },
        )
    } else {
        ScreenScaffold {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = LocalScreenPadding.current),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ListHeader { Text(stringResource(R.string.library_virtual_pass_title)) }
                NormalQR(
                    bitmap = bitmap,
                    isLoading = isLoading,
                    error = error,
                    qrSideDp = qrSideDp.dp,
                    onDoubleTap = { isFullscreen = true },
                )
                Spacer(Modifier.height(6.dp))
                if (countdown > 0 && !isLoading) {
                    CountdownRow(countdown)
                }
                if (!username.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = username,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NormalQR(
    bitmap: android.graphics.Bitmap?,
    isLoading: Boolean,
    error: String?,
    qrSideDp: androidx.compose.ui.unit.Dp,
    onDoubleTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(qrSideDp)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.library_qr_content_description),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )

            isLoading -> CircularProgressIndicator()

            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.library_qr_generate_failed),
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = error,
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            else -> Text(
                text = stringResource(R.string.library_qr_not_generated),
                textAlign = TextAlign.Center,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun FullscreenQR(
    bitmap: android.graphics.Bitmap?,
    patternBounds: android.graphics.Rect?,
    qrSideDp: androidx.compose.ui.unit.Dp,
    onExit: () -> Unit,
) {
    // Plain Box (no ScreenScaffold) so the QR can fill the entire watch face
    // when held up to a scanner. Background turns white because most readers
    // cope better with a high-contrast surround than the system dark theme.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onExit() })
            }
            .pointerInput(Unit) {
                // A small vertical flick — up or down — drops back to the
                // normal view. Threshold is generous so a stray finger
                // tremor doesn't bounce the user out mid-scan.
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                    onVerticalDrag = { _, dy ->
                        accumulated += dy
                        if (abs(accumulated) > 24f) onExit()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            // Draw only the pattern bounding box at qrSideDp so the rendered
            // QR's edge matches what the QR-padding settings preview shows.
            // The bitmap also contains a white quiet zone / floor-rounding
            // border around the pattern; cropping via BitmapPainter's
            // srcOffset/srcSize keeps that out of the displayed square. The
            // surrounding white background still gives scanners a quiet zone.
            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            val rect = patternBounds
            val painter = remember(imageBitmap, rect) {
                if (rect == null || rect.isEmpty) {
                    BitmapPainter(
                        image = imageBitmap,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    BitmapPainter(
                        image = imageBitmap,
                        srcOffset = IntOffset(rect.left, rect.top),
                        srcSize = IntSize(rect.width(), rect.height()),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            Image(
                painter = painter,
                contentDescription = stringResource(R.string.library_qr_content_description),
                modifier = Modifier.size(qrSideDp),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun CountdownRow(countdown: Int) {
    val target = countdown.coerceIn(0, LibraryApi.QR_VALID_SECONDS).toFloat() /
            LibraryApi.QR_VALID_SECONDS
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (countdown > 0) 1000 else 0,
            easing = LinearEasing,
        ),
        label = "library_qr_countdown_progress",
    )
    val accent = LocalAccentColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(color = accent.copy(alpha = 0.18f), style = Stroke(width = stroke))
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = stroke),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = countdown.toString(),
            color = Color.Gray,
        )
    }
}

@Composable
private fun KeepScreenBright(active: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, active) {
        val window = activity?.window
        if (window == null || !active) {
            onDispose { }
        } else {
            val previous = window.attributes.screenBrightness
            window.attributes = window.attributes.apply { screenBrightness = 1.0f }
            onDispose {
                window.attributes = window.attributes.apply { screenBrightness = previous }
            }
        }
    }
}

@Composable
private fun SecureScreen() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Mirror of the phone's Settings → Developer → Disable screen-capture
    // protection toggle, delivered via the Wear Data Layer (see
    // WearProtocol.Schedule.KEY_DISABLE_SCREEN_CAPTURE_PROTECTION). The phone
    // only ever publishes `true` from a DEBUG build, so a release-paired
    // watch reads false here under normal operation.
    val repo = remember(context) { ScheduleRepository.get(context) }
    val disabled by repo.disableScreenCaptureProtectionFlow.collectAsState(initial = false)
    DisposableEffect(activity, disabled) {
        val window = activity?.window
        // Snapshot whether the window was already secure (set by another owner
        // or earlier). Only clear FLAG_SECURE on dispose if this composable is
        // the one that added it — otherwise leaving the logged-in page would
        // strip protection this helper never owned.
        val alreadySecure = window != null && (window.attributes.flags and
                WindowManager.LayoutParams.FLAG_SECURE) != 0
        val shouldAdd = window != null && !alreadySecure && !disabled
        if (shouldAdd) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (shouldAdd) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Largest fullscreen-QR square side (in dp) that fits without being clipped by
 * either the round chassis (corners) or the user's configured QR padding.
 *
 * On a round face the chassis caps the side at `diameter / √2` — pushing past
 * that even with zero padding would clip the corners through the round mask.
 * The configured padding is a flat per-side inset and only tightens the
 * result when set large enough to drop below the chassis cap.
 *
 * Shared by [LoggedInState]'s fullscreen rendering and by
 * `QRPaddingSettingsScreen`'s corner-bracket preview so both show the exact
 * same square.
 */
internal fun qrFullscreenSideDp(
    minScreenSideDp: Int,
    qrPaddingDp: Int,
    isScreenRound: Boolean,
): Float {
    val chassisLimit =
        if (isScreenRound) minScreenSideDp / 1.41421356f else minScreenSideDp.toFloat()
    val paddingLimit = (minScreenSideDp - 2 * qrPaddingDp).coerceAtLeast(0).toFloat()
    return minOf(chassisLimit, paddingLimit).coerceAtLeast(96f)
}
