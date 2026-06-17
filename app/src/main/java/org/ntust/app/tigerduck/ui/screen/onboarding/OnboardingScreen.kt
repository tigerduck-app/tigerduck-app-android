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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import org.ntust.app.tigerduck.ui.component.SecureScreen
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
    val isLoggingIn by viewModel.isLoggingIn.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()

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
        // Mask any revealed password before the scroll animation starts.
        // animateScrollToPage flips pagerState.currentPage while the login
        // page (3) is still partly visible, so gating FLAG_SECURE on
        // currentPage alone would clear it mid-transition and expose the
        // plaintext to a screenshot (issue #88). Hiding the password first
        // means there is nothing sensitive on screen once the flag drops.
        passwordVisible = false
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

    // Block screenshots / screen-recording while the NTUST password is
    // revealed as plaintext on the login page (issue #88). FLAG_SECURE is
    // window-wide, so it is only raised while the eye toggle is on AND the
    // login page is the one on screen.
    SecureScreen(secure = passwordVisible && pagerState.currentPage == 3)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                    subtitle = stringResource(R.string.onboarding_welcome_subtitle),
                    actions = {
                        Button(
                            onClick = { goToPage(1) },
                            modifier = Modifier.fillMaxWidth(0.6f)
                        ) { Text(stringResource(R.string.action_next)) }
                    },
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
                }

                1 -> OnboardingPageScaffold(
                    iconContent = { FlashingShieldLockIcon(tint = onboardingBlue()) },
                    title = stringResource(R.string.onboarding_privacy_title),
                    subtitle = stringResource(R.string.onboarding_privacy_subtitle),
                    actions = {
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
                    },
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
                }

                2 -> OnboardingPageScaffold(
                    iconContent = {
                        if (isFdroidFlavor) {
                            PulsingIcon(
                                icon = Icons.Filled.Info,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        } else {
                            PulsingIcon(
                                icon = Icons.Filled.Watch,
                                tint = onboardingRed(),
                            )
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
                    actions = {
                        Button(
                            onClick = { goToPage(3) },
                            modifier = Modifier.fillMaxWidth(0.6f)
                        ) { Text(stringResource(R.string.action_next)) }
                    },
                ) {}

                3 -> {
                    val focusManager = LocalFocusManager.current
                    OnboardingPageScaffold(
                        iconContent = { PersonKeyBadgeIcon(tint = onboardingGreen()) },
                        title = stringResource(R.string.onboarding_sign_in_title),
                        subtitle = stringResource(R.string.onboarding_sign_in_subtitle),
                        actions = {
                            // Order matches iOS: Skip sits above the prominent
                            // sign-in button so the affirmative action is the
                            // last thing the eye lands on before tapping.
                            TextButton(onClick = { goToPage(4) }) {
                                Text(
                                    stringResource(R.string.onboarding_skip_for_now),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY)
                                )
                            }
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.login(studentId, password) { goToPage(4) }
                                },
                                enabled = studentId.isNotBlank() && password.isNotBlank() && !isLoggingIn,
                                modifier = Modifier.fillMaxWidth(0.8f),
                            ) {
                                if (isLoggingIn) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(stringResource(R.string.onboarding_sign_in_button))
                                }
                            }
                        },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(0.8f),
                        ) {
                            OutlinedAccountIdField(
                                value = studentId,
                                onValueChange = { raw ->
                                    studentId = raw.filter { ch -> !ch.isWhitespace() }.uppercase()
                                },
                                label = stringResource(R.string.sign_in_student_id),
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
                                label = { Text(stringResource(R.string.sign_in_password)) },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                trailingIcon = if (!isLoggingIn) {
                                    {
                                        PasswordTrailingIcons(
                                            password = password,
                                            passwordVisible = passwordVisible,
                                            onClear = { password = ""; passwordVisible = false },
                                            onToggleVisibility = {
                                                passwordVisible = !passwordVisible
                                            },
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
                                    .semantics { contentType = ContentType.Password },
                            )
                            if (loginError != null) {
                                Text(
                                    text = loginError!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (isSignedIn) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = onboardingGreen(),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.action_done),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = onboardingGreen(),
                                    )
                                }
                            }
                        }
                    }
                }

                4 -> PermissionsPage(
                    systemPermissions = viewModel.systemPermissions,
                    onContinue = { goToPage(5) },
                )

                5 -> OnboardingPage(
                    icon = Icons.Filled.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.onboarding_ready_title),
                    subtitle = stringResource(R.string.onboarding_ready_subtitle),
                    actions = {
                        Button(
                            onClick = { viewModel.completeOnboarding() },
                            modifier = Modifier.fillMaxWidth(0.6f)
                        ) { Text(stringResource(R.string.onboarding_start_button)) }
                    },
                ) {}
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
        BellBadgeIcon(tint = onboardingOrange())
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
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    OnboardingPageScaffold(
        iconContent = { PulsingIcon(icon = icon, tint = iconTint) },
        title = title,
        subtitle = subtitle,
        actions = actions,
        content = content,
    )
}

