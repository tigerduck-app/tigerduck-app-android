package org.ntust.app.tigerduck.mail.store

import android.content.Context
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.notify.MailMuteSettings
import org.ntust.app.tigerduck.mail.notify.MailMuteSettingsCodec

/** Non-secret mail state. Its own file, so no AppPreferences migration ever touches it. */
interface MailStateStore {
    var displayName: String?
    var notificationsEnabled: Boolean
    /** Set when the server rejected the stored password; background checks stop until the next sign-in. */
    var authFailed: Boolean
    var demoMailbox: Boolean
    var inboxUidValidity: Long
    /** The seen marker: the INBOX UID from which new mail counts as new. 0 = no baseline yet. */
    var inboxSeenUidNext: Long
    var lastForegroundCheckAt: Long
    /** Which new mail is not notified: quiet hours and mute rules, the student's own like [notificationsEnabled]. */
    var muteSettings: MailMuteSettings
    /**
     * When [folder] (a server name) last answered, epoch ms; 0 = never. Per folder, so the list
     * page can date the folder on screen. See [org.ntust.app.tigerduck.mail.sync.MailChecker.fetchedAt].
     */
    fun fetchedAt(folder: String): Long

    /**
     * Dates [folder] at [atMs] while [stillCurrent] says the account the folder answered is still
     * the signed-in one, and says whether it did. Asked under the store's lock, which [clear]
     * takes too -- and a sign-out moves its account before it clears -- so a date it allows lands
     * before that clear and goes with it; the same shape as
     * [org.ntust.app.tigerduck.mail.store.MailCache.saveBodyIf].
     */
    fun setFetchedAtIf(folder: String, atMs: Long, stillCurrent: () -> Boolean): Boolean
    /** Newest first, at most 10, `"<epochMillis>|<source>|<outcome>"`. */
    var diagnostics: List<String>
    fun ownedDeleted(folder: String, uidValidity: Long): Set<Long>
    fun setOwnedDeleted(folder: String, uidValidity: Long, uids: Set<Long>)

    /**
     * How many times [clear] has run in this process. A write that reads the owned-`\Deleted` set
     * takes it first, so that what it records afterwards ([setOwnedDeletedIf]) lands in the state
     * of the student it read for. [clearSignIn] keeps the set, and leaves this as it was.
     */
    val clears: Int

    /**
     * [setOwnedDeleted] while [clears] still stands at [clearsSince], asked under the lock [clear]
     * takes, and says whether it wrote. Not on the account's generation: a student signing in over
     * another clears the state before the account moves to them, and the same student signing in
     * again moves the account but keeps the set, which must keep taking what their writes flag.
     * Nothing persisted changes; the count lives and dies with the writes it guards.
     */
    fun setOwnedDeletedIf(folder: String, uidValidity: Long, uids: Set<Long>, clearsSince: Int): Boolean
    fun clear()

    /**
     * [clear], but for the same student signing in again: what is theirs rather than the
     * sign-in's stays -- the display name, whether new mail notifies and which mail it mutes,
     * and the mail this app flagged `\Deleted` and has still to expunge.
     */
    fun clearSignIn()
}

/** Entries of the owned-`\Deleted` set: mails this app flagged but could not expunge yet (spec §8.3). */
object OwnedDeletedCodec {
    private const val SEP = '\u001f'

    fun encode(folder: String, uidValidity: Long, uid: Long): String = "$folder$SEP$uidValidity$SEP$uid"

    fun decode(entry: String): Triple<String, Long, Long>? {
        val parts = entry.split(SEP)
        if (parts.size < 3) return null
        val uid = parts.last().toLongOrNull() ?: return null
        val validity = parts[parts.size - 2].toLongOrNull() ?: return null
        val folder = parts.dropLast(2).joinToString(SEP.toString())
        return Triple(folder, validity, uid)
    }
}

class SharedPrefsMailStateStore(context: Context) : MailStateStore {
    private val prefs = context.getSharedPreferences("school_mail_state", Context.MODE_PRIVATE)

    @Volatile private var clearCount = 0
    override val clears: Int get() = clearCount

    override var displayName: String?
        get() = prefs.getString(K_NAME, null)
        set(value) = prefs.edit().apply { if (value.isNullOrBlank()) remove(K_NAME) else putString(K_NAME, value.trim()) }.apply()

