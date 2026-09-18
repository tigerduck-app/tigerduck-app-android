// 校園信箱通知 — School Mail's notification settings, reached from Settings →
// 通知 rather than from the School Mail settings page. The new-mail toggle,
// its hint, the exact-alarm gap warning and the 通知診斷 log were MOVED here
// out of SchoolMailSettingsScreen, not copied: this is the one place in the
// app the toggle lives, so there is no second copy to disagree with it.
//
// It shares SchoolMailSettingsViewModel with SchoolMailSettingsScreen, which
// keeps 寄件者顯示名稱. Both are thin readers/writers of the same
// MailStateStore, so the toggle keeps exactly the scheduling side effects it
// had before the move (MailBackgroundScheduler schedule/cancel).
//
// iOS carries the same page with the same contents in the same order.

package org.ntust.app.tigerduck.ui.screen.mail

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.screen.settings.SettingsToggleRow
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolMailNotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SchoolMailSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The exact-alarm grant and the diagnostics log both change behind this
    // screen — the user leaves to grant the permission, or a background check
    // runs — so re-read them on the way back in.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.school_mail_notification_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard {
                    SettingsToggleRow(
                        label = stringResource(R.string.school_mail_settings_notifications),
                        checked = ui.notificationsEnabled,
                        subtitle = stringResource(R.string.school_mail_settings_notifications_hint),
                        onCheckedChange = viewModel::setNotifications,
                    )
                }
            }
            if (ui.notificationsEnabled && !ui.canExactAlarm && Build.VERSION.SDK_INT >= 31) {
                item {
                    ContentCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Color(0xFFFF9500))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.school_mail_exact_alarm_hint),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                                    )
                                }
                            }) { Text(stringResource(R.string.action_allow)) }
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.school_mail_settings_diagnostics), Modifier.padding(top = 12.dp)) }
            item {
                ContentCard {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        if (ui.diagnostics.isEmpty()) {
                            Text(
                                stringResource(R.string.school_mail_settings_no_checks),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        ui.diagnostics.forEachIndexed { i, line ->
                            if (i > 0) HorizontalDivider()
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
