package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.mail.store.MailStateStore

class InMemoryMailStateStore : MailStateStore {
    override var displayName: String? = null
    override var notificationsEnabled: Boolean = true
    override var authFailed: Boolean = false
    override var demoMailbox: Boolean = false
    override var inboxUidValidity: Long = 0
    override var inboxSeenUidNext: Long = 0
    override var lastForegroundCheckAt: Long = 0
    override var diagnostics: List<String> = emptyList()
        set(value) { field = value.take(10) }
    private val owned = mutableMapOf<Pair<String, Long>, Set<Long>>()

    override fun ownedDeleted(folder: String, uidValidity: Long): Set<Long> = owned[folder to uidValidity].orEmpty()

    override fun setOwnedDeleted(folder: String, uidValidity: Long, uids: Set<Long>) {
        owned.keys.removeAll { it.first == folder }
        owned[folder to uidValidity] = uids
    }

    override fun clear() {
        displayName = null; notificationsEnabled = true; authFailed = false; demoMailbox = false
        inboxUidValidity = 0; inboxSeenUidNext = 0; lastForegroundCheckAt = 0; diagnostics = emptyList()
        owned.clear()
    }
}
