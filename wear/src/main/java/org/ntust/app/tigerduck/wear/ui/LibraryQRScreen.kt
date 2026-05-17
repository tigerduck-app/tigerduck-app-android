package org.ntust.app.tigerduck.wear.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import org.ntust.app.tigerduck.wear.data.WatchLibraryCredentialStore
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentColor
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding

@Composable
fun LibraryQRScreen() {
    val context = LocalContext.current
    val store = remember(context) { WatchLibraryCredentialStore.get(context) }
    val snapshot by store.state.collectAsState(
        initial = WatchLibraryCredentialStore.LibrarySnapshot(null, null, 0L)
    )

    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = LocalScreenPadding.current),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text(stringResource(R.string.library_virtual_pass_title)) }
            if (!snapshot.isLoggedIn) {
                NotLoggedInState()
            } else {
                LoggedInState()
            }
        }
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
private fun LoggedInState() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    // QR is square; size it from the smallest screen dimension. With the title
    // above and the bare countdown ring below (no text, no username), ~65% of
    // the min dimension fits on round and rectangular Wear displays alike.
    val qrSideDp = ((minOf(config.screenWidthDp, config.screenHeightDp)) * 0.65f).coerceIn(96f, 180f)
    val qrSidePx = with(density) { qrSideDp.dp.roundToPx() }

    val store = remember(context) { WatchLibraryCredentialStore.get(context) }
    val service = remember(store) { LibraryService(store, isDebugBuild = BuildConfig.DEBUG) }
    val scope = rememberCoroutineScope()
    val controller = remember(service) { LibraryQRController(service, scope) }

    val bitmap by controller.qrBitmap.collectAsState()
    val countdown by controller.countdown.collectAsState()
    val isLoading by controller.isLoading.collectAsState()
    val error by controller.error.collectAsState()

    DisposableEffect(controller, qrSidePx) {
        controller.start(qrSidePx)
        onDispose { controller.stop() }
    }

    Box(
        modifier = Modifier.size(qrSideDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        when {
            current != null -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = stringResource(R.string.library_qr_content_description),
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
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
                // The watch swallowed the underlying cause before this change,
                // which made network/credential issues impossible to diagnose
                // without logcat. Show whatever the controller reports so the
                // user can see "Failed to connect", "401", "Bad username", etc.
                Text(
                    text = error ?: "",
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

    Spacer(Modifier.height(6.dp))
    if (countdown > 0 && !isLoading) {
        CountdownIndicator(countdown)
    }
}

@Composable
private fun CountdownIndicator(countdown: Int) {
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
}
