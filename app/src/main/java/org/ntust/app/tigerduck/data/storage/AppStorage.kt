package org.ntust.app.tigerduck.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.browser.BrowserDataCleaner
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.store.MailCache
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app keeps on the phone that can be cleared, for Settings' Storage page: what each
 * [Kind] takes, and clearing it. Three kinds are files the app downloaded, and keeps where only it
 * can see them ([Kind.downloaded]); three are what School Mail and the TigerDuck browser keep:
 * the cached mail, the mail downloaded for search, and the sites' data. Nothing else the app keeps
 * goes with them: not a mail waiting to send, not the files a send is staging, not the app's own
 * data. The TigerDuck browser saves its downloads to the phone's Downloads folder, where they are
 * the user's, so they are not counted here.
 *
 * A mail attachment or class table image written in the last [IN_USE_MILLIS] stays, and is not
 * counted: one may still be downloading, and another app may still be reading one it was handed
 * (a draft it was shared into, a viewer that opens it again). Nothing here follows a link out of
 * the folders it measures and deletes.
 *
 * [sizes] is shared by every page that shows them, and [refresh], [clear] and
 * [clearDownloaded] take turns: a clearing outlives the page it started on, and the page open
 * when it ends shows it.
 */
@Singleton
class AppStorage internal constructor(
    private val moodle: MoodleFileDownloads,
    private val mailAttachments: () -> File,
    /** The attachment folders a download is still writing into, by name ([MailCache.newAttachmentSlot]). */
    private val attachmentsInUse: () -> Set<String>,
    private val classTableImages: () -> File,
    private val cachedMail: Store,
    private val downloadedMail: Store,
    private val websiteData: Store,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        moodle: MoodleFileDownloads,
        mailCache: MailCache,
        mail: SchoolMailRepository,
        browser: BrowserDataCleaner,
        @ApplicationContext context: Context,
    ) : this(
        moodle = moodle,
        mailAttachments = { mailCache.attachmentsDir },
        attachmentsInUse = mailCache::attachmentSlotsInUse,
        classTableImages = { File(context.cacheDir, CLASS_TABLE_EXPORT_DIR) },
        cachedMail = cachedMailStore(mailCache, Dispatchers.IO),
        downloadedMail = Store(bytes = mail::searchIndexBytes, clear = mail::deleteSearchIndex),
        websiteData = websiteDataStore(context.dataDir, context.cacheDir, Dispatchers.IO, clear = browser::clearWebsiteData),
    )

    /** Something kept as a whole, measured and cleared through its owner. */
    class Store(val bytes: suspend () -> Long, val clear: suspend () -> Unit)

    enum class Kind(val downloaded: Boolean) {
        /** Moodle's materials and attachments, and members' pictures ([MoodleFileDownloads]). */
        MOODLE_FILES(downloaded = true),

        /** Attachments opened from School Mail, each in a folder of its own. */
        MAIL_ATTACHMENTS(downloaded = true),

        /** Class table images made for sharing, each in a folder of its own. */
        CLASS_TABLE_IMAGES(downloaded = true),

        /** School Mail's cached folder pages and mails, which it fetches again as they're read. */
        CACHED_MAIL(downloaded = false),

        /** The mail School Mail downloaded for search and the list, which takes a while to fetch again. */
        DOWNLOADED_MAIL(downloaded = false),

        /** What sites keep in the TigerDuck browser: cookies, their storage, the pages' cache. */
        WEBSITE_DATA(downloaded = false),
    }

    private val _sizes = MutableStateFlow<Map<Kind, Long>?>(null)

    /** The last [refresh]'s sizes, or the sizes after the last clearing; null until the first. */
    val sizes: StateFlow<Map<Kind, Long>?> = _sizes.asStateFlow()

    private val busy = Mutex()

    suspend fun refresh() {
        busy.withLock { _sizes.value = measure() }
    }

    /** The bytes each kind takes, as far as clearing it would free them. Real IO: off the main thread. */
    suspend fun measure(): Map<Kind, Long> {
        val moodleBytes = moodle.measure()
        val files = withContext(io) {
            mapOf(
                Kind.MOODLE_FILES to moodleBytes,
                Kind.MAIL_ATTACHMENTS to settledAttachments().sumOf(::bytes),
                Kind.CLASS_TABLE_IMAGES to settled(classTableImages()).sumOf(::bytes),
            )
        }
        return files + mapOf(
            Kind.CACHED_MAIL to cachedMail.bytes(),
            Kind.DOWNLOADED_MAIL to downloadedMail.bytes(),
            Kind.WEBSITE_DATA to websiteData.bytes(),
        )
    }

    /**
     * Clears [kind], then measures. Moodle's downloads on their way stop first, as a sign-out
     * stops them; each file downloads again when it is next opened.
     */
    suspend fun clear(kind: Kind) {
        busy.withLock {
            remove(kind)
            _sizes.value = measure()
        }
    }

    /** Clears every kind the app downloaded ([Kind.downloaded]), not the mail or the sites' data. */
    suspend fun clearDownloaded() {
        busy.withLock {
            Kind.entries.filter { it.downloaded }.forEach { remove(it) }
            _sizes.value = measure()
        }
    }

    private suspend fun remove(kind: Kind) {
        when (kind) {
            Kind.MOODLE_FILES -> moodle.clear()
            Kind.MAIL_ATTACHMENTS -> withContext(io) { settledAttachments().forEach(::delete) }
            Kind.CLASS_TABLE_IMAGES -> withContext(io) { settled(classTableImages()).forEach(::delete) }
            Kind.CACHED_MAIL -> cachedMail.clear()
            Kind.DOWNLOADED_MAIL -> downloadedMail.clear()
            Kind.WEBSITE_DATA -> websiteData.clear()
        }
    }

    /**
     * The attachments' folders, but not one a download is still writing into, however long it has
     * waited, nor one a send is staging, which it still has to read.
     */
    private fun settledAttachments(): List<File> {
        val inUse = attachmentsInUse()
        return settled(mailAttachments()).filterNot { it.name in inUse || it.name.startsWith(MailCache.OUTGOING_PREFIX) }
    }

    /** The entries of [dir] nothing was written to in the last [IN_USE_MILLIS]. */
    private fun settled(dir: File): List<File> {
        val cutoff = now() - IN_USE_MILLIS
        return dir.listFiles().orEmpty().filter { lastWritten(it) < cutoff }
    }

    companion object {
        /** Under cacheDir, where the class table export writes its images. */
        const val CLASS_TABLE_EXPORT_DIR = "class_table_export"

        /** How long a file just written or handed to another app is left alone. As iOS's export sweep. */
        const val IN_USE_MILLIS = 10 * 60 * 1000L

        /** School Mail's cached folder pages and mails ([MailCache]), not its attachments. */
        internal fun cachedMailStore(cache: MailCache, io: CoroutineDispatcher): Store = Store(
            bytes = { withContext(io) { cache.messagesBytes() } },
            clear = { withContext(io) { cache.clearMessages() } },
        )

        /**
         * What the app's WebViews keep: their profile under [dataDir] (cookies and site storage)
         * and their pages' cache under [cacheDir]. [clear] is the browser's own
         * ([BrowserDataCleaner.clearWebsiteData]), which closes its screens first and leaves its
         * downloads and each site's zoom and desktop choice.
         */
        internal fun websiteDataStore(dataDir: File, cacheDir: File, io: CoroutineDispatcher, clear: suspend () -> Unit): Store = Store(
            bytes = { withContext(io) { bytes(File(dataDir, WEBVIEW_PROFILE_DIR)) + bytes(File(cacheDir, WEBVIEW_CACHE_DIR)) } },
            clear = clear,
        )

        /** Where a WebView keeps its profile under the app's data folder, and its cache under the cache folder. */
        private const val WEBVIEW_PROFILE_DIR = "app_webview"
        private const val WEBVIEW_CACHE_DIR = "WebView"

        /** Walks [entry] without following links: a link is an entry of its own, not what it points to. */
        private fun walk(entry: File, visit: (Path, BasicFileAttributes) -> Unit, leave: (Path) -> Unit = {}) {
            if (!Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(entry.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    visit(file, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    visit(dir, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    leave(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }

        /** When anything in [entry] was last written, a folder's own entries included. */
        private fun lastWritten(entry: File): Long {
            var latest = 0L
            walk(entry, { _, attrs -> latest = maxOf(latest, attrs.lastModifiedTime().toMillis()) })
            return latest
        }

        private fun bytes(entry: File): Long {
            var total = 0L
            walk(entry, { _, attrs -> if (attrs.isRegularFile) total += attrs.size() })
            return total
        }

        /** Deletes [entry], files and links alike, and each folder once it is empty. */
        private fun delete(entry: File) {
            walk(
                entry,
                visit = { path, attrs -> if (!attrs.isDirectory) runCatching { Files.delete(path) } },
                leave = { dir -> runCatching { Files.delete(dir) } },
            )
        }
    }
}