    override var notificationsEnabled: Boolean
        get() = prefs.getBoolean(K_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(K_NOTIFY, value).apply()

    override var authFailed: Boolean
        get() = prefs.getBoolean(K_AUTH_FAILED, false)
        set(value) = prefs.edit().putBoolean(K_AUTH_FAILED, value).apply()

    override var demoMailbox: Boolean
        get() = prefs.getBoolean(K_DEMO, false)
        set(value) = prefs.edit().putBoolean(K_DEMO, value).apply()

    override var inboxUidValidity: Long
        get() = prefs.getLong(K_VALIDITY, 0L)
        set(value) = prefs.edit().putLong(K_VALIDITY, value).apply()

    override var inboxSeenUidNext: Long
        get() = prefs.getLong(K_MARKER, 0L)
        set(value) = prefs.edit().putLong(K_MARKER, value).apply()

    override var lastForegroundCheckAt: Long
        get() = prefs.getLong(K_FOREGROUND, 0L)
        set(value) = prefs.edit().putLong(K_FOREGROUND, value).apply()

    override var muteSettings: MailMuteSettings
        get() = MailMuteSettingsCodec.decode(prefs.getString(K_MUTE, null))
        set(value) = prefs.edit().putString(K_MUTE, MailMuteSettingsCodec.encode(value)).apply()

    override fun fetchedAt(folder: String): Long = prefs.getLong(fetchedKey(folder), 0L)

    @Synchronized
    override fun setFetchedAtIf(folder: String, atMs: Long, stillCurrent: () -> Boolean): Boolean {
        if (!stillCurrent()) return false
        prefs.edit().putLong(fetchedKey(folder), atMs).apply()
        return true
    }

    /**
     * Every folder's date under its server name, except the inbox's, which keeps the one key the
     * builds that dated the inbox alone wrote: an install upgraded from one of them then opens on
     * that date rather than on "never synced". IMAP names the inbox INBOX in any case.
     */
    private fun fetchedKey(folder: String): String =
        if (folder.equals(SpecialFolder.INBOX.imapName, ignoreCase = true)) K_FETCHED else K_FETCHED_PREFIX + folder

    override var diagnostics: List<String>
        get() = prefs.getString(K_DIAG, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit().putString(K_DIAG, value.take(10).joinToString("\n")).apply()

    override fun ownedDeleted(folder: String, uidValidity: Long): Set<Long> =
        prefs.getStringSet(K_OWNED, emptySet()).orEmpty()
            .mapNotNull(OwnedDeletedCodec::decode)
            .filter { it.first == folder && it.second == uidValidity }
            .map { it.third }
            .toSet()

    override fun setOwnedDeleted(folder: String, uidValidity: Long, uids: Set<Long>) {
        val others = prefs.getStringSet(K_OWNED, emptySet()).orEmpty()
            .filter { OwnedDeletedCodec.decode(it)?.first != folder }
        val mine = uids.map { OwnedDeletedCodec.encode(folder, uidValidity, it) }
        prefs.edit().putStringSet(K_OWNED, (others + mine).toSet()).apply()
    }

    @Synchronized
    override fun setOwnedDeletedIf(folder: String, uidValidity: Long, uids: Set<Long>, clearsSince: Int): Boolean {
        if (clearCount != clearsSince) return false
        setOwnedDeleted(folder, uidValidity, uids)
        return true
    }

    // Under the lock [setFetchedAtIf] and [setOwnedDeletedIf] ask their question under; see there.
    @Synchronized
    override fun clear() {
        clearCount++
        prefs.edit().clear().apply()
    }

    @Synchronized
    override fun clearSignIn() {
        val name = prefs.getString(K_NAME, null)
        val notify = prefs.getBoolean(K_NOTIFY, true)
        val mute = prefs.getString(K_MUTE, null)
        val owned = prefs.getStringSet(K_OWNED, emptySet()).orEmpty().toSet()
        prefs.edit().clear().apply {
            name?.let { putString(K_NAME, it) }
            putBoolean(K_NOTIFY, notify)
            mute?.let { putString(K_MUTE, it) }
            if (owned.isNotEmpty()) putStringSet(K_OWNED, owned)
        }.apply()
    }

    private companion object {
        const val K_NAME = "display_name"
        const val K_NOTIFY = "notifications_enabled"
        const val K_AUTH_FAILED = "auth_failed"
        const val K_DEMO = "demo_mailbox"
        const val K_VALIDITY = "inbox_uid_validity"
        const val K_MARKER = "inbox_seen_uid_next"
        const val K_FOREGROUND = "last_foreground_check_at"
        const val K_MUTE = "mute_settings"
        const val K_FETCHED = "inbox_fetched_at"
        const val K_FETCHED_PREFIX = "fetched_at:"
        const val K_DIAG = "diagnostics"
        const val K_OWNED = "owned_deleted"
    }
}
