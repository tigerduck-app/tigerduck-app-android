package org.ntust.app.tigerduck.wear.debug

import android.content.Context

/**
 * Display-only overrides for the watch's library pass, used when capturing
 * store screenshots.
 *
 * The phone's [org.ntust.app.tigerduck.debug.DebugFixtureStore] cannot reach
 * here. Everything else the watch shows arrives over the Wearable Data Layer
 * from the phone, so a fixture loaded on the phone already reaches the watch's
 * timetable -- but the library pass does not travel that way. The watch fetches
 * its own QR from `api.lib.ntust.edu.tw` using credentials mirrored from the
 * phone, so without a real library account the page can only ever render
 * "sign in on your phone", which is the one thing a store screenshot must not
 * show.
 *
 * Mirroring the credentials instead was the other option and is worse: the
 * watch would then make a real network call with a made-up token and render
 * the error. The payload has to be overridden, not the login.
 *
 * Written by `WearFixtureReceiver` (debug source set only). Read from
 * `BuildConfig.DEBUG` branches in the library screen and its controller, so a
 * release build folds them away and never references this class.
 */
class WearFixtureStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Payload to encode instead of asking the library server for one. */
    var libraryQrContent: String?
        get() = prefs.getString(KEY_QR_CONTENT, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_QR_CONTENT) else putString(KEY_QR_CONTENT, value)
        }.apply()

    /** Name to show above the pass, in place of the mirrored library user. */
    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_USERNAME) else putString(KEY_USERNAME, value)
        }.apply()

    /**
     * Whether to render the pass as signed in on the strength of
     * [libraryQrContent] alone. Without it the page shows its "open TigerDuck
     * on your phone" prompt and the QR never appears.
     */
    var fakeSignedIn: Boolean
        get() = prefs.getBoolean(KEY_FAKE_SIGNED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_FAKE_SIGNED_IN, value).apply()

    /** The override is only live when there is actually a payload to draw. */
    val isActive: Boolean
        get() = fakeSignedIn && !libraryQrContent.isNullOrBlank()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_FILE = "wear_debug_fixture"
        private const val KEY_QR_CONTENT = "library_qr_content"
        private const val KEY_USERNAME = "username"
        private const val KEY_FAKE_SIGNED_IN = "library_qr_fake_signed_in"

        @Volatile
        private var instance: WearFixtureStore? = null

        fun get(context: Context): WearFixtureStore =
            instance ?: synchronized(this) {
                instance ?: WearFixtureStore(context.applicationContext).also { instance = it }
            }
    }
}
