package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isNtustLoggingIn by viewModel.isNtustLoggingIn.collectAsState()
    val ntustLoginError by viewModel.ntustLoginError.collectAsState()
    val libIsLoggingIn by viewModel.libIsLoggingIn.collectAsState()
    val libLoginError by viewModel.libLoginError.collectAsState()
    val isNtustLoggedIn by viewModel.isNtustLoggedIn.collectAsState()
    val isLibraryLoggedIn by viewModel.isLibraryLoggedIn.collectAsState()

    var ntustStudentIdInput by remember { mutableStateOf("") }
    var ntustPasswordInput by remember { mutableStateOf("") }
    var libUsernameInput by remember { mutableStateOf("") }
    var libPasswordInput by remember { mutableStateOf("") }

    val accentColorHex by remember { derivedStateOf { viewModel.appState.accentColorHex } }
    val showAbsoluteTime by remember { derivedStateOf { viewModel.appState.showAbsoluteAssignmentTime } }
    val browserPreference by remember { derivedStateOf { viewModel.appState.browserPreference } }
    val timeSliderStyle by remember { derivedStateOf { viewModel.appState.timeSliderStyle } }
    val invertSlider by remember { derivedStateOf { viewModel.appState.invertSliderDirection } }
    val notifyAssignments by remember { derivedStateOf { viewModel.appState.notifyAssignments } }

    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text(
                "設定",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
        }

        // MARK: Account section
        item { SettingsSectionTitle("帳號") }
        item {
            SettingsCard {
                // NTUST Account
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isNtustLoggedIn) Color(0xFF34C759) else Color(0xFFFF3B30))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "NTUST 校務系統",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f)
                        )
                        if (isNtustLoggingIn) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                if (isNtustLoggedIn) "已登入" else "未登入",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    if (isNtustLoggedIn) {
                        viewModel.ntustStudentId?.let {
                            Text("學號：$it", style = MaterialTheme.typography.bodySmall)
                        }
                        val expiryMs = viewModel.cookieExpiryMs
                        if (expiryMs > 0) {
                            val expiry = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(expiryMs))
                            Text("Cookie 有效至 $expiry", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        OutlinedButton(
                            onClick = { viewModel.logoutNtust() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("登出") }
                    } else {
                        OutlinedTextField(
                            value = ntustStudentIdInput,
                            onValueChange = { ntustStudentIdInput = it.uppercase() },
                            label = { Text("學號") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                        )
                        OutlinedTextField(
                            value = ntustPasswordInput,
                            onValueChange = { ntustPasswordInput = it },
                            label = { Text("密碼") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        ntustLoginError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = {
                                viewModel.loginNtust(ntustStudentIdInput, ntustPasswordInput)
                            },
                            enabled = ntustStudentIdInput.isNotBlank() && ntustPasswordInput.isNotBlank() && !isNtustLoggingIn
                        ) { Text("登入 NTUST") }
                    }
                }

                HorizontalDivider()

                // Library Account
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isLibraryLoggedIn) Color(0xFF34C759) else Color(0xFFFF3B30))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "圖書館系統",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f)
                        )
                        if (libIsLoggingIn) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                if (isLibraryLoggedIn) "已登入" else "未登入",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    if (isLibraryLoggedIn) {
                        viewModel.libraryUsername?.let {
                            Text("帳號：$it", style = MaterialTheme.typography.bodySmall)
                        }
                        val expiryMs = viewModel.libraryTokenExpiry
                        if (expiryMs > 0) {
                            val expiry = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(expiryMs))
                            Text("Token 有效至 $expiry", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        OutlinedButton(
                            onClick = { viewModel.logoutLibrary() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("登出") }
                    } else {
                        Text("帳號密碼可能與校務系統不同", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        OutlinedTextField(
                            value = libUsernameInput,
                            onValueChange = { libUsernameInput = it.uppercase() },
                            label = { Text("圖書館帳號") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                        )
                        OutlinedTextField(
                            value = libPasswordInput,
                            onValueChange = { libPasswordInput = it },
                            label = { Text("圖書館密碼") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        libLoginError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { viewModel.loginLibrary(libUsernameInput, libPasswordInput) },
                            enabled = libUsernameInput.isNotBlank() && libPasswordInput.isNotBlank() && !libIsLoggingIn
                        ) { Text("登入圖書館") }
                    }
                }
            }
        }

        // MARK: Theme
        item { SettingsSectionTitle("自訂") }
        item {
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("主題色", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppPreferences.themeColors.forEach { (_, hex) ->
                            val color = Color(0xFF000000 or hex.toLong())
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable { viewModel.appState.accentColorHex = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (accentColorHex == hex) {
                                    Text("\u2713", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // MARK: Display
        item { SettingsSectionTitle("顯示") }
        item {
            SettingsCard {
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
                    // Time slider style
                    SettingsPickerRow(
                        label = "時間滑條樣式",
                        value = if (timeSliderStyle == "segmentedBar") "課程區塊" else "時間軸",
                        options = listOf("fluidTrack" to "時間軸", "segmentedBar" to "課程區塊"),
                        selectedKey = timeSliderStyle,
                        onSelect = { viewModel.appState.timeSliderStyle = it }
                    )
                    HorizontalDivider()
                    SettingsToggleRow("反轉滑條方向", invertSlider) {
                        viewModel.appState.invertSliderDirection = it
                    }
                }
            }
        }

        // MARK: Notifications
        item { SettingsSectionTitle("通知") }
        item {
            SettingsCard {
                SettingsToggleRow("作業到期提醒", notifyAssignments) {
                    viewModel.appState.notifyAssignments = it
                }
            }
        }

        // MARK: About
        item { SettingsSectionTitle("關於") }
        item {
            SettingsCard {
                Column {
                    SettingsRow("版本", appVersion)
                    HorizontalDivider()
                    SettingsLinkRow("回饋/問題回報") {
                        openUrl(context, "https://github.com/tigerduck-app/tigerduck-app/issues", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow("隱私權政策") {
                        openUrl(context, "https://app.ntust.org/tigerduck/privacy", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow("開源授權") {
                        openUrl(context, "https://github.com/tigerduck-app/tigerduck-app/blob/main/LICENSE", browserPreference)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) { content() }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                    trailingIcon = {
                        if (selectedKey == key) {
                            Text("\u2713", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun openUrl(context: android.content.Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    if (browserPreference == "inApp") {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
