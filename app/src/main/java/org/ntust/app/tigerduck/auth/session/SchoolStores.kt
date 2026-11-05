package org.ntust.app.tigerduck.auth.session

import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.clubs.ClubActivities
import org.ntust.app.tigerduck.clubs.ClubSubscriptions
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.sync.outbox.Outbox
import javax.inject.Inject

/**
 * The school account's stores, in the order its sign-out clears them: the one list of them. Each
 * comes through a `dagger.Lazy` and is built only when a list is read, at a sign-out, so a store
 * nothing in this process has used is cleared all the same, and a store may depend on what signs
 * out without a cycle.
 *
 * `AccountStateInventoryTest` fails on an [AccountScoped] or [LateAccountScoped] type on no list
 * here, and on a school row whose clearer nothing the school's sign-out runs reaches.
 */
class SchoolStores @Inject constructor(
    private val bulletinReadStateStore: dagger.Lazy<BulletinReadStateStore>,
    private val prefs: dagger.Lazy<AppPreferences>,
    private val storedPasswordLatch: dagger.Lazy<StoredPasswordLatch>,
    private val clubSubscriptions: dagger.Lazy<ClubSubscriptions>,
    private val clubActivities: dagger.Lazy<ClubActivities>,
    private val importedClassTables: dagger.Lazy<ImportedClassTableStore>,
    private val outbox: dagger.Lazy<Outbox>,
    private val dataCache: dagger.Lazy<DataCache>,
) {
    /**
     * Cleared at once, each a step of its own, before the account shows as signed out: the club
     * subscriptions with their unsent mark, and the club feed fetched for them.
     */
    val atOnce: List<AccountScoped>
        get() = listOf(bulletinReadStateStore.get(), prefs.get(), storedPasswordLatch.get(), clubSubscriptions.get(), clubActivities.get())

    /**
     * Cleared after, one by one, on the application scope; the widgets are drawn again once they are
     * done. The outbox forgets what it holds in memory before DataCache takes the queues' files.
     */
    val late: List<LateAccountScoped>
        get() = listOf(importedClassTables.get(), outbox.get(), dataCache.get())
}
