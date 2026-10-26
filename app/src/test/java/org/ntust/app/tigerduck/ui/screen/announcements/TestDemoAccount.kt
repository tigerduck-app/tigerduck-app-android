package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.demo.DemoAccount

/**
 * The school account whose sign-out the bulletin list watches for; see
 * [CredentialManager.schoolAccountGeneration]. Unencrypted, as the tests cannot reach the keystore.
 */
internal fun testCredentials(context: Application) =
    CredentialManager(context.getSharedPreferences("credentials-test", Context.MODE_PRIVATE))

/** The demo account the bulletin list consults; signed in when [fixtures] says demo mode is on. */
internal fun testDemoAccount(
    context: Application,
    prefs: AppPreferences,
    cache: BulletinCache,
    fixtures: DebugFixtureStore = DebugFixtureStore(context),
) = DemoAccount(
    context,
    fixtures,
    DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs)),
    cache,
    prefs,
    ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { null }, Dispatchers.Unconfined),
)
