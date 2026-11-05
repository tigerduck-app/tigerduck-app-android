package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.InMemoryStoredPasswordLatch
import org.ntust.app.tigerduck.clubs.ClubActivities
import org.ntust.app.tigerduck.clubs.ClubApiClient
import org.ntust.app.tigerduck.clubs.ClubSubscriptions
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * The school's stores, as its sign-out lists them: built only when a list is read, so a store
 * nothing has used this process is cleared all the same, and none is built with the plan.
 */
class SchoolStoresTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val built = mutableListOf<String>()

    private fun <T : Any> counted(name: String, make: () -> T) = dagger.Lazy {
        built += name
        make()
    }

    private fun stores() = SchoolStores(
        counted("read state") { BulletinReadStateStore(context) },
        counted("preferences") { prefs },
        counted("latch") { InMemoryStoredPasswordLatch() },
        counted("clubs") { ClubSubscriptions(context) },
        counted("club feed") { ClubActivities(context, ClubApiClient(OkHttpClient())) },
        counted("imported") { ImportedClassTableStore({ File(context.filesDir, "imported.json") }, { null }, Dispatchers.Unconfined) },
        counted("outbox") { Outbox(emptySet()) },
        counted("cache") { DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs)) },
    )

    @Test
    fun `no store is built until a sign-out reads its list`() {
        val stores = stores()
        assertEquals(emptyList<String>(), built)

        stores.atOnce
        assertEquals(listOf("read state", "preferences", "latch", "clubs", "club feed"), built)
        stores.late
        assertEquals(listOf("read state", "preferences", "latch", "clubs", "club feed", "imported", "outbox", "cache"), built)
    }

    @Test
    fun `each list holds the school's stores in the order its sign-out clears them`() {
        assertEquals(
            listOf(
                BulletinReadStateStore::class, AppPreferences::class, InMemoryStoredPasswordLatch::class,
                ClubSubscriptions::class, ClubActivities::class,
            ),
            stores().atOnce.map { it::class },
        )
        assertEquals(listOf(ImportedClassTableStore::class, Outbox::class, DataCache::class), stores().late.map { it::class })
    }

    @Test
    fun `the school's sign-out clears the club subscriptions, their unsent mark and the club feed`() {
        val signIn = org.ntust.app.tigerduck.util.testSignIn()
        ClubSubscriptions(context).subscribe(9001, signIn)
        context.getSharedPreferences("club_activities", 0).edit().putString("payload", "{}").putString("etag", "\"v1\"").apply()
        context.getSharedPreferences("club_directory", 0).edit().putString("payload", "{}").apply()

        stores().atOnce.forEach { it.forgetAccount() }

        assertEquals(emptyMap<String, Any?>(), context.getSharedPreferences("club_subscriptions", 0).all)
        assertEquals(emptyMap<String, Any?>(), context.getSharedPreferences("club_activities", 0).all)
        // The directory is the school's public list, the device's as on iOS.
        assertEquals("{}", context.getSharedPreferences("club_directory", 0).getString("payload", null))
    }

    @Test
    fun `the outbox is on the school's late list, before DataCache`() {
        val late = stores().late.map { it::class }

        assertTrue(Outbox::class in late)
        assertTrue("its memory goes while the queue files are still there", late.indexOf(Outbox::class) < late.indexOf(DataCache::class))
    }
}
