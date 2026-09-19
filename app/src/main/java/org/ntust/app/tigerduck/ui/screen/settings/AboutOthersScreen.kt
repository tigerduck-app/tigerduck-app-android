package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets

/**
 * 其他, behind the row under 官網 in Settings' 關於 section: the links about
 * the project rather than the app's own settings — 回饋/問題回報, 隱私權政策,
 * 刪除帳號, 開源授權 and 查看原始碼. The settings themselves are on
 * [OtherSettingsScreen]. iOS has the same page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutOthersScreen(
    onBack: () -> Unit,
    onNavigateToSourceCode: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val browserPreference = viewModel.appState.browserPreference

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_about_others)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard {
                    Column {
                        SettingsLinkRow(stringResource(R.string.settings_feedback_bug_report)) {
                            openUrl(
                                context,
                                "https://github.com/tigerduck-app/tigerduck-app-android/issues",
                                browserPreference,
                            )
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_privacy_policy)) {
                            openUrl(
                                context,
                                "https://app.ntust.org/tigerduck/privacy",
                                browserPreference,
                            )
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_delete_account)) {
                            openUrl(
                                context,
                                "https://tigerduck.app/delete-account",
                                browserPreference,
                            )
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_open_source_licenses)) {
                            onNavigateToLicenses()
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_view_source_code)) {
                            onNavigateToSourceCode()
                        }
                    }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    if (browserPreference == "inApp") {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
