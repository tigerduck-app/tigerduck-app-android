// The onboarding pager: page order, the permission and privacy gates,
// and what 'done' means. Page chrome is in OnboardingComponents.kt, the
// animated illustrations in OnboardingIcons.kt.

package org.ntust.app.tigerduck.ui.screen.onboarding

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
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
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

private const val URL_TIGERDUCK_WEBSITE = "https://tigerduck.app"
private const val URL_TIGERDUCK_GITHUB = "https://github.com/tigerduck-app"
private const val URL_PRIVACY_POLICY = "https://tigerduck.app/privacy-policy"
private const val URL_DELETE_ACCOUNT = "https://tigerduck.app/delete-account"
private const val URL_LEARN_MORE_BACKEND = "https://tigerduck.app/learn-more-about-backend"
private val isFdroidFlavor: Boolean
    get() = BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    // Pages (Play): 0 Welcome → 1 Privacy → 2 Sync → 3 Flavor info → 4 Login → 5 Permissions → 6 Ready.
    // Pages (fdroid): 0 Welcome → 1 Privacy → 2 Flavor info → 3 Login → 4 Permissions → 5 Ready.
    // The sync page is only shown on Play — fdroid doesn't have cloud sync.
    val showSyncPage = !isFdroidFlavor
    val pageCount = if (showSyncPage) 7 else 6
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
    var analyticsEnabled by rememberSaveable { mutableStateOf(viewModel.prefs.analyticsEnabled) }
    var syncEnabled by remember { mutableStateOf(viewModel.prefs.cloudSyncEnabled) }

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
    // Login page index shifts depending on whether the sync page is present.
    val loginPageIndex = if (showSyncPage) 4 else 3
    SecureScreen(secure = passwordVisible && pagerState.currentPage == loginPageIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Compute page indices that shift when the sync page is present (Play).
        val syncPageIndex = if (showSyncPage) 2 else -1
        val flavorPageIndex = if (showSyncPage) 3 else 2
        val permissionsPageIndex = if (showSyncPage) 5 else 4
        val readyPageIndex = if (showSyncPage) 6 else 5

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
                    // Analytics opt-in: hide on fdroid where the logger is a no-op stub.
                    if (!isFdroidFlavor) {
                        Spacer(Modifier.height(12.dp))
                        AnalyticsOptInCard(
                            checked = analyticsEnabled,
                            onCheckedChange = {
                                analyticsEnabled = it
                                viewModel.setAnalyticsEnabled(it)
                            },
                        )
                    }
                }

                syncPageIndex -> {
                    // Cross-device sync opt-in — Play flavor only.
                    OnboardingPageScaffold(
                        iconContent = {
                            PulsingIcon(
                                icon = Icons.Filled.Cloud,
                                tint = onboardingBlue(),
                            )
                        },
                        title = stringResource(R.string.onboarding_sync_title),
                        subtitle = stringResource(R.string.onboarding_sync_description),
                        actions = {
                            Button(
                                onClick = { goToPage(flavorPageIndex) },
                                modifier = Modifier.fillMaxWidth(0.6f)
                            ) { Text(stringResource(R.string.action_next)) }
                        },
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(0.9f),
                        ) {
                            SyncDataInfoRows()
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.onboarding_sync_toggle_label),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Switch(
                                        checked = syncEnabled,
                                        onCheckedChange = {
                                            syncEnabled = it
                                            viewModel.setSyncEnabled(it)
                                        },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.onboarding_sync_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY),
                            )
                            LinkRow(
                                icon = Icons.Filled.Info,
                                label = stringResource(R.string.settings_learn_more_backend),
                                url = URL_LEARN_MORE_BACKEND,
                            )
                            LinkRow(
                                icon = Icons.Filled.Shield,
                                label = stringResource(R.string.onboarding_privacy_policy_label),
                                url = URL_PRIVACY_POLICY,
                            )
                            LinkRow(
                                icon = Icons.Filled.AccountCircle,
                                label = stringResource(R.string.onboarding_privacy_delete_account_label),
                                url = URL_DELETE_ACCOUNT,
                            )
                        }
                    }
                }

                flavorPageIndex -> OnboardingPageScaffold(
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
                            onClick = { goToPage(loginPageIndex) },
                            modifier = Modifier.fillMaxWidth(0.6f)
                        ) { Text(stringResource(R.string.action_next)) }
                    },
                ) {}

                loginPageIndex -> {
                    val focusManager = LocalFocusManager.current
                    OnboardingPageScaffold(
                        iconContent = { PersonKeyBadgeIcon(tint = onboardingGreen()) },
                        title = stringResource(R.string.onboarding_sign_in_title),
                        subtitle = stringResource(R.string.onboarding_sign_in_subtitle),
                        actions = {
                            TextButton(onClick = { goToPage(permissionsPageIndex) }) {
                                Text(
                                    stringResource(R.string.onboarding_skip_for_now),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY)
                                )
                            }
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.login(studentId, password) {
                                        password = ""
                                        goToPage(permissionsPageIndex)
                                    }
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
                                            viewModel.login(studentId, password) {
                                                password = ""
                                                goToPage(permissionsPageIndex)
                                            }
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

                permissionsPageIndex -> PermissionsPage(
                    systemPermissions = viewModel.systemPermissions,
                    onContinue = { goToPage(readyPageIndex) },
                )

                readyPageIndex -> OnboardingPage(
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

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(96.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            MaterialTheme.colorScheme.background,
                        ),
                    )
                ),
        )

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
