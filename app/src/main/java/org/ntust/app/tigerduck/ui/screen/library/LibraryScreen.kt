package org.ntust.app.tigerduck.ui.screen.library

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
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

    LaunchedEffect(Unit) { viewModel.load() }

    // Key the remembered form state to the suggested username so that
    // logging out + signing in with a different NTUST account pre-fills the
    // newly stored ID instead of stale text from the previous session.
    var libUsername by remember(viewModel.suggestedUsername) {
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
                onUsernameChange = { libUsername = it.uppercase() },
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(horizontal = 24.dp),
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
        animationSpec = tween(durationMillis = if (countdown > 0) 1000 else 0, easing = LinearEasing),
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
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.library_login_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii
                )
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.library_login_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                // Match the NTUST login fields (LoginSheet / OnboardingScreen):
                // ASCII-only keyboard + no autocorrect. The onPasswordChange
                // filter also strips any non-ASCII the IME or paste slips in.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
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
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
    }
}
