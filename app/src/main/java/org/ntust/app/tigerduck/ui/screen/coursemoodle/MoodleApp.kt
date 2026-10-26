// Whether the Moodle app is on this phone, for the Open Moodle in setting
// (tigerduck-app-android#542): Settings shows the setting only while it is, and without it every
// "Open in Moodle" opens in the TigerDuck browser, whatever was stored; see openMoodlePage.

package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ntust.app.tigerduck.debug.OutsideApp
import org.ntust.app.tigerduck.debug.PretendMissingApps

/**
 * Whether an app on this phone takes the Moodle app's `moodlemobile://` links, as "Open in
 * Moodle" hands it one: only an activity that takes an implicit launch counts, as
 * `startActivity` needs. The manifest's `<queries>` entry for the scheme is what lets Android 11
 * and later answer this; a debug build pretending the Moodle app is missing says it is not.
 */
fun isMoodleAppInstalled(packageManager: PackageManager): Boolean {
    if (PretendMissingApps.isMissing(OutsideApp.MOODLE)) return false
    val probe = Intent(Intent.ACTION_VIEW, MoodlePage.deepLink("/").toUri())
    return packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
}

/**
 * [isMoodleAppInstalled], looked at again each time the screen comes back (`ON_RESUME`) and
 * whenever an app is installed or removed while it is up.
 */
@Composable
fun rememberMoodleAppInstalled(): Boolean {
    val context = LocalContext.current
    var installed by remember(context) { mutableStateOf(isMoodleAppInstalled(context.packageManager)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) installed = isMoodleAppInstalled(context.packageManager)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                installed = isMoodleAppInstalled(context.packageManager)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // The system's broadcasts still reach a receiver no other app may send to.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return installed
}
