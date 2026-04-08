package org.ntust.app.tigerduck.ui.screen.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
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
                    icon = "🎓",
                    title = "歡迎使用 TigerDuck",
                    subtitle = "你的臺科大校園助手"
                ) {
                    Button(
                        onClick = { goToPage(1) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text("下一步") }
                }

                1 -> OnboardingPage(
                    icon = "🔑",
                    title = "登入帳號",
                    subtitle = "使用 NTUST SSO 登入以存取課表、Moodle 等功能"
                ) {
                    val focusManager = LocalFocusManager.current
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        OutlinedTextField(
                            value = studentId,
                            onValueChange = { studentId = it.uppercase() },
                            label = { Text("學號") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密碼") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (studentId.isNotBlank() && password.isNotBlank() && !isLoggingIn) {
                                        viewModel.login(studentId, password) { goToPage(2) }
                                    }
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
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
                                Text("登入 NTUST SSO")
                            }
                        }
                        TextButton(onClick = { goToPage(2) }) {
                            Text(
                                "暫時跳過",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY)
                            )
                        }
                    }
                }

                2 -> OnboardingPage(
                    icon = "⚙️",
                    title = "選擇功能",
                    subtitle = "你可以之後在設定中隨時調整"
                ) {
                    Button(
                        onClick = { goToPage(3) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text("下一步") }
                }

                3 -> OnboardingPage(
                    icon = "✅",
                    title = "準備就緒！",
                    subtitle = "開始探索你的校園生活"
                ) {
                    Button(
                        onClick = { viewModel.completeOnboarding() },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) { Text("開始使用 TigerDuck") }
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
            repeat(4) { i ->
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
private fun OnboardingPage(
    icon: String,
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
        Text(text = icon, style = MaterialTheme.typography.displayMedium)
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
