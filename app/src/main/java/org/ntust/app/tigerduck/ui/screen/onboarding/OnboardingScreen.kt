package org.ntust.app.tigerduck.ui.screen.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.OutlinedAccountIdField
import org.ntust.app.tigerduck.ui.component.PasswordTrailingIcons
import org.ntust.app.tigerduck.ui.screen.settings.NotificationSetupContent
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

private const val URL_TIGERDUCK_WEBSITE = "https://tigerduck.app"
private const val URL_TIGERDUCK_GITHUB = "https://github.com/tigerduck-app"
private const val URL_PRIVACY_POLICY = "https://tigerduck.app/privacy-policy"
private const val URL_DELETE_ACCOUNT = "https://tigerduck.app/delete-account"

private val isFdroidFlavor: Boolean
    get() = BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    // Pages: 0 Welcome → 1 Privacy → 2 Flavor info → 3 Login → 4 Permissions → 5 Ready.
    // The original "choose features" page is intentionally commented out below.
    val pageCount = 6
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val isLoggingIn by viewModel.isLoggingIn.collectAsState()
    val loginError by viewModel.loginError.collectAsState()

    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var privacyPolicyAccepted by remember { mutableStateOf(false) }
    var deleteAccountAccepted by remember { mutableStateOf(false) }

    // Track the furthest page the user has reached. The bottom-left forward
    // arrow is enabled only for pages already visited, so per-page gating
    // (privacy checkboxes, login) can't be bypassed on first traversal but
    // re-navigation between visited pages stays free.
    var maxVisitedPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage > maxVisitedPage) {
            maxVisitedPage = pagerState.currentPage
        }
    }

    // Re-evaluated by the forward arrow on every recomposition so unchecking
    // the privacy boxes after visiting a later page can't re-cross the gate.
    val bothAccepted by remember {
        derivedStateOf { privacyPolicyAccepted && deleteAccountAccepted }
    }

    // The forward arrow may advance past a page only when (a) the page has
    // been visited before AND (b) any per-page gate on that page still holds.
    // Without (b), a user who reached the login page once could come back to
    // page 1, uncheck the privacy boxes, and forward-arrow past them again.
    fun canAdvanceFrom(page: Int): Boolean {
        if (page >= maxVisitedPage) return false
        if (page == 1 && !bothAccepted) return false
        return true
    }

    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    // System back: walk one page back inside onboarding; on page 0 fall
    // through to the same two-press-to-exit pattern MainNavigation uses
    // (we're rendered before MainNavigation, so its BackHandler isn't live
    // yet — duplicate the logic here so the UX is consistent).
    val context = LocalContext.current
    val backPressExitHint = stringResource(R.string.app_exit_confirm_toast)
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (pagerState.currentPage > 0) {
            goToPage(pagerState.currentPage - 1)
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPressMs < 2000L) {
                (context as? Activity)?.finish()
            } else {
                lastBackPressMs = now
                Toast.makeText(context, backPressExitHint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {
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
                    Text(
                        text = stringResource(R.string.onboarding_welcome_description),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY),
                        modifier = Modifier.fillMaxWidth(0.9f),
                    )
                    Spacer(Modifier.height(4.dp))
                    LinkRow(
                        icon = Icons.Filled.Public,
                        label = stringResource(R.string.onboarding_welcome_website_label),
                        url = URL_TIGERDUCK_WEBSITE,
                    )
                    LinkRow(
                        icon = Icons.Filled.Code,
                        label = stringResource(R.string.onboarding_welcome_github_label),
                        url = URL_TIGERDUCK_GITHUB,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { goToPage(1) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text(stringResource(R.string.action_next)) }
                }

                1 -> OnboardingPage(
                    icon = Icons.Filled.PrivacyTip,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.onboarding_privacy_title),
                    subtitle = stringResource(R.string.onboarding_privacy_subtitle)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        PrivacyCheckRow(
                            label = stringResource(R.string.onboarding_privacy_policy_label),
                            url = URL_PRIVACY_POLICY,
                            checked = privacyPolicyAccepted,
                            onCheckedChange = { privacyPolicyAccepted = it },
                        )
                        PrivacyCheckRow(
                            label = stringResource(R.string.onboarding_privacy_delete_account_label),
                            url = URL_DELETE_ACCOUNT,
                            checked = deleteAccountAccepted,
                            onCheckedChange = { deleteAccountAccepted = it },
                        )
                    }
                    if (!bothAccepted) {
                        Text(
                            text = stringResource(R.string.onboarding_privacy_continue_hint),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY),
                        )
                    }
                    Button(
                        onClick = { goToPage(2) },
                        enabled = bothAccepted,
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text(stringResource(R.string.action_next)) }
                }

                2 -> OnboardingPageScaffold(
                    iconContent = {
                        if (isFdroidFlavor) {
                            PulsingIcon(
                                icon = Icons.Filled.Info,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        } else {
                            WatchAnimatedIcon(tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    title = stringResource(
                        if (isFdroidFlavor) R.string.onboarding_flavor_fdroid_title
                        else R.string.onboarding_flavor_play_title
                    ),
                    subtitle = stringResource(
                        if (isFdroidFlavor) R.string.onboarding_flavor_fdroid_description
                        else R.string.onboarding_flavor_play_description
                    ),
                ) {
                    Button(
                        onClick = { goToPage(3) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text(stringResource(R.string.action_next)) }
                }

                3 -> OnboardingPage(
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
                            enabled = !isLoggingIn,
                            autofillHint = android.view.View.AUTOFILL_HINT_USERNAME,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.login_password)) },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = if (!isLoggingIn) {
                                {
                                    PasswordTrailingIcons(
                                        password = password,
                                        passwordVisible = passwordVisible,
                                        onClear = { password = ""; passwordVisible = false },
                                        onToggleVisibility = { passwordVisible = !passwordVisible },
                                    )
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
                                        viewModel.login(studentId, password) { goToPage(4) }
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
                                viewModel.login(studentId, password) { goToPage(4) }
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
                        TextButton(onClick = { goToPage(4) }) {
                            Text(
                                stringResource(R.string.onboarding_skip_for_now),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY)
                            )
                        }
                    }
                }

                // Original "choose features" page — temporarily disabled per
                // product request. Restore by re-adding the case and bumping
                // pageCount accordingly.
                // 2 -> OnboardingPage(
                //     icon = Icons.Filled.Tune,
                //     iconTint = Color(0xFFEF6C00),
                //     title = stringResource(R.string.onboarding_choose_features_title),
                //     subtitle = stringResource(R.string.onboarding_choose_features_subtitle)
                // ) {
                //     Button(
                //         onClick = { goToPage(3) },
                //         modifier = Modifier.fillMaxWidth(0.6f)
                //     ) { Text(stringResource(R.string.action_next)) }
                // }

                4 -> PermissionsPage(
                    systemPermissions = viewModel.systemPermissions,
                    onContinue = { goToPage(5) },
                )

                5 -> OnboardingPage(
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

        // Navigation arrows. Forward is gated to pages already visited so
        // per-page requirements (privacy/login) still apply on first
        // traversal.
        FilledTonalIconButton(
            onClick = { goToPage(pagerState.currentPage - 1) },
            enabled = pagerState.currentPage > 0,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        FilledTonalIconButton(
            onClick = { goToPage(pagerState.currentPage + 1) },
            enabled = canAdvanceFrom(pagerState.currentPage),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.action_next),
            )
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
private fun LinkRow(
    icon: ImageVector,
    label: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        onClick = { openUrl(context, url) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(0.9f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PrivacyCheckRow(
    label: String,
    url: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { openUrl(context, url) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val uri = url.toUri()
    runCatching {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
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
    content: @Composable ColumnScope.() -> Unit,
) {
    OnboardingPageScaffold(
        iconContent = { PulsingIcon(icon = icon, tint = iconTint) },
        title = title,
        subtitle = subtitle,
        content = content,
    )
}

@Composable
private fun OnboardingPageScaffold(
    iconContent: @Composable () -> Unit,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 32.dp)
            .padding(top = 64.dp, bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        iconContent()
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

// Wear OS onboarding hint icon: a gently rocking + breathing watch.
// The pulse and rotation use slightly different periods so the motion never
// looks like a single rigid cycle — gives a subtle "alive on the wrist" feel.
@Composable
private fun WatchAnimatedIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "watch-animation")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "watch-pulse",
    )
    val rotation by transition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "watch-rotation",
    )
    val alpha = 0.6f + 0.4f * pulse
    val scale = 0.96f + 0.06f * pulse
    Icon(
        imageVector = Icons.Filled.Watch,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            },
    )
}
