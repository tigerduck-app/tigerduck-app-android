package org.ntust.app.tigerduck.ui.component

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun ComingSoonDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快了快了") },
        text = { Text("此功能尚未實現，敬請期待～") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("收到！") }
        }
    )
}
