package org.ntust.app.tigerduck.ui.screen.debug

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier
import org.ntust.app.tigerduck.liveactivity.LiveActivityScenario
import org.ntust.app.tigerduck.liveactivity.LiveActivitySnapshot
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.notification.AssignmentNotificationReceiver
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationReceiver
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.notification.NotificationGroup
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val deps = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, NotificationDebugEntryPoint::class.java)
    }
    // The real notifier, not a copy, so this previews exactly what a class
    // posts — including whether the system is willing to promote it to a
    // status-bar chip. Reaching a class in the normal way means being logged
    // in with a timetable and waiting for a period to start, which is a poor
    // way to check a rendering change.
    val liveNotifier = remember(deps) { deps.liveActivityNotifier() }

    // Class and homework go through the code their real triggers post with,
    // past the checks in front of it (term dates, the homework switch), so
    // what lands in each stack is what production puts there.
    val sendClass = {
        deps.notificationChannels().ensureRegistered()
        val now = System.currentTimeMillis()
        ClassPreparingNotificationReceiver.post(
            context,
            notificationId = DEBUG_CLASS_ID_BASE + sent.incrementAndGet(),
            courseName = "Preview Course",
            classroom = "TR-412",
            instructor = "Debug Menu",
            startMs = now + 10 * 60_000L,
            endMs = now + 60 * 60_000L,
            leadTimeMs = 10 * 60_000L,
        )
    }
    val sendHomework = {
        deps.notificationChannels().ensureRegistered()
        AssignmentNotificationReceiver.post(
            context,
            title = "Preview Assignment",
            courseName = "Preview Course",
            assignmentId = "debug-${sent.incrementAndGet()}",
            kind = AssignmentNotificationReceiver.KIND_REGULAR,
            offset = AssignmentReminderOffset.HR24,
        )
    }
    // Mirrors AndroidMailNotifier rather than going through it: every id it
    // posts a mail under is some real mail's, which the preview would replace.
    // A tap opens the inbox, as on iOS.
    val sendMail = {
        deps.notificationChannels().ensureRegistered()
        val id = DEBUG_MAIL_ID_BASE + sent.incrementAndGet()
        val inbox = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .putExtra("start_route", MailRoutes.LIST)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.SCHOOL_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Preview mail")
            .setContentText("Debug Menu")
            .setAutoCancel(true)
            .setGroup(NotificationGroup.MAIL.key)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(inbox)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        NotificationGroup.MAIL.postSummary(context, contentIntent = inbox)
    }
    // Bulletins arrive over FCM, whose builders are play-only, so this mirrors them.
    val sendOther = {
        val notification = NotificationCompat.Builder(context, NotificationChannels.BULLETINS)
            .setSmallIcon(R.drawable.ic_notification)
            // Mirrors the production builders so this preview stays honest.
            .setColor(ContextCompat.getColor(context, R.color.duck_yellow))
            .setContentTitle("Test notification")
            .setContentText("This is a test notification from the developer menu.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(NotificationGroup.OTHER.key)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(TEST_NOTIFICATION_ID + sent.incrementAndGet(), notification)
        NotificationGroup.OTHER.postSummary(context)
    }

    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pending?.invoke()
        pending = null
    }
    val send = { post: () -> Unit ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = post
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            post()
        }
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
            Button(onClick = { send(sendClass) }) {
                Text("Send class reminder")
            }

            Button(onClick = { send(sendHomework) }) {
                Text("Send homework reminder")
            }

            Button(onClick = { send(sendMail) }) {
                Text("Send new mail")
            }

            Button(onClick = { send(sendOther) }) {
                Text("Send bulletin (Other)")
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
    fun notificationChannels(): NotificationChannelRegistrar
}

// A new notification on every press rather than a replacement, so pressing
// one twice shows how its stack collapses. Held for the process rather than
// the screen, or reopening the menu would start over and replace the
// previews still in the shade.
private val sent = AtomicInteger()

private const val TEST_NOTIFICATION_ID = 0x7F00_0001
private const val DEBUG_CLASS_ID_BASE = 0x7F10_0000
private const val DEBUG_MAIL_ID_BASE = 0x7F20_0000
