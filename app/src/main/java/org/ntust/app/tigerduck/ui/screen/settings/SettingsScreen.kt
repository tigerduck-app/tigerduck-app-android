package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Activity
import android.content.Intent
import org.ntust.app.tigerduck.BuildConfig
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToTabEditor: () -> Unit = {},
    onNavigateToLiveActivity: () -> Unit = {},
    onNavigateToNotificationSetup: () -> Unit = {},
) {
    val context = LocalContext.current
    val isNtustLoggingIn by viewModel.isNtustLoggingIn.collectAsState()
    val ntustLoginError by viewModel.ntustLoginError.collectAsState()
    val libIsLoggingIn by viewModel.libIsLoggingIn.collectAsState()
    val libLoginError by viewModel.libLoginError.collectAsState()
    val isNtustLoggedIn by viewModel.isNtustLoggedIn.collectAsState()
    val isLibraryLoggedIn by viewModel.isLibraryLoggedIn.collectAsState()

    var showNtustLoginSheet by remember { mutableStateOf(false) }
    var showLibraryLoginSheet by remember { mutableStateOf(false) }

    // Auto-dismiss dialogs when login succeeds
    LaunchedEffect(isNtustLoggedIn) {
        if (isNtustLoggedIn) showNtustLoginSheet = false
    }
    LaunchedEffect(isLibraryLoggedIn) {
        if (isLibraryLoggedIn) showLibraryLoginSheet = false
    }

    val accentColorHex = viewModel.appState.accentColorHex
    val showAbsoluteTime = viewModel.appState.showAbsoluteAssignmentTime
    val browserPreference = viewModel.appState.browserPreference
    val invertSlider = viewModel.appState.invertSliderDirection
    val notifyAssignments = viewModel.appState.notifyAssignments
    val libraryEnabled = viewModel.appState.libraryFeatureEnabled
    val themeMode = viewModel.appState.themeMode
    val appLanguage = viewModel.appState.appLanguage

    var showLibraryWarning by remember { mutableStateOf(false) }
    var showResetColorsConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val appVersion = remember { BuildConfig.VERSION_NAME }

    // Show network error as snackbar
    LaunchedEffect(ntustLoginError) {
        val error = ntustLoginError ?: return@LaunchedEffect
        if (error.contains("連線") || error.contains("網路")) {
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { scaffoldPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            PageHeader(title = "設定")
        }

        // MARK: Account section
        item { SectionHeader("帳號") }
        item {
            ContentCard {
                Column {
                    AccountRow(
                        title = "NTUST 校務系統",
                        isLoggedIn = isNtustLoggedIn,
                        subtitle = if (isNtustLoggedIn) viewModel.ntustStudentId else null,
                        isLoggingIn = isNtustLoggingIn,
                        onLogin = { showNtustLoginSheet = true },
                        onLogout = { viewModel.logoutNtust() },
                    )

                    if (libraryEnabled) {
                        HorizontalDivider()
                        val expiryMs = viewModel.libraryTokenExpiry
                        val expirySubtitle = if (isLibraryLoggedIn && expiryMs > 0) {
                            val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN).apply {
                                timeZone = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
                            }
                            "Token 有效至 " + fmt.format(Date(expiryMs))
                        } else null
                        AccountRow(
                            title = "圖書館系統",
                            isLoggedIn = isLibraryLoggedIn,
                            subtitle = if (isLibraryLoggedIn) viewModel.libraryUsername else null,
                            extraSubtitle = expirySubtitle,
                            isLoggingIn = libIsLoggingIn,
                            onLogin = { showLibraryLoginSheet = true },
                            onLogout = { viewModel.logoutLibrary() },
                        )
                    }
                }
            }
        }

        // MARK: Custom
        item { SectionHeader("自訂") }
        item {
            ContentCard {
                Column {
                    SettingsLinkRow("Tab 編輯器") { onNavigateToTabEditor() }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("主題色", style = MaterialTheme.typography.bodyMedium)
                        // Show the mode-appropriate display color but always
                        // persist the canonical (light) hex so the pair swaps
                        // when the user toggles 顏色主題.
                        val accentPaletteDisplay = if (TigerDuckTheme.isDarkMode) {
                            AppPreferences.themeColorsDark
                        } else {
                            AppPreferences.themeColors
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            accentPaletteDisplay.forEachIndexed { idx, (_, displayHex) ->
                                val canonicalHex = AppPreferences.themeColors[idx].second
                                val color = Color(0xFF000000 or displayHex.toLong())
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable { viewModel.appState.accentColorHex = canonicalHex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (accentColorHex == canonicalHex) {
                                        Text("\u2713", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // MARK: Display
        item { SectionHeader("顯示") }
        item {
            ContentCard {
                Column {
                    SettingsToggleRow("作業截止時間顯示完整日期", showAbsoluteTime) {
                        viewModel.appState.showAbsoluteAssignmentTime = it
                    }
                    HorizontalDivider()
                    // Link opening method
                    SettingsPickerRow(
                        label = "開啟連結方式",
                        value = if (browserPreference == "inApp") "App 內瀏覽器" else "系統預設瀏覽器",
                        options = listOf("system" to "系統預設瀏覽器", "inApp" to "App 內瀏覽器"),
                        selectedKey = browserPreference,
                        onSelect = { viewModel.appState.browserPreference = it }
                    )
                    HorizontalDivider()
                    SettingsToggleRow("反轉滑條方向", invertSlider) {
                        viewModel.appState.invertSliderDirection = it
                    }
                    HorizontalDivider()
                    SettingsPickerRow(
                        label = "語言",
                        value = when (appLanguage) {
                            AppLanguageManager.TRADITIONAL_CHINESE -> "繁體中文"
                            AppLanguageManager.ENGLISH -> "English"
                            else -> "跟隨系統"
                        },
                        options = listOf(
                            AppLanguageManager.SYSTEM to "跟隨系統",
                            AppLanguageManager.TRADITIONAL_CHINESE to "繁體中文",
                            AppLanguageManager.ENGLISH to "English",
                        ),
                        selectedKey = appLanguage,
                        onSelect = { selectedLanguage ->
                            viewModel.setAppLanguage(selectedLanguage)
                            (context as? Activity)?.recreate()
                        },
                    )
                }
            }
        }

        // MARK: Notifications
        item { SectionHeader("通知") }
        item {
            ContentCard {
                Column {
                    SettingsToggleRow("作業到期提醒", notifyAssignments) {
                        viewModel.appState.notifyAssignments = it
                        if (!it) viewModel.cancelAllAssignmentNotifications()
                    }
                    HorizontalDivider()
                    SettingsLinkRow("即時動態") { onNavigateToLiveActivity() }
                    HorizontalDivider()
                    SettingsLinkRow("重新設定通知") { onNavigateToNotificationSetup() }
                }
            }
        }

        // MARK: Other features
        item { SectionHeader("其他功能") }
        item {
            ContentCard {
                Column {
                SettingsToggleRow("圖書館及相關功能", libraryEnabled) { enabled ->
                    if (enabled) {
                        showLibraryWarning = true
                    } else {
                        viewModel.appState.libraryFeatureEnabled = false
                        viewModel.appState.configuredTabs =
                            viewModel.appState.configuredTabs.filter { !it.isLibraryRelated }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SettingRowHeight)
                        .clickable { showResetColorsConfirm = true }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("重設課表顏色", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                SettingsPickerRow(
                    label = "顏色主題",
                    value = when (themeMode) {
                        "dark" -> "暗色模式"
                        "light" -> "亮色模式"
                        else -> "跟隨系統"
                    },
                    options = listOf(
                        "system" to "跟隨系統",
                        "dark" to "暗色模式",
                        "light" to "亮色模式"
                    ),
                    selectedKey = themeMode,
                    onSelect = { viewModel.appState.themeMode = it }
                )
                } // Column
            }
        }

        // MARK: About
        item { SectionHeader("關於") }
        item {
            ContentCard {
                Column {
                    SettingsRow("版本", appVersion)
                    HorizontalDivider()
                    SettingsLinkRow("回饋/問題回報") {
                        openUrl(context, "https://github.com/tigerduck-app/tigerduck-app-android/issues", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow("隱私權政策") {
                        openUrl(context, "https://app.ntust.org/tigerduck/privacy", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow("開源授權") {
                        openUrl(context, "https://github.com/tigerduck-app/tigerduck-app-android/blob/main/LICENSE", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow("查看原始碼") {
                        openUrl(context, "https://github.com/tigerduck-app/tigerduck-app-android", browserPreference)
                    }
                }
            }
        }
    }
    } // Scaffold

    if (showNtustLoginSheet) {
        LoginSheet(
            title = "NTUST 校務系統",
            usernamePlaceholder = "學號",
            passwordPlaceholder = "密碼",
            uppercaseInput = true,
            isLoggingIn = isNtustLoggingIn,
            loginError = ntustLoginError,
            onLogin = { u, p -> viewModel.loginNtust(u, p) },
            onDismiss = { showNtustLoginSheet = false },
        )
    }

    if (showLibraryLoginSheet) {
        LoginSheet(
            title = "圖書館系統",
            subtitle = "帳號密碼可能與校務系統不同",
            usernamePlaceholder = "圖書館帳號",
            passwordPlaceholder = "圖書館密碼",
            initialUsername = viewModel.ntustStudentId.orEmpty(),
            isLoggingIn = libIsLoggingIn,
            loginError = libLoginError,
            onLogin = { u, p -> viewModel.loginLibrary(u, p) },
            onDismiss = { showLibraryLoginSheet = false },
        )
    }

    if (showLibraryWarning) {
        LibraryWarningDialog(
            onConfirm = {
                viewModel.appState.libraryFeatureEnabled = true
                if (!viewModel.appState.configuredTabs.contains(AppFeature.LIBRARY) &&
                    viewModel.appState.configuredTabs.size < 4
                ) {
                    viewModel.appState.configuredTabs =
                        viewModel.appState.configuredTabs + AppFeature.LIBRARY
                }
                showLibraryWarning = false
            },
            onDismiss = { showLibraryWarning = false }
        )
    }

    if (viewModel.appState.pendingLibraryEnablePrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.appState.pendingLibraryEnablePrompt = false },
            title = { Text("圖書館功能未啟用") },
            text = { Text("請先在下方「其他功能」中開啟「圖書館及相關功能」後，再使用圖書館 QR 捷徑。") },
            confirmButton = {
                TextButton(onClick = { viewModel.appState.pendingLibraryEnablePrompt = false }) {
                    Text("知道了")
                }
            },
        )
    }

    if (showResetColorsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetColorsConfirm = false },
            title = { Text("確定要重設課表顏色？") },
            text = { Text("這將會把課表的顏色依照預設顏色隨機重新分配") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetCourseColors()
                    showResetColorsConfirm = false
                }) { Text("確定") }
            },
            dismissButton = {
                TextButton(onClick = { showResetColorsConfirm = false }) { Text("取消") }
            }
        )
    }
}

private val SettingRowHeight = 56.dp

@Composable
private fun AccountRow(
    title: String,
    isLoggedIn: Boolean,
    subtitle: String?,
    extraSubtitle: String? = null,
    isLoggingIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isLoggedIn) Color(0xFF34C759) else Color(0xFFFF3B30))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
            if (!extraSubtitle.isNullOrBlank()) {
                Text(
                    extraSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        }
        if (isLoggingIn) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else if (isLoggedIn) {
            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("登出") }
        } else {
            Button(onClick = onLogin) { Text("登入") }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY))
    }
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // iOS-style switch colors: the default M3 unchecked thumb is `outline`
        // which collapses to near-invisible dark gray on the dark track in
        // dark mode. Force a white thumb and a neutral track instead.
        val isDark = TigerDuckTheme.isDarkMode
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = if (isDark) Color(0xFF39393D) else Color(0xFFE9E9EB),
                uncheckedBorderColor = Color.Transparent,
            )
        )
    }
}

@Composable
private fun SettingsPickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .clickable { expanded = true }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY))

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(12.dp)) {
                options.forEach { (key, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        },
                        leadingIcon = {
                            RadioButton(
                                selected = selectedKey == key,
                                onClick = null
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun LibraryWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(5) }
    var confirmEnabled by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(Unit) {
        // 1-second max vibration on dialog open
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = view.context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            view.context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        vibrator?.vibrate(
            android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        )

        for (i in 4 downTo 0) {
            delay(1000)
            countdown = i
        }
        confirmEnabled = true
    }

    val infiniteTransition = rememberInfiniteTransition(label = "flash")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flash_alpha"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.Red.copy(alpha = flashAlpha),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "請注意！",
                    color = Color.Red.copy(alpha = flashAlpha),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Text("本應用程式非臺科大官方圖書館應用程式，且尚未得到學校圖書館認可，無法保證各項功能的正常使用及其他相關使用後果。\n\n如需使用請謹慎。若使後產生任何負面結果，需自負責任，且與 tigerduck-app 一律無關！")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red,
                    disabledContainerColor = Color.Red.copy(alpha = 0.35f)
                )
            ) {
                Text(
                    if (confirmEnabled) "我願意自負後果"
                    else "我願意自負後果（$countdown）"
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("退回")
            }
        }
    )
}

private fun openUrl(context: android.content.Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    if (browserPreference == "inApp") {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
