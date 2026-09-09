package org.ntust.app.tigerduck.wear.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Loads the library-pass half of a screenshot fixture onto the watch.
 *
 * It reads the same JSON file the phone's `DebugFixtureReceiver` does, and
 * deliberately ignores everything except `libraryQr` and `studentId`. The
 * timetable does not belong here: the watch's schedule is mirrored from the
 * phone over the Data Layer, so a fixture loaded on the phone has already put
 * the fake courses on the watch by the time this runs. Writing them a second
 * time would give the two copies a way to disagree.
 *
 * Same action names as the phone's receiver. The watch APK carries the same
 * applicationId, but the two live on different devices, so `am broadcast -p`
 * reaches exactly one of them and the script can use one pair of constants.
 *
 * **Debug builds only** -- declared in `wear/src/debug/AndroidManifest.xml`.
 */
class WearFixtureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = WearFixtureStore.get(context)

        when (intent.action) {
            ACTION_CLEAR -> {
                store.clear()
                Log.i(TAG, "cleared library pass override")
            }

            ACTION_LOAD -> {
                val path = intent.getStringExtra(EXTRA_FILE)
                if (path == null) {
                    Log.e(TAG, "ignoring LOAD: pass --es $EXTRA_FILE <path to fixture json>")
                    return
                }
                val file = File(path)
                if (!file.isFile) {
                    Log.e(TAG, "ignoring LOAD: no such file $path")
                    return
                }
                val root = try {
                    JSONObject(file.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "ignoring LOAD: $path is not valid JSON", e)
                    return
                }

                val qr = root.optJSONObject(KEY_LIBRARY_QR)
                if (qr == null) {
                    // Not an error: a fixture may legitimately carry only a
                    // timetable, and that half reaches the watch from the phone.
                    Log.i(TAG, "no '$KEY_LIBRARY_QR' section — leaving the pass alone")
                    return
                }
                store.libraryQrContent = qr.optString("content").takeIf { it.isNotBlank() }
                store.fakeSignedIn = qr.optBoolean("fakeLoggedIn", true)
                store.username = root.optString(KEY_STUDENT_ID).takeIf { it.isNotBlank() }
                Log.i(
                    TAG,
                    "library pass override ${if (store.isActive) "ON" else "off"} " +
                        "(user=${store.username ?: "—"})",
                )
            }

            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    private companion object {
        const val TAG = "WearFixture"
        const val ACTION_LOAD = "org.ntust.app.tigerduck.debug.LOAD_FIXTURE"
        const val ACTION_CLEAR = "org.ntust.app.tigerduck.debug.CLEAR_FIXTURE"
        const val EXTRA_FILE = "file"
        const val KEY_LIBRARY_QR = "libraryQr"
        const val KEY_STUDENT_ID = "studentId"
    }
}