@Composable
private fun OnboardingPageScaffold(
    iconContent: @Composable () -> Unit,
    title: String,
    subtitle: String,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    // Layout: one big scrollable column whose inner content is forced to be
    // at least the viewport tall. With Arrangement.SpaceBetween, that means
    //   – short body  → actions sit at the visible bottom
    //   – long body   → inner column grows past the viewport, actions get
    //                    pushed below the fold but stay reachable by scroll
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        val viewportHeight = this.maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = 32.dp)
                    .padding(top = 64.dp, bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    iconContent()
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY),
                    )
                    Spacer(Modifier.height(16.dp))
                    content()
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions()
                }
            }
        }
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

// iOS system color palette, dark-mode adapted. Used for the per-page accent
// tints on the onboarding pages so the icons match the iOS app exactly
// (privacy = blue, watch = red, login = green, notifications = orange).
@Composable
private fun onboardingBlue(): Color =
    if (isSystemInDarkTheme()) Color(0xFF0A84FF) else Color(0xFF007AFF)

@Composable
private fun onboardingRed(): Color =
    if (isSystemInDarkTheme()) Color(0xFFFF453A) else Color(0xFFFF3B30)

@Composable
private fun onboardingGreen(): Color =
    if (isSystemInDarkTheme()) Color(0xFF32D74B) else Color(0xFF34C759)

@Composable
private fun onboardingOrange(): Color =
    if (isSystemInDarkTheme()) Color(0xFFFF9F0A) else Color(0xFFFF9500)

// Layered shield + inner lock: matches the iOS `OnboardingPageView.layerFlash`
// path. The shield holds steady at the accent color while the inner lock
// pulses its alpha — reads as a slow "flash" on the lock without disturbing
// the surrounding shield silhouette.
@Composable
private fun FlashingShieldLockIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "shield-lock-flash")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shield-lock-flash-fraction",
    )
    val lockAlpha = 0.3f + 0.7f * pulse
    Box(
        modifier = modifier.size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = Color.White.copy(alpha = lockAlpha),
            modifier = Modifier
                .size(42.dp)
                .offset(y = (-2).dp),
        )
    }
}

// Person silhouette with a key badge in the lower-right — Material's closest
// approximation of iOS `person.badge.key.fill`. Whole composition pulses
// together (matching the iOS .symbolEffect(.pulse) on the single SF symbol).
@Composable
private fun PersonKeyBadgeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "person-key-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "person-key-pulse-fraction",
    )
    val alpha = 0.45f + 0.55f * pulse
    val scale = 0.94f + 0.08f * pulse
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(30.dp)
                .clip(CircleShape)
                .background(background)
                .padding(2.dp)
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// Bell with a small notification dot in the upper-right — Material's closest
// approximation of iOS `bell.badge.fill`. Pulses as one composition.
@Composable
private fun BellBadgeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "bell-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bell-pulse-fraction",
    )
    val alpha = 0.45f + 0.55f * pulse
    val scale = 0.94f + 0.08f * pulse
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
    ) {
        Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 10.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(background)
                .padding(2.dp)
                .clip(CircleShape)
                .background(tint),
        )
    }
}
