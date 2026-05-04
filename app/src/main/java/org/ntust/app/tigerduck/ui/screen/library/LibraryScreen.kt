package org.ntust.app.tigerduck.ui.screen.library

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.OutlinedAccountIdField
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val qrBitmap by viewModel.qrBitmap.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()
    val isLoadingQR by viewModel.isLoadingQR.collectAsStateWithLifecycle()
    val isLoggingIn by viewModel.isLoggingIn.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val storedUsername by viewModel.storedUsername.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current

    // Lock the activity to whatever orientation it is in when the user enters
    // the library page — the rotating QR is meant to be held up to a scanner,
    // and an unexpected rotate animation mid-scan disrupts that. Restore the
    // previous request on exit so MainActivity's rotation preference resumes.
    val context = LocalContext.current
    val activity = remember(context) {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return@remember c
            c = c.baseContext
        }
        null
    }
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            if (previous != null) activity.requestedOrientation = previous
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) { viewModel.load() }

    // Key on `storedUsername` (a real StateFlow) instead of the plain
    // suggestedUsername getter — Compose can't observe a non-State property,
    // so keying on it doesn't re-init the field after a logout + sign-in
    // with a different NTUST account.
    var libUsername by remember(storedUsername) {
        mutableStateOf(viewModel.suggestedUsername)
    }
    var libPassword by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(title = stringResource(R.string.feature_library)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isLoggedIn) Color(0xFF34C759) else Color.Gray)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isLoggedIn) stringResource(R.string.library_status_logged_in)
                    else stringResource(R.string.library_status_not_logged_in),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
            }
        }

        errorMessage?.let { msg ->
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (isLoggedIn) {
            LibraryQRCodeCard(
                qrBitmap = qrBitmap,
                username = storedUsername,
                countdown = countdown,
                isLoadingQR = isLoadingQR,
            )
        } else {
            LoginPromptCard(
                username = libUsername,
                password = libPassword,
                isLoggingIn = isLoggingIn,
                onUsernameChange = { libUsername = it.filter { ch -> !ch.isWhitespace() } },
                // Library credentials are provisioned against an ASCII-only
                // backend — strip anything the IME or paste inserts outside
                // printable ASCII so silent non-ASCII chars can't poison the
                // login attempt.
                onPasswordChange = { libPassword = it.filter { ch -> ch.code in 0x20..0x7E } },
                onSubmit = { viewModel.loginAndRefresh(libUsername, libPassword) }
            )
        }

        // 討論小間 / 圖書館講座 — hidden until the backing data sources are
        // available. Re-enable the row below when ready.
        // Row(
        //     modifier = Modifier
        //         .fillMaxWidth()
        //         .padding(horizontal = 16.dp),
        //     horizontalArrangement = Arrangement.spacedBy(12.dp)
        // ) {
        //     FeatureCard("討論小間", "即將推出", modifier = Modifier.weight(1f))
        //     FeatureCard("圖書館講座", "即將推出", modifier = Modifier.weight(1f))
        // }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LibraryQRCodeCard(
    qrBitmap: android.graphics.Bitmap?,
    username: String?,
    countdown: Int,
    isLoadingQR: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.library_virtual_pass_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                username?.let {
                    Text(
                        text = "|",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                    )
                }
            }

            // In landscape the card stretches the whole width and the
            // square QR ends up taller than the viewport, so the user has
            // to scroll to see the bottom — useless for a code that
            // needs to be scanned. Cap the QR side to what can fit
            // vertically (minus header + countdown + paddings) so it
            // stays whole on screen.
            val config = LocalConfiguration.current
            val isLandscape = config.screenWidthDp > config.screenHeightDp
            val qrSideDp = if (isLandscape) {
                (config.screenHeightDp - 220).coerceIn(160, config.screenWidthDp - 48).dp
            } else {
                (config.screenWidthDp - 80).coerceAtLeast(160).dp
            }
            Box(
                modifier = Modifier
                    .size(qrSideDp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoadingQR && qrBitmap == null -> {
                        CircularProgressIndicator()
                    }

                    qrBitmap != null -> {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.library_qr_content_description),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None,
                        )
                    }

                    else -> {
                        Text(
                            stringResource(R.string.library_qr_not_generated),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                        )
                    }
                }
            }

            if (countdown > 0 && !isLoadingQR) {
                CountdownIndicator(countdown = countdown)
            }
        }
    }
}

@Composable
private fun CountdownIndicator(countdown: Int) {
    val target = (countdown.coerceIn(0, LibraryViewModel.QR_VALID_SECONDS)).toFloat() /
            LibraryViewModel.QR_VALID_SECONDS
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (countdown > 0) 1000 else 0,
            easing = LinearEasing
        ),
        label = "library_qr_countdown_progress"
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = 2.5.dp.toPx()
            drawCircle(color = track, style = Stroke(width = stroke))
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = stringResource(R.string.library_qr_refresh_in_seconds, countdown),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
    }
}

@Composable
private fun LoginPromptCard(
    username: String,
    password: String,
    isLoggingIn: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val passwordFocusRequester = remember { FocusRequester() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.library_login_prompt_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                stringResource(R.string.library_login_prompt_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
            OutlinedAccountIdField(
                value = username,
                onValueChange = onUsernameChange,
                label = stringResource(R.string.library_login_username),
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
                onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                autofillHint = android.view.View.AUTOFILL_HINT_USERNAME,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.library_login_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                trailingIcon = if (!isLoggingIn && password.isNotEmpty()) {
                    {
                        IconButton(onClick = { onPasswordChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Cancel,
                                contentDescription = stringResource(R.string.action_clear_text),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocusRequester)
                    .semantics { contentType = ContentType.Password },
                // KeyboardType.Password disables the IME suggestion strip and
                // typed-character preview. The onPasswordChange filter still
                // strips any non-ASCII the IME or paste slips in.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = {
                    if (username.isNotBlank() && password.isNotBlank() && !isLoggingIn) onSubmit()
                }),
            )
            Button(
                onClick = onSubmit,
                enabled = username.isNotBlank() && password.isNotBlank() && !isLoggingIn,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.library_login_button))
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
    }
}
