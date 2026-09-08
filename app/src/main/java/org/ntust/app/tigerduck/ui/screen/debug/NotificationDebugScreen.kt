package org.ntust.app.tigerduck.ui.screen.debug

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier
import org.ntust.app.tigerduck.liveactivity.LiveActivityScenario
import org.ntust.app.tigerduck.liveactivity.LiveActivitySnapshot
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // The real notifier, not a copy, so this previews exactly what a class
    // posts — including whether the system is willing to promote it to a
    // status-bar chip. Reaching a class in the normal way means being logged
    // in with a timetable and waiting for a period to start, which is a poor
    // way to check a rendering change.
    val liveNotifier = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, NotificationDebugEntryPoint::class.java)
            .liveActivityNotifier()
    }

    val sendTest = sendTest@{
        val notification = NotificationCompat.Builder(context, NotificationChannels.BULLETINS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test notification")
            .setContentText("This is a test notification from the developer menu.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(TEST_NOTIFICATION_ID, notification)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) sendTest()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text("Notification") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        sendTest()
                    }
                },
            ) {
                Text("Send test notification")
            }

            Button(onClick = { liveNotifier.apply(previewInClassSnapshot()) }) {
                Text("Preview Live Update (in class)")
            }

            Button(onClick = { liveNotifier.cancel() }) {
                Text("Clear Live Update")
            }
        }
    }
}

/**
 * A synthetic mid-class snapshot: a third of the way through a 50-minute
 * period, so both the countdown and a partly filled progress bar have
 * something to show.
 */
private fun previewInClassSnapshot(): LiveActivitySnapshot {
    val now = AppClock.nowMillis()
    return LiveActivitySnapshot(
        scenario = LiveActivityScenario.IN_CLASS,
        title = "Preview Course",
        subtitle = "09:10–10:00",
        locationText = "TR-412",
        instructor = "Debug Menu",
        countdownTarget = Date(now + 34 * 60_000L),
        progress = 0.32,
        accentHex = 0xF5A623,
        sourceId = "debug-preview",
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NotificationDebugEntryPoint {
    fun liveActivityNotifier(): LiveActivityNotifier
}

private const val TEST_NOTIFICATION_ID = 0x7F00_0001
