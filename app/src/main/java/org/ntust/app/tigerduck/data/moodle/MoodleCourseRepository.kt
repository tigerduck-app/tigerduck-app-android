package org.ntust.app.tigerduck.data.moodle

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.moodle.LiveMoodleCourseApi
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import java.io.File
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [MoodleCourseStore] of each course the signed-in account opens, shared by every Moodle
 * screen of that course for as long as the app runs, so a screen pushed from another finds what
 * that one fetched. A sign-out drops them all; the next account builds its own, over its own
 * cache folder. The demo account's stores answer from the bundled file, in the language the app
 * shows, and keep their copies in memory only, so a change of language reaches them.
 */
@Singleton
class MoodleCourseRepository internal constructor(
    private val signedIn: () -> Boolean,
    /** The account the stores are for; null with none. */
    private val account: () -> String?,
    private val isDemo: () -> Boolean,
    private val liveApi: MoodleCourseApi,
    private val demoApi: () -> MoodleCourseApi?,
    private val idMap: suspend () -> Map<String, Int>,
    private val cachePort: (account: String) -> MoodleCachePort,
    private val picturesDir: (account: String) -> File?,
    /** The files downloaded on the device; null downloads nothing. */
    private val downloads: MoodleFileDownloads?,
    /** The account's folder of downloaded files, found off the main thread: it reads a salt from disk. */
    private val downloadsDir: (account: String) -> File?,
    private val decode: (ByteArray) -> ImageBitmap?,
    authState: Flow<Boolean>,
    appScope: CoroutineScope,
    private val newScope: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
    /** Fires when the language the app shows has changed, which the demo account's Moodle follows. */
    languageChanges: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    /** The records of the account's last assignment round, read again after each round. */
    private val assignmentRecords: () -> Flow<List<MoodleAssignmentRecord>> = { kotlinx.coroutines.flow.emptyFlow() },
    /** Home's assignment rows, read again after each write. */
    private val listedAssignments: () -> Flow<List<Assignment>> = { kotlinx.coroutines.flow.emptyFlow() },
    /** Where a page's submission status goes, for the cached list; see [AssignmentStatusWriter]. */
    private val takeStatus: suspend (SubmissionStatusAnswer) -> Unit = {},
    /** The cache's generation now, which a page's request goes out under. */
    private val cacheGeneration: () -> Int = { 0 },
    private val monotonicNowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    @Inject
    constructor(
        live: LiveMoodleCourseApi,
        demoAccount: DemoAccount,
        dataCache: DataCache,
        downloads: MoodleFileDownloads,
        authService: AuthService,
        uiLanguage: UiLanguageMonitor,
        statusWriter: AssignmentStatusWriter,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(
        signedIn = { authService.authState.value },
        // A demo session can present as signed in with no stored ID; its cache needs a folder all
        // the same.
        account = {
            authService.storedStudentId?.takeIf { it.isNotBlank() }
                ?: if (demoAccount.isActive) DEMO_ACCOUNT else null
        },
        isDemo = { demoAccount.isActive },
        liveApi = live,
        demoApi = demoAccount::moodleApi,
        idMap = dataCache::loadMoodleCourseIds,
        cachePort = { account -> DataCachePort(dataCache, account) },
        picturesDir = { account -> File(dataCache.moodleFilesDir(account), "pictures") },
        downloads = downloads,
        downloadsDir = dataCache::moodleDownloadsDir,
        decode = ::decodeBitmap,
        authState = authService.authState,
        appScope = appScope,
        languageChanges = uiLanguage.changes,
        assignmentRecords = { dataCache.assignmentRecordsVersion.map { dataCache.loadAssignmentRecords() } },
        listedAssignments = { dataCache.assignmentsVersion.map { dataCache.loadAssignments() } },
        takeStatus = { answer -> statusWriter.take(answer) },
        cacheGeneration = { dataCache.userDataGeneration },
    )

    private val lock = Mutex()
    private var stores = mutableMapOf<Pair<String, Int>, MoodleCourseStore>()
    private var assignments = mutableMapOf<String, MoodleAccountAssignments>()
    private var contexts = mutableMapOf<String, MoodleCacheContext>()
    private var scope = newScope()
    private var demo: SwitchableApi? = null
    /** The demo account's copies, held for the session; never written to the device. */
    private var demoCache = MemoryCachePort()

    /**
     * Bumped at every sign-out. A store holds the one it was built in, so one left on a page
     * further down the back stack is not current after signing back in, even as the same account:
     * its scope was cancelled with the sign-out.
     */
    @Volatile
    private var generation = 0

    init {
        appScope.launch {
            authState.collect { signedIn -> if (!signedIn) clear() }
        }
        appScope.launch {
            languageChanges.collect { reloadDemo() }
        }
    }

    /**
     * The store for Moodle course [courseId], built on first use; null when signed out. [courseNo]
     * is the class table's course number, which the summary is also checked against.
     */
    suspend fun store(courseId: Int, courseNo: String? = null): MoodleCourseStore? = lock.withLock {
        if (!signedIn()) return@withLock null
        val account = account() ?: return@withLock null
        stores[account to courseId]?.let { return@withLock it }
        val api = apiLocked() ?: return@withLock null
        val ids = idMap()
        val filesDir = downloads?.let { withContext(Dispatchers.IO) { downloadsDir(account) } }
            ?.let { File(it, courseId.toString()) }
        MoodleCourseStore(
            courseId = courseId,
            termCourseIds = MoodleCourseStore.termCourseIds(courseId, ids),
            courseCodes = MoodleCourseStore.courseCodes(courseId, courseNo, ids),
            term = MoodleCourseStore.term(courseId, ids),
            api = api,
            context = contextLocked(account),
            scope = scope,
            picturesDir = picturesDir(account),
            downloads = downloads,
            filesDir = filesDir,
            decodePicture = decode,
            assignmentRecords = assignmentsLocked(account, api).records.map { it.orEmpty() },
        ).also { stores[account to courseId] = it }
    }

    /**
     * The account's assignments, for their pages and the courses' lists, built on first use;
     * null when signed out.
     */
    suspend fun assignments(): MoodleAccountAssignments? = lock.withLock {
        if (!signedIn()) return@withLock null
        val account = account() ?: return@withLock null
        assignmentsLocked(account, apiLocked() ?: return@withLock null)
    }

    private fun assignmentsLocked(account: String, api: MoodleCourseApi): MoodleAccountAssignments =
        assignments.getOrPut(account) {
            MoodleAccountAssignments(
                api = api,
                context = contextLocked(account),
                scope = scope,
                records = assignmentRecords(),
                listed = listedAssignments(),
                takeStatus = takeStatus,
                generation = cacheGeneration,
                monotonicNowMs = monotonicNowMs,
            )
        }

    /** The Moodle the account's stores ask: the demo account's own, else the live site; null when the demo has none. */
    private fun apiLocked(): MoodleCourseApi? = if (isDemo()) {
        demo ?: demoApi()?.let(::SwitchableApi)?.also { demo = it }
    } else {
        liveApi
    }

    /** One per account and sign-in, shared by its stores and its assignments. */
    private fun contextLocked(account: String): MoodleCacheContext = contexts.getOrPut(account) {
        val built = generation
        MoodleCacheContext(
            account = account,
            cache = if (isDemo()) demoCache else cachePort(account),
            isCurrent = { generation == built && signedIn() && account() == account },
        )
    }

    /**
     * Drops every store and stops what they were fetching and downloading, and deletes the
     * downloaded files once the downloads have wound down, so none of the account's is left to
     * the next; the cache wipe deletes the rest.
     */
    private suspend fun clear() = lock.withLock {
        generation++
        downloads?.clear()
        scope.cancel()
        scope = newScope()
        stores = mutableMapOf()
        assignments = mutableMapOf()
        contexts = mutableMapOf()
        demo = null
        demoCache = MemoryCachePort()
    }

    /**
     * Answers the demo account's stores, those built already included, from the bundled file in
     * the language the app shows now, and fetches what they show again in it.
     */
    private suspend fun reloadDemo() = lock.withLock {
        val switchable = demo ?: return@withLock
        if (!isDemo()) return@withLock
        switchable.api = demoApi() ?: return@withLock
        stores.values.forEach { it.reloadAll() }
    }

    /** The demo account's Moodle, which a change of language swaps under the stores built on it. */
    private class SwitchableApi(@Volatile var api: MoodleCourseApi) : MoodleCourseApi {
        override suspend fun functions() = api.functions()
        override suspend fun forums(courseIds: List<Int>) = api.forums(courseIds)
        override suspend fun discussions(forumId: Int, page: Int) = api.discussions(forumId, page)
        override suspend fun posts(discussionId: Int) = api.posts(discussionId)
        override suspend fun materials(courseId: Int) = api.materials(courseId)
        override suspend fun markMaterialViewed(material: MoodleMaterial, isCurrent: () -> Boolean) =
            api.markMaterialViewed(material, isCurrent)
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = api.markViewed(discussionId, isCurrent)
        override suspend fun members(courseId: Int) = api.members(courseId)
        override suspend fun picture(url: String) = api.picture(url)
        override suspend fun gradeItems(courseId: Int) = api.gradeItems(courseId)
        override suspend fun summary(courseId: Int) = api.summary(courseId)
        override suspend fun submission(assignId: Int) = api.submission(assignId)
        override suspend fun comments(cmid: Int, submissionId: Int) = api.comments(cmid, submissionId)
        override suspend fun groups(courseId: Int) = api.groups(courseId)
        override suspend fun download(file: MoodleFile, target: File, maxBytes: Long, progress: (Float) -> Unit) =
            api.download(file, target, maxBytes, progress)
    }

    private class MemoryCachePort : MoodleCachePort {
        private val copies = java.util.concurrent.ConcurrentHashMap<String, Pair<Any, Long>>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = copies[name] as Pair<T, Long>?

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) {
            copies[name] = value to fetchedAtMs
        }
    }

    private class DataCachePort(private val dataCache: DataCache, private val account: String) : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? =
            dataCache.loadMoodleData<T>(type, name, account)?.let { it.value to it.fetchedAtMs }

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) =
            dataCache.saveMoodleData(value, fetchedAtMs, name, account)
    }

    private companion object {
        const val DEMO_ACCOUNT = "demo"

        fun decodeBitmap(bytes: ByteArray): ImageBitmap? =
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
    }
}
