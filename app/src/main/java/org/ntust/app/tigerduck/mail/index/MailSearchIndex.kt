package org.ntust.app.tigerduck.mail.index

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import org.ntust.app.tigerduck.data.model.mail.MailSummaryDto
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailRow
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.store.toDto
import org.ntust.app.tigerduck.mail.store.toModel
import java.io.Closeable
import java.io.File

/**
 * Every mail's sender, subject and text, kept on the phone so a search runs here: the school's
 * server takes a search in ASCII only. It answers `NO [BADCHARSET]` to UTF-8 and Big5 alike, and
 * finds nothing for Chinese sent without a charset. Each mail's [MailSummary] is kept with it, so
 * the list pages back through the mail downloaded without asking the server.
 *
 * The index belongs to one [owner], the signed-in mail account: a write from any other, one that
 * left while it was fetching, is refused, and the next account to [open] a folder starts it empty.
 */
interface MailSearchIndex {
    /**
     * One mail as a search reads it, [sender] holding its name and address both, and as the list
     * shows it, [summary].
     */
    data class Entry(val uid: Long, val sender: String, val subject: String, val body: String, val summary: MailSummary)

    /** The UIDVALIDITY [folder] was indexed under for [owner], or null if it has not been. */
    fun uidValidity(owner: String, folder: String): Long?

    /**
     * Makes [folder] [owner]'s at [uidValidity], emptying it when either differs from before, so a
     * recreated folder or another account starts over. False, with nothing changed, unless
     * [stillCurrent].
     */
    fun open(owner: String, folder: String, uidValidity: Long, stillCurrent: () -> Boolean): Boolean

    /** The UIDs of [folder] indexed for [owner]. */
    fun uids(owner: String, folder: String): Set<Long>

    /**
     * Adds [entries] to [folder], unless the folder is no longer [owner]'s at [uidValidity] or
     * [stillCurrent] says the account left. Says whether it added them.
     */
    fun add(owner: String, folder: String, uidValidity: Long, entries: List<Entry>, stillCurrent: () -> Boolean): Boolean

    /**
     * Drops [uids], gone from the server, from [folder] while it is indexed under [uidValidity],
     * unless [stillCurrent] says the account left.
     */
    fun remove(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>, stillCurrent: () -> Boolean)

    /**
     * The UIDs, newest first, of [owner]'s mail in [folder] whose sender, subject or text holds
     * [query]; none unless the folder is indexed under [uidValidity], which they only name mail in.
     */
    fun search(owner: String, folder: String, uidValidity: Long, query: String): List<Long>

    /** The summaries of those of [uids] downloaded into [folder] under [uidValidity], by UID. */
    fun summaries(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>): Map<Long, MailSummary>

    /**
     * Every mail downloaded for [owner], in every folder, as the list shows it: the people the
     * compose screen suggests come from it (`RecipientSuggestions`). None for anyone else.
     */
    fun allMail(owner: String): List<MailRow>

    /**
     * Brings the flags of [folder]'s mail downloaded under [uidValidity] to [flags], as the server
     * has them now; mail flagged `\Deleted` leaves it. Nothing changes once [stillCurrent] says the
     * account left.
     */
    fun updateFlags(owner: String, folder: String, uidValidity: Long, flags: Map<Long, MailFlags>, stillCurrent: () -> Boolean)

    /** Empties it, for a sign-out or the user deleting it, and gives back the disk it took. */
    fun clear()

    /**
     * [clear], unless [keep] says the mail of the owner it has now (null for none) stays: asked
     * under its lock, which every download's first write of a folder takes too, so the answer is
     * still true when the clear runs.
     */
    fun clearUnless(keep: (owner: String?) -> Boolean)

    /** The disk it takes, in bytes. */
    fun sizeBytes(): Long
}

/**
 * [MailSearchIndex] in SQLite, in the app's own storage, which no backup or device transfer copies
 * (data_extraction_rules.xml). [name] null keeps it in memory.
 */
class SqliteMailSearchIndex(private val context: Context, private val name: String? = DB_NAME) : MailSearchIndex, Closeable {

    private val gson = Gson()

    private val helper = object : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE owner (id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE folders (name TEXT PRIMARY KEY NOT NULL, uid_validity INTEGER NOT NULL)")
            db.execSQL(
                "CREATE TABLE mail (folder TEXT NOT NULL, uid INTEGER NOT NULL, sender TEXT NOT NULL, " +
                    "subject TEXT NOT NULL, body TEXT NOT NULL, summary TEXT NOT NULL, PRIMARY KEY (folder, uid)) WITHOUT ROWID",
            )
        }

        // Only a copy of the server's mail: a new layout starts over and fetches it again.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

