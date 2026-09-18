package org.ntust.app.tigerduck.mail.store

import com.google.gson.Gson
import org.ntust.app.tigerduck.data.model.mail.BodyCacheDto
import org.ntust.app.tigerduck.data.model.mail.FolderCacheDto
import org.ntust.app.tigerduck.data.model.mail.SourceCacheDto
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailPage
import java.io.File
import java.security.MessageDigest

/**
 * Versioned JSON files under cacheDir/mail (spec §8.1). The server is the
 * source of truth: anything unreadable is deleted and fetched again.
 */
class MailCache(
    private val root: File,
    private val gson: Gson = Gson(),
    private val maxBodyBytes: Long = 20L * 1024 * 1024,
) {
    private val foldersDir get() = File(root, "folders")
    private val bodiesDir get() = File(root, "bodies")
    val attachmentsDir: File get() = File(root, "attachments")

    /**
     * A single body or source larger than this is not cached at all. Bodies and sources share
     * one [maxBodyBytes] LRU budget (see [saveSource]); an entry anywhere near the full budget
     * is nearly as bad as one over it, since caching it evicts almost everything else just to
     * make room for one item. Capping any single entry at half the budget guarantees caching
     * one thing can never evict more than half of what's already there, so the cache always
     * keeps more than just whatever was written most recently.
     */
    private val maxEntryBytes = maxBodyBytes / 2

    @Synchronized
    fun loadFolder(folder: String): FolderCacheDto? {
        val file = File(foldersDir, key(folder))
        val dto = read(file, FolderCacheDto::class.java) ?: return null
        if (dto.version != VERSION) {
            file.delete(); return null
        }
        return dto
    }

    @Synchronized
    fun saveFolder(folder: String, page: MailPage) {
        write(
            File(foldersDir, key(folder)),
            FolderCacheDto(VERSION, page.uidValidity, page.totalMessages, page.nextBeforeSeq ?: 0, page.messages.map { it.toDto() }),
        )
    }

    /** Drops [folder]'s cached page, e.g. once its UIDVALIDITY no longer matches the server's. */
    @Synchronized
    fun deleteFolder(folder: String) {
        File(foldersDir, key(folder)).delete()
    }

    @Synchronized
    fun loadBody(folder: String, uid: Long, uidValidity: Long): MailBody? {
        val file = File(bodiesDir, key(bodyKey(folder, uid)))
        val dto = read(file, BodyCacheDto::class.java) ?: return null
        if (dto.version != VERSION || dto.uidValidity != uidValidity || dto.uid != uid) {
            file.delete(); return null
        }
        file.setLastModified(System.currentTimeMillis())
        return dto.toModel()
    }

    @Synchronized
    fun saveBody(folder: String, uid: Long, uidValidity: Long, body: MailBody) {
        saveBounded(File(bodiesDir, key(bodyKey(folder, uid))), body.toDto(uidValidity, uid))
    }

    /**
     * The raw source, keyed and invalidated exactly like a body and kept in the same directory,
     * so one [maxBodyBytes] budget and one LRU sweep cover bodies and sources together. Its key
     * carries a suffix a body key can never produce, so the two never collide on one file.
     */
    @Synchronized
    fun loadSource(folder: String, uid: Long, uidValidity: Long): String? {
        val file = File(bodiesDir, key(sourceKey(folder, uid)))
        val dto = read(file, SourceCacheDto::class.java) ?: return null
        val source = dto.source
        if (dto.version != VERSION || dto.uidValidity != uidValidity || dto.uid != uid || source == null) {
            file.delete(); return null
        }
        file.setLastModified(System.currentTimeMillis())
        return source
    }

    @Synchronized
    fun saveSource(folder: String, uid: Long, uidValidity: Long, source: String) {
        saveBounded(File(bodiesDir, key(sourceKey(folder, uid))), SourceCacheDto(VERSION, uidValidity, uid, source))
    }

    /**
     * Every byte under the cache root — folders, bodies, sources and downloaded attachments
     * alike, since that is what actually occupies the disk. Walking the tree is real IO, so
     * callers keep it off the main thread.
     */
    @Synchronized
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Synchronized
    fun clearAll() {
        root.deleteRecursively()
    }

    private fun <T> read(file: File, type: Class<T>): T? {
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), type)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun write(file: File, value: Any) {
        writeText(file, gson.toJson(value))
    }

    /**
     * The bodies/sources write path: an entry over [maxEntryBytes] is skipped outright rather
     * than written and then cleaned up by [evictBodies] — checking the size up front means an
     * oversized entry never touches disk and never disturbs the LRU order of what's already
     * cached (see [maxEntryBytes]).
     */
    private fun saveBounded(file: File, value: Any) {
        val json = gson.toJson(value)
        if (json.toByteArray(Charsets.UTF_8).size > maxEntryBytes) return
        writeText(file, json)
        evictBodies()
    }

    private fun writeText(file: File, json: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: java.io.IOException) {
            tmp.delete()
        }
    }

    private fun evictBodies() {
        val files = bodiesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBodyBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun bodyKey(folder: String, uid: Long) = "$folder\u0000$uid"

    private fun sourceKey(folder: String, uid: Long) = "$folder\u0000$uid\u0000source"

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"

    companion object {
        const val VERSION = 1
    }
}
