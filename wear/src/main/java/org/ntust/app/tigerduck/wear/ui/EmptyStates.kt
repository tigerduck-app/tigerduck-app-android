package org.ntust.app.tigerduck.wear.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.launch

@Composable
fun EmptyStateMessage(text: String, openPhoneOnTap: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val helper = RemoteActivityHelper(context)
    val baseModifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)
    val modifier = if (openPhoneOnTap) {
        baseModifier.clickable {
            scope.launch {
                val intent = Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse("market://details?id=org.ntust.app.tigerduck"))
                helper.startRemoteActivity(intent, null)
            }
        }
    } else baseModifier
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}
