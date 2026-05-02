package org.ntust.app.tigerduck.ui.screen.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.OutlinedAccountIdField
import org.ntust.app.tigerduck.ui.screen.settings.NotificationSetupContent
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import androidx.compose.ui.res.stringResource

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val pageCount = 5
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val isLoggingIn by viewModel.isLoggingIn.collectAsState()
    val loginError by viewModel.loginError.collectAsState()

    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false
        ) { page ->
            when (page) {
                0 -> OnboardingPage(
                    icon = Icons.Filled.School,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.onboarding_welcome_title),
                    subtitle = stringResource(R.string.onboarding_welcome_subtitle)
                ) {
                    Button(
                        onClick = { goToPage(1) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text(stringResource(R.string.action_next)) }
                }

                1 -> OnboardingPage(
                    icon = Icons.Filled.Key,
                    iconTint = Color(0xFF2E7D32),
                    title = stringResource(R.string.onboarding_login_title),
                    subtitle = stringResource(R.string.onboarding_login_subtitle)
                ) {
                    val focusManager = LocalFocusManager.current
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        OutlinedAccountIdField(
                            value = studentId,
                            onValueChange = { raw ->
                                studentId = raw.filter { ch -> !ch.isWhitespace() }.uppercase()
                            },
                            label = stringResource(R.string.login_student_id),
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                            autofillHint = android.view.View.AUTOFILL_HINT_USERNAME,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.login_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            trailingIcon = if (!isLoggingIn && password.isNotEmpty()) {
                                {
                                    IconButton(onClick = { password = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Cancel,
                                            contentDescription = stringResource(R.string.action_clear_text),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else null,
                            enabled = !isLoggingIn,
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (studentId.isNotBlank() && password.isNotBlank() && !isLoggingIn) {
                                        viewModel.login(studentId, password) { goToPage(2) }
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentType = ContentType.Password }
                        )
                        if (loginError != null) {
                            Text(
                                text = loginError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.login(studentId, password) { goToPage(2) }
                            },
                            enabled = studentId.isNotBlank() && password.isNotBlank() && !isLoggingIn,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isLoggingIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.onboarding_login_button))
                            }
                        }
                        TextButton(onClick = { goToPage(2) }) {
                            Text(
                                stringResource(R.string.onboarding_skip_for_now),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY)
                            )
                        }
                    }
                }

                2 -> OnboardingPage(
                    icon = Icons.Filled.Tune,
                    iconTint = Color(0xFFEF6C00),
                    title = stringResource(R.string.onboarding_choose_features_title),
                    subtitle = stringResource(R.string.onboarding_choose_features_subtitle)
                ) {
                    Button(
                        onClick = { goToPage(3) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text(stringResource(R.string.action_next)) }
                }

                3 -> PermissionsPage(
                    systemPermissions = viewModel.systemPermissions,
                    onContinue = { goToPage(4) },
                )

                4 -> OnboardingPage(
                    icon = Icons.Filled.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.onboarding_ready_title),
                    subtitle = stringResource(R.string.onboarding_ready_subtitle)
                ) {
                    Button(
                        onClick = { viewModel.completeOnboarding() },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text(stringResource(R.string.onboarding_start_button)) }
                }
            }
        }

        // Page indicator
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(pageCount) { i ->
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == i)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                        )
                        .size(if (pagerState.currentPage == i) 10.dp else 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionsPage(
    systemPermissions: org.ntust.app.tigerduck.notification.SystemPermissions,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 72.dp, bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulsingIcon(
            icon = Icons.Filled.Notifications,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.onboarding_permissions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        NotificationSetupContent(
            systemPermissions = systemPermissions,
            finishLabel = stringResource(R.string.action_next),
            onFinish = onContinue,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun OnboardingPage(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 120.dp, bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PulsingIcon(icon = icon, tint = iconTint)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY)
        )
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun PulsingIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "onboarding-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "onboarding-pulse-fraction",
    )
    val alpha = 0.45f + 0.55f * pulse
    val scale = 0.94f + 0.08f * pulse
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
    )
}
