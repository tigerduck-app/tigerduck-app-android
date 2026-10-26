package org.ntust.app.tigerduck.mail.store

import com.google.gson.Gson
import org.ntust.app.tigerduck.data.model.mail.HeldAttachmentDto
import org.ntust.app.tigerduck.data.model.mail.HeldSendDto
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * The mail the compose screen holds for Undo or is sending (tigerduck-app#555), kept on the phone
 * from the moment the send is confirmed until it has gone out or been taken back, so that the
 * system ending the app in between does not lose it. One directory per mail under [root], in the
 * app's own storage, which no backup copies: the record, and a copy of each file picked on the
 * phone, whose picker grant may not outlive the process. A file carried over from a mail on the
 * server is kept by its place there, and downloaded at the send as before.
 *
 * Nothing in it is ever sent on its own: after a restart a mail comes back to the composer for the
 * student to review ([orphan]), and one whose send had started says so ([Held.sendingSinceMs]),
 * as it may have gone out already (tigerduck-app#483). A mail belongs to its [Held.owner], the
 * signed-in address, and to no one else: a sign-out or another student signing in empties the
 * store ([clear]), and another student's mail found later is deleted, not shown. The sign-in
 * generation the rest of the mail code pins writes by lives in memory alone, so it guards the
 * writes ([save]'s `stillCurrent`) and not what a restart finds.
 *
 * Every read is tolerant: an unreadable, half-written or older-layout mail is deleted and never
 * blocks the composer.
 */
class HeldSendStore(
    private val root: File,
    private val gson: Gson = Gson(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the composer shows again: the fields and where the mail came from. */
    data class Fields(
        val mode: String,
        val sourceFolder: String,
        val sourceUid: Long,
        val sourceLoaded: Boolean,
        val inReplyTo: String?,
        val references: String?,
        val to: List<String>,
        val cc: List<String>,
        val bcc: List<String>,
        val subject: String,
        val body: String,
    )

    /** A file carried over from mail [uid] of [folder] on the server, as MIME part [partId]. */
    data class Original(val folder: String, val uid: Long, val partId: String)

    /** One attachment of a kept mail: its [copy] on the phone, or its [original] on the server. */
    data class Attachment(
        val id: String,
        val fileName: String,
        val contentType: String,
        val sizeBytes: Long,
        val copy: File?,
        val original: Original?,
    )

    /**
     * A kept mail. [sendingSinceMs] is when its send started, null while it was only held.
     * [missing] names the files picked for it whose copy is gone, which only damage to the app's
     * storage does: left out of [attachments], for the composer to say so.
     */
    data class Held(
        val id: String,
        val owner: String,
        val savedAtMs: Long,
        val sendingSinceMs: Long?,
        val fields: Fields,
        val attachments: List<Attachment>,
        val missing: List<String> = emptyList(),
    )

    /**
     * An attachment to keep: the bytes [open] reads, copied; one already kept as [stored], which
     * stays as it is; or an [original] on the server.
     */
    class Keep(
        val id: String,
        val fileName: String,
        val contentType: String,
        val sizeBytes: Long,
        val open: (() -> InputStream)? = null,
        val original: Original? = null,
        val stored: File? = null,
    ) {
        companion object {
            /** [attachment] kept again as it is. */
            fun of(attachment: Attachment) = Keep(
                attachment.id, attachment.fileName, attachment.contentType, attachment.sizeBytes,
                original = attachment.original, stored = attachment.copy,
            )
        }
    }

    /**
     * The mail a composer of this process holds: never an [orphan], since it is on screen. Empty
     * after a restart, which is how a mail the system ended the app over is told from one in use.
     */
    private val live = mutableSetOf<String>()

    /**
     * The sign-in generation each mail kept in this process was kept under, which a sign-out's late
     * cleanup goes by ([clear]); in memory alone, like the generation.
     */
    private val keptUnder = mutableMapOf<String, Int>()

    /**
     * Keeps mail [id] of [owner], held, under sign-in [generation], unless [stillCurrent] says the
     * account has left, asked under the lock [clear] takes. A mail kept before is replaced, its
     * copies the new one still names kept as they are and the others deleted. Says whether it kept
     * it; a file that cannot be read leaves the mail as it was.
     */
    @Synchronized
    fun save(
        id: String,
        owner: String,
        fields: Fields,
        attachments: List<Keep>,
        generation: Int = 0,
        stillCurrent: () -> Boolean,
    ): Boolean {
        if (!stillCurrent()) return false
        live += id
        keptUnder[id] = generation
        val dir = File(root, id)
        val before = dir.listFiles()?.mapTo(mutableSetOf()) { it.name }.orEmpty()
        return try {
            dir.mkdirs()
            val kept = attachments.map { keep ->
                val copy = when {
                    keep.stored != null && keep.stored.parentFile == dir && keep.stored.isFile -> keep.stored.name
                    keep.open != null -> "a-${UUID.randomUUID()}".also { name ->
                        keep.open.invoke().use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
                    }
                    else -> null
                }
                HeldAttachmentDto(
                    keep.id, keep.fileName, keep.contentType, keep.sizeBytes, copy,
                    keep.original?.folder, keep.original?.uid ?: -1, keep.original?.partId,
                )
            }
            val savedAt = read(dir)?.savedAtMillis?.takeIf { it > 0 } ?: clock()
            write(dir, HeldSendDto(
                VERSION, owner.lowercase(), savedAt, 0, fields.mode, fields.sourceFolder, fields.sourceUid,
                fields.sourceLoaded, fields.inReplyTo, fields.references, fields.to, fields.cc, fields.bcc,
                fields.subject, fields.body, kept,
            ))
            val named = kept.mapNotNullTo(mutableSetOf(RECORD)) { it.copy }
            dir.listFiles()?.filter { it.name !in named }?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            // Only what this attempt wrote goes: the mail kept before, if any, stays whole.
            dir.listFiles()?.filter { it.name !in before }?.forEach { it.delete() }
            false
        }
    }

    /**
     * Marks mail [id] as sending, from now: its send to the server is about to start. Says whether
     * it did. Unmarked, the send must not go out kept as held, or a restart would offer it again as
     * unsent: the caller [forget]s it first.
     */
    @Synchronized
    fun markSending(id: String): Boolean {
        val dir = File(root, id)
        val record = read(dir) ?: return false
        return runCatching { write(dir, record.copy(sendingSinceMillis = clock())) }.isSuccess
    }

    /**
     * Mail [id] is no longer kept, taken back or failed for sure, while its composer still reads
     * the copies, which go once it lets go ([release]).
     */
    @Synchronized
    fun forget(id: String) {
        File(File(root, id), RECORD).delete()
    }

    /** Mail [id] and its copies go: it has gone out, or was saved as a draft or discarded. */
    @Synchronized
    fun delete(id: String) {
        live -= id
        keptUnder -= id
        File(root, id).deleteRecursively()
    }

    /**
     * The composer of mail [id] has closed. A mail still to resolve stays, for the composer to
     * open again after a restart; one [forget] let go goes with its copies.
     */
    @Synchronized
    fun release(id: String) {
        live -= id
        val dir = File(root, id)
        if (!File(dir, RECORD).isFile) dir.deleteRecursively()
    }

    /**
     * The kept mail of [owner] goes, as anything unreadable does, when that account leaves: it
     * signed out, or another student signed in. Only what was kept under a sign-in before
     * [below], and what this process did not keep, from before a restart: the cleanup of a
     * sign-out runs some time after it, when the next student, or the same one signed in again,
     * may have mail of their own kept. Null: everything.
     */
    @Synchronized
    fun clear(owner: String?, below: Int = Int.MAX_VALUE) {
        if (owner == null) {
            live.clear()
            keptUnder.clear()
            root.deleteRecursively()
            return
        }
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val record = read(dir)
            val departing = record == null ||
                (record.owner.equals(owner, ignoreCase = true) && (keptUnder[dir.name] ?: Int.MIN_VALUE) < below)
            if (departing) {
                live -= dir.name
                keptUnder -= dir.name
                dir.deleteRecursively()
            }
        }
    }

    /**
     * Mail [id] for a composer to show again, held for it from now: null unless it is [owner]'s,
     * or when a composer of this process holds it already, so two never show one mail.
     */
    @Synchronized
    fun adopt(id: String, owner: String): Held? {
        if (id in live) return null
        val held = held(id, read(File(root, id)) ?: return null)
        if (!held.owner.equals(owner, ignoreCase = true)) return null
        live += id
        return held
    }

    /**
     * The oldest mail of [owner] that no composer of this process holds: one the system ended the
     * app over, to open in the composer again. Everything else found on the way goes: another
     * student's mail, and anything unreadable or half-written.
     */
    @Synchronized
    fun orphan(owner: String): Held? {
        val found = root.listFiles().orEmpty().filter { it.isDirectory && it.name !in live }.mapNotNull { dir ->
            val held = read(dir)?.let { held(dir.name, it) }
            if (held == null || !held.owner.equals(owner, ignoreCase = true)) {
                dir.deleteRecursively()
                null
            } else {
                held
            }
        }
        return found.minWithOrNull(compareBy<Held> { it.savedAtMs }.thenBy { it.id })
    }

    private fun read(dir: File): HeldSendDto? {
        val file = File(dir, RECORD)
        if (!file.isFile) return null
        val dto = try {
            gson.fromJson(file.readText(), HeldSendDto::class.java)
        } catch (e: Exception) {
            null
        } ?: return null
        return dto.takeIf { it.version == VERSION && !it.owner.isNullOrBlank() }
    }

    /** [dto] of the mail in directory [id], its copies checked; a copy gone is left out, and named in [Held.missing]. */
    private fun held(id: String, dto: HeldSendDto): Held {
        val dir = File(root, id)
        val missing = mutableListOf<String>()
        val attachments = dto.attachments.orEmpty().filterNotNull().mapNotNull { a ->
            val fileName = a.fileName ?: return@mapNotNull null
            val contentType = a.contentType ?: return@mapNotNull null
            val copy = a.copy?.takeIf { '/' !in it && it != ".." && it != "." }?.let { File(dir, it) }?.takeIf { it.isFile }
            val original = if (a.folder != null && a.partId != null && a.uid >= 0) Original(a.folder, a.uid, a.partId) else null
            if (copy == null && original == null) {
                missing += fileName
                return@mapNotNull null
            }
            Attachment(a.id ?: UUID.randomUUID().toString(), fileName, contentType, a.sizeBytes, copy, original)
        }
        return Held(
            id = id,
            owner = dto.owner.orEmpty(),
            savedAtMs = dto.savedAtMillis,
            sendingSinceMs = dto.sendingSinceMillis.takeIf { it > 0 },
            fields = Fields(
                mode = dto.mode.orEmpty(),
                sourceFolder = dto.sourceFolder.orEmpty(),
                sourceUid = dto.sourceUid,
                sourceLoaded = dto.sourceLoaded,
                inReplyTo = dto.inReplyTo,
                references = dto.references,
                to = dto.to.orEmpty().filterNotNull(),
                cc = dto.cc.orEmpty().filterNotNull(),
                bcc = dto.bcc.orEmpty().filterNotNull(),
                subject = dto.subject.orEmpty(),
                body = dto.body.orEmpty(),
            ),
            attachments = attachments,
            missing = missing,
        )
    }

    private fun write(dir: File, dto: HeldSendDto) {
        val tmp = File(dir, "$RECORD.tmp")
        tmp.writeText(gson.toJson(dto))
        if (!tmp.renameTo(File(dir, RECORD))) {
            File(dir, RECORD).delete()
            if (!tmp.renameTo(File(dir, RECORD))) throw java.io.IOException("record not written")
        }
    }

    companion object {
        const val VERSION = 1
        private const val RECORD = "record.json"
    }
}
