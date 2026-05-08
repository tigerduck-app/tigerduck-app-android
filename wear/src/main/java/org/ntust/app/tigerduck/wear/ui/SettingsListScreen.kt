package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding

@Composable
fun SettingsListScreen(onPaddingClick: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val pad = LocalScreenPadding.current
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pad),
            state = listState,
        ) {
            item {
                ListHeader { Text(stringResource(R.string.settings_title)) }
            }
            item {
                FilledTonalButton(
                    onClick = onPaddingClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.settings_padding_label),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
