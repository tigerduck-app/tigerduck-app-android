package org.ntust.app.tigerduck.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Display-only overrides used when capturing store screenshots.
 *
 * These exist so a screenshot can show a made-up student ID and a chosen
 * library QR payload without touching the real credentials behind them.
 * Writing the actual [org.ntust.app.tigerduck.data.preferences.CredentialManager]
 * fields would have been fewer moving parts, but it would also break the
 * signed-in session the screenshots are being taken from — every network call
 * keys off the stored student ID — and leave the install in a state that only
 * a re-login fixes. An override that only the display sites consult is
 * reversible by clearing one preferences file.
 *
 * Written by `DebugFixtureReceiver` (debug source set only). Read from a
 * handful of `BuildConfig.DEBUG` branches in the ViewModels; in a release
 * build those branches are constant-false and R8 removes them, so this class
 * is unreachable there.
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
     * a real one. Any string; a URL is the point — it makes the QR in a store
     * screenshot scannable to somewhere deliberate.
     */
    var libraryQrContent: String?
        get() = prefs.getString(KEY_QR_CONTENT, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_QR_CONTENT) else putString(KEY_QR_CONTENT, value)
        }.apply()

    /**
     * Whether the library screen should render as signed in while
     * [libraryQrContent] is set. Without this the screen shows its sign-in
     * form, and the QR — the thing being screenshotted — never appears unless
     * a real library account is signed in on the device.
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
     * Read once per process, at [org.ntust.app.tigerduck.ui.AppState] init and
     * when each OkHttp client makes a call. Flipping it on a running app would
     * leave an in-flight sync still writing, so the screenshot script
     * force-stops the app after loading a demo fixture.
     */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEMO_MODE, value).apply()

    /** True when anything is overridden — used to log a warning banner in the app. */
    val hasAnyOverride: Boolean
        get() = studentIdOverride != null || libraryQrContent != null || demoMode

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE = "debug_fixture"
        const val KEY_STUDENT_ID = "student_id"
        const val KEY_QR_CONTENT = "library_qr_content"
        const val KEY_QR_FAKE_SIGNED_IN = "library_qr_fake_signed_in"
        const val KEY_DEMO_MODE = "demo_mode"
    }
}
