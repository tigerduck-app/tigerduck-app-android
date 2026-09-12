package org.ntust.app.tigerduck.demo

import android.content.Context
import android.util.Log
import androidx.core.os.ConfigurationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The store-review account. Signing in with the credentials in
 * `assets/demo.json` puts the app on that file's made-up student and never
 * contacts a server — not NTUST, not Moodle, not the library, not ours.
 *
 * Play review has no NTUST account to sign in with and a real one cannot be
 * handed out, so without this a reviewer sees nothing past the sign-in page.
 *
 * How: [DebugFixtureStore.demoMode] makes
 * [org.ntust.app.tigerduck.debug.DemoModeInterceptor] refuse every request on
 * every OkHttp client, the file's data goes into the same caches the real
 * fetches fill, and [ServerStatusTracker] holds every status dot at OK. Each
 * screen then takes the offline path it already has, over the demo data.
 * Signing out leaves it.
 */
@Singleton
class DemoAccount @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: DebugFixtureStore,
    private val dataCache: DataCache,
    private val bulletinCache: BulletinCache,
    private val appPreferences: AppPreferences,
) {
    /** Whether the demo account is signed in. Read live: sign-in flips it without a restart. */
    val isActive: Boolean
        get() = store.demoMode

    /** Whether these are the demo account's credentials. Reads the bundled file and nothing else. */
    fun matches(studentId: String, password: String): Boolean =
        load(DemoFixture.LANG_EN)?.matches(studentId, password) == true

    /**
     * Switch to the demo account. Demo mode goes on first, so nothing that
     * starts after this can reach a server and overwrite the data written
     * next.
     */
    suspend fun enter() {
        val fixture = load(currentLang()) ?: return
        store.demoMode = true
        store.studentIdOverride = fixture.studentId
        store.libraryQrContent = fixture.libraryQrContent
        store.libraryFakeSignedIn = fixture.libraryFakeSignedIn
        ServerStatusTracker.enterDemoMode()
        write(fixture)
    }

    /**
     * Write the demo data again, on each app start while signed in: in the
     * language the app now shows, and over anything a request already in
     * flight at sign-in may have written after it.
     */
    suspend fun reapply() {
        if (!isActive) return
        load(currentLang())?.let { write(it) }
    }

    /** Leave the demo account. Part of sign-out, which clears the cached data itself. */
    fun exit() {
        store.clear()
        ServerStatusTracker.exitDemoMode()
    }

    private suspend fun write(fixture: DemoFixture) {
        fixture.courses?.let { dataCache.saveCourses(it) }
        fixture.assignments?.let { dataCache.saveAssignments(it) }
        fixture.bulletins?.let { bulletinCache.save(it) }
        fixture.calendar?.let { dataCache.saveCalendarEvents(it) }
    }

    private fun load(lang: String): DemoFixture? = runCatching {
        context.assets.open(ASSET).bufferedReader().use { DemoFixture.parse(it.readText(), lang) }
    }.onFailure { Log.e(TAG, "cannot read $ASSET", it) }.getOrNull()

    /** The language the app is showing, resolved the way push registration resolves it. */
    private fun currentLang(): String {
        val locale = AppLanguageManager.resolveExplicitLocale(appPreferences.appLanguage)
            ?: ConfigurationCompat.getLocales(context.resources.configuration)[0]
        return if (locale?.language == "zh") DemoFixture.LANG_ZH else DemoFixture.LANG_EN
    }

    private companion object {
        const val TAG = "DemoAccount"
        const val ASSET = "demo.json"
    }
}
