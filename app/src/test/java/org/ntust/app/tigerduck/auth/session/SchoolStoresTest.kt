package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.InMemoryStoredPasswordLatch
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
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
        counted("imported") { ImportedClassTableStore({ File(context.filesDir, "imported.json") }, { null }, Dispatchers.Unconfined) },
        counted("cache") { DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs)) },
    )

    @Test
    fun `no store is built until a sign-out reads its list`() {
        val stores = stores()
        assertEquals(emptyList<String>(), built)

        stores.atOnce
        assertEquals(listOf("read state", "preferences", "latch"), built)
        stores.late
        assertEquals(listOf("read state", "preferences", "latch", "imported", "cache"), built)
    }

    @Test
    fun `each list holds the school's stores in the order its sign-out clears them`() {
        assertEquals(
            listOf(BulletinReadStateStore::class, AppPreferences::class, InMemoryStoredPasswordLatch::class),
            stores().atOnce.map { it::class },
        )
        assertEquals(listOf(ImportedClassTableStore::class, DataCache::class), stores().late.map { it::class })
    }
}