        private fun recreate(db: SQLiteDatabase) {
            listOf("owner", "folders", "mail").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun ownerLocked(): String? =
        db.rawQuery("SELECT id FROM owner", null).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun validityLocked(folder: String): Long? =
        db.rawQuery("SELECT uid_validity FROM folders WHERE name = ?", arrayOf(folder)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    private fun clearLocked() {
        db.delete("mail", null, null)
        db.delete("folders", null, null)
        db.delete("owner", null, null)
    }

    @Synchronized
    override fun uidValidity(owner: String, folder: String): Long? =
        if (ownerLocked() == owner) validityLocked(folder) else null

    @Synchronized
    override fun open(owner: String, folder: String, uidValidity: Long, stillCurrent: () -> Boolean): Boolean {
        if (!stillCurrent()) return false
        transaction {
            if (ownerLocked() != owner) {
                clearLocked()
                db.insert("owner", null, ContentValues().apply { put("id", owner) })
            }
            if (validityLocked(folder) != uidValidity) {
                db.delete("mail", "folder = ?", arrayOf(folder))
                db.insertWithOnConflict(
                    "folders", null,
                    ContentValues().apply { put("name", folder); put("uid_validity", uidValidity) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
        return true
    }

    @Synchronized
    override fun uids(owner: String, folder: String): Set<Long> {
        if (ownerLocked() != owner) return emptySet()
        return db.rawQuery("SELECT uid FROM mail WHERE folder = ?", arrayOf(folder)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    @Synchronized
    override fun add(
        owner: String,
        folder: String,
        uidValidity: Long,
        entries: List<MailSearchIndex.Entry>,
        stillCurrent: () -> Boolean,
    ): Boolean {
        if (!stillCurrent() || ownerLocked() != owner || validityLocked(folder) != uidValidity) return false
        transaction {
            entries.forEach { e ->
                db.insertWithOnConflict(
                    "mail", null,
                    ContentValues().apply {
                        put("folder", folder)
                        put("uid", e.uid)
                        put("sender", e.sender)
                        put("subject", e.subject)
                        put("body", e.body)
                        put("summary", gson.toJson(e.summary.toDto()))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
        return true
    }

    @Synchronized
    override fun remove(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>, stillCurrent: () -> Boolean) {
        if (uids.isEmpty() || !stillCurrent() || ownerLocked() != owner || validityLocked(folder) != uidValidity) return
        transaction { uids.forEach { db.delete("mail", "folder = ? AND uid = ?", arrayOf(folder, it.toString())) } }
    }

    @Synchronized
    override fun search(owner: String, folder: String, uidValidity: Long, query: String): List<Long> {
        if (query.isEmpty() || ownerLocked() != owner || validityLocked(folder) != uidValidity) return emptyList()
        // LIKE ignores case for ASCII, as the server's search did; % and _ in what was typed are
        // matched as themselves.
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return db.rawQuery(
            "SELECT uid FROM mail WHERE folder = ? AND (sender LIKE ?2 ESCAPE '\\' OR subject LIKE ?2 ESCAPE '\\' " +
                "OR body LIKE ?2 ESCAPE '\\') ORDER BY uid DESC",
            arrayOf(folder, pattern),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    @Synchronized
    override fun summaries(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>): Map<Long, MailSummary> {
        if (uids.isEmpty() || ownerLocked() != owner || validityLocked(folder) != uidValidity) return emptyMap()
        return buildMap {
            // In chunks under SQLite's limit on bound arguments.
            uids.chunked(500).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                db.rawQuery(
                    "SELECT summary FROM mail WHERE folder = ? AND uid IN ($marks)",
                    arrayOf(folder) + chunk.map { it.toString() },
                ).use { c ->
                    while (c.moveToNext()) {
                        val summary = summaryOf(c.getString(0)) ?: continue
                        put(summary.uid, summary)
                    }
                }
            }
        }
    }

    @Synchronized
    override fun allMail(owner: String): List<MailRow> {
        if (ownerLocked() != owner) return emptyList()
        return db.rawQuery("SELECT folder, summary FROM mail", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val summary = summaryOf(c.getString(1)) ?: continue
                    add(MailRow(c.getString(0), summary))
                }
            }
        }
    }

    @Synchronized
    override fun updateFlags(
        owner: String,
        folder: String,
        uidValidity: Long,
        flags: Map<Long, MailFlags>,
        stillCurrent: () -> Boolean,
    ) {
        if (flags.isEmpty() || !stillCurrent()) return
        val stored = summaries(owner, folder, uidValidity, flags.keys)
        transaction {
            stored.values.forEach { summary ->
                val now = flags[summary.uid] ?: return@forEach
                if (now == summary.flags) return@forEach
                // Flagged \Deleted, which the stored summary has no room for: gone from the list,
                // as the server's own pages leave it out.
                if (now.deleted) {
                    db.delete("mail", "folder = ? AND uid = ?", arrayOf(folder, summary.uid.toString()))
                    return@forEach
                }
                db.update(
                    "mail",
                    ContentValues().apply { put("summary", gson.toJson(summary.copy(flags = now).toDto())) },
                    "folder = ? AND uid = ?",
                    arrayOf(folder, summary.uid.toString()),
                )
            }
        }
    }

    // A row a later layout cannot read is treated as not downloaded, and fetched again.
    private fun summaryOf(json: String): MailSummary? =
        runCatching { gson.fromJson(json, MailSummaryDto::class.java)?.toModel() }.getOrNull()

    @Synchronized
    override fun clearUnless(keep: (owner: String?) -> Boolean) {
        if (!keep(ownerLocked())) clear()
    }

    @Synchronized
    override fun clear() {
        transaction { clearLocked() }
        // Rows deleted leave their pages in the file: rewritten, it gives the disk back.
        db.execSQL("VACUUM")
    }

    @Synchronized
    override fun sizeBytes(): Long {
        val file = name?.let(context::getDatabasePath) ?: return 0L
        return listOf(file, File(file.path + "-journal"), File(file.path + "-wal")).sumOf { it.length() }
    }

    @Synchronized
    override fun close() = helper.close()

    private inline fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DB_NAME = "mail_search.db"
        // 2: each mail's summary.
        private const val VERSION = 2
    }
}
