package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.AppUrls
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.openLink

/**
 * 其他, behind the last row of Settings' 關於 section: the services' status, then the links about
 * the project rather than the app's own settings — 回饋/問題回報, 隱私權政策, 刪除帳號, 開源授權
 * and 查看原始碼. iOS has the same page.
 */
@Composable
internal fun AboutOthersRows(browserPreference: String, open: (SettingsLink) -> Unit) {
    val context = LocalContext.current
    ContentCard {
        Column {
            // Whether the services behind the app are up, where Check for updates asks whether the
            // app is current. Shown on every flavor — an F-Droid build talks to the same backend.
            //
            // The URL currently 302s to another origin. Custom Tabs follows that in place, so the
            // in-app choice stays in-app; handing the redirect to an ACTION_VIEW intent instead
            // would eject the user into their browser mid-hop.
            SettingsLinkRow(SettingsRow.SERVER_STATUS.label()) { openLink(context, AppUrls.SERVER_STATUS, browserPreference) }
            HorizontalDivider()
            SettingsLinkRow(stringResource(R.string.settings_feedback_bug_report)) {
                openLink(context, AppUrls.ISSUES, browserPreference)
            }
            HorizontalDivider()
            SettingsLinkRow(stringResource(R.string.settings_privacy_policy)) {
                openLink(context, AppUrls.PRIVACY_POLICY, browserPreference)
            }
            HorizontalDivider()
            SettingsLinkRow(stringResource(R.string.settings_delete_account)) {
                openLink(context, AppUrls.DELETE_ACCOUNT, browserPreference)
            }
            HorizontalDivider()
            SettingsLinkRow(stringResource(R.string.settings_open_source_licenses)) { open(SettingsLink.LICENSES) }
            HorizontalDivider()
            SettingsLinkRow(stringResource(R.string.settings_view_source_code)) { open(SettingsLink.SOURCE_CODE) }
        }
    }
}
