package org.ntust.app.tigerduck.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The demo account's state: whether it is signed in, and the display
 * overrides its bundled file carries — the student ID to show, the library
 * QR payload and user name, and whether the library screen renders as
 * signed in. The library ones can also be set on their own, by the demo
 * library sign-in with no demo account.
 *
 * Written by [org.ntust.app.tigerduck.demo.DemoAccount] at the demo sign-in
 * and cleared at sign-out, all but a demo library signed in ([clearSchool]). Only the display sites consult the overrides, so
 * clearing this one preferences file is all it takes to leave.
 *
 * Backed by its own SharedPreferences file, for the same reason
 * [DebugClockPrefsStore] is: it must never end up inside an AppPreferences
 * migration.
 */
@Singleton
class DebugFixtureStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    /** Student ID to render in place of the real one, or null to show the real one. */
    var studentIdOverride: String?
        get() = prefs.getString(KEY_STUDENT_ID, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_STUDENT_ID) else putString(KEY_STUDENT_ID, value)
        }.apply()

    /**
     * Payload to encode into the library QR instead of asking the backend for
     * a real one. Any string; a URL makes the demo QR scannable to somewhere
     * deliberate.
     */
    var libraryQrContent: String?
        get() = prefs.getString(KEY_QR_CONTENT, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_QR_CONTENT) else putString(KEY_QR_CONTENT, value)
        }.apply()

    /** Library user name to show while [libraryFakeSignedIn], in place of a real account's. */
    var libraryUsername: String?
        get() = prefs.getString(KEY_LIBRARY_USERNAME, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_LIBRARY_USERNAME) else putString(KEY_LIBRARY_USERNAME, value)
        }.apply()

    /**
     * Whether the library screen should render as signed in while
     * [libraryQrContent] is set. Without this the screen shows its sign-in
     * form, and the demo QR never appears unless a real library account is
     * signed in on the device.
     */
    var libraryFakeSignedIn: Boolean
        get() = prefs.getBoolean(KEY_QR_FAKE_SIGNED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_QR_FAKE_SIGNED_IN, value).apply()

    /**
     * Whether the app is running on fixture data alone, with every server
     * unreachable.
     *
     * A fixture on its own is only a cache write, and the three refresh paths
     * that rebuild that cache — the class table's portal fetch, the cloud
     * reconcile and the background worker — each overwrite it within seconds
     * of the network coming back. Demo mode is what makes the fake timetable
     * stay: [DemoModeInterceptor] fails every request before it leaves the
     * device, so there is nothing left to overwrite it with.
     *
     * Read live by [DemoModeInterceptor] on every call, so the demo sign-in
     * takes hold at once; [org.ntust.app.tigerduck.ui.AppState] and
     * `AuthService` sample it at process start for what they decide once.
     */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEMO_MODE, value).apply()

    /** The demo courses' announcements the demo account has opened, which stay read across restarts. */
    var demoMoodleReadIds: Set<Int>
        get() = prefs.getStringSet(KEY_DEMO_MOODLE_READ, null).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(KEY_DEMO_MOODLE_READ, value.map { it.toString() }.toSet()).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Leaving the demo account, a school sign-out: everything goes but a demo library signed in,
     * which stays until its own sign-out, as a real library account stays through a school
     * sign-out.
     */
    fun clearSchool() {
        if (!libraryFakeSignedIn) return clear()
        // The demo sign-in shows the library under the demo student ID, which goes below.
        if (libraryUsername == null) libraryUsername = studentIdOverride
        val library = listOf(KEY_QR_CONTENT, KEY_QR_FAKE_SIGNED_IN, KEY_LIBRARY_USERNAME)
        prefs.edit().apply { prefs.all.keys.filter { it !in library }.forEach(::remove) }.apply()
    }

    private companion object {
        const val PREFS_FILE = "debug_fixture"
        const val KEY_STUDENT_ID = "student_id"
        const val KEY_QR_CONTENT = "library_qr_content"
        const val KEY_QR_FAKE_SIGNED_IN = "library_qr_fake_signed_in"
        const val KEY_LIBRARY_USERNAME = "library_username"
        const val KEY_DEMO_MODE = "demo_mode"
        const val KEY_DEMO_MOODLE_READ = "demo_moodle_read"
    }
}
