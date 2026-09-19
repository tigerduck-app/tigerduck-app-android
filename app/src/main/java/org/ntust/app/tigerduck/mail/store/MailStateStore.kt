package org.ntust.app.tigerduck.mail.store

import android.content.Context

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
    /** Newest first, at most 10, `"<epochMillis>|<source>|<outcome>"`. */
    var diagnostics: List<String>
    fun ownedDeleted(folder: String, uidValidity: Long): Set<Long>
    fun setOwnedDeleted(folder: String, uidValidity: Long, uids: Set<Long>)
    fun clear()
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

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val K_NAME = "display_name"
        const val K_NOTIFY = "notifications_enabled"
        const val K_AUTH_FAILED = "auth_failed"
        const val K_DEMO = "demo_mailbox"
        const val K_VALIDITY = "inbox_uid_validity"
        const val K_MARKER = "inbox_seen_uid_next"
        const val K_FOREGROUND = "last_foreground_check_at"
        const val K_DIAG = "diagnostics"
        const val K_OWNED = "owned_deleted"
    }
}
