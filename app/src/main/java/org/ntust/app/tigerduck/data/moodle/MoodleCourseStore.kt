package org.ntust.app.tigerduck.data.moodle

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.moodle.MoodleFileUrls
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionThread
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import org.ntust.app.tigerduck.util.logSummary
import java.io.File

/**
 * A course's data on Moodle, for the course detail's Moodle section and the screens it opens.
 * Each kind is a [MoodleFeed], cached on the device for the signed-in account. One store per
 * account and course, shared by every screen of that course; see [MoodleCourseRepository].
 * Ported from iOS `MoodleCourseStore`.
 */
class MoodleCourseStore(
    val courseId: Int,
    termCourseIds: List<Int>,
    private val courseCodes: List<String>,
    private val api: MoodleCourseApi,
    private val context: MoodleCacheContext,
    private val scope: CoroutineScope,
    /** Where member pictures are kept; null keeps them in memory only. */
    private val picturesDir: File? = null,
    /**
     * Where the pictures a member's page opens full screen are kept, under the account's
     * downloads, which the system's viewers may read; null opens none. See [fullPicture].
     */
    private val openedPicturesDir: File? = null,
    /** The files downloaded on the device, every course's; null downloads nothing. */
    val downloads: MoodleFileDownloads? = null,
    /** Where this course's downloaded files are kept for the account; null downloads nothing. */
    private val filesDir: File? = null,
    private val decodePicture: (ByteArray) -> ImageBitmap? = { null },
    /**
     * Told when the account's picture has changed here, for the account's other courses, whose
     * members show it too; see [changePicture].
     */
    private val onOwnPictureChanged: () -> Unit = {},
    /** Where pictures are decoded, off the main thread. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    /** The term the course is of, such as "1151", which its forums copy is kept under; see [term]. */
    term: String? = null,
    /** The records of the account's last assignment round, which the section's Assignments row counts. */
    assignmentRecords: Flow<List<MoodleAssignmentRecord>> = kotlinx.coroutines.flow.flowOf(emptyList()),
) {
    /** Every course of the term, which one forums call covers. */
    val termCourseIds: List<Int> = (termCourseIds + courseId).distinct().sorted()

    val account: String get() = context.account

    fun isCurrent(): Boolean = context.isCurrent()

    /**
     * Kept per term: every course of a term shares one copy, and a course of another term, opened
     * after, does not replace it.
     */
    val forums = feed<MoodleTermForums>(
        term?.let { "terms/$it/forums" } ?: "courses/$courseId/forums",
        MoodleRefreshInterval.FORUMS_MS,
    )
    val materials = feed<MoodleCourseMaterials>("courses/$courseId/materials", MoodleRefreshInterval.MATERIALS_MS)
    val grades = feed<MoodleCourseGrades>("courses/$courseId/grades", MoodleRefreshInterval.GRADES_MS)
    val members = feed<MoodleMemberList>("courses/$courseId/members", MoodleRefreshInterval.MEMBERS_MS)
    val summary = feed<MoodleCourseSummary>("courses/$courseId/summary", MoodleRefreshInterval.SUMMARY_MS)

    private inline fun <reified T : Any> feed(name: String, ttlMs: Long) =
        MoodleFeed<T>(name, object : TypeToken<T>() {}.type, ttlMs, context, scope)

    /** Returns once every cached copy is read, so a screen's first refresh knows their age. */
    suspend fun awaitCache() {
        forums.awaitCache()
        materials.awaitCache()
        grades.awaitCache()
        members.awaitCache()
        summary.awaitCache()
    }

    // --- Section ---

    /** Every assignment of the course the last round recorded, which its row counts. */
    val assignmentCount: StateFlow<Int> = assignmentRecords
        .map { records -> records.count { it.courseId == courseId } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** The section's rows, in order; a kind with nothing to count has none. */
    val sectionRows: StateFlow<List<MoodleSectionRow>> =
        combine(forums.state, materials.state, grades.state, members.state, assignmentCount) { f, mat, g, m, assignments ->
            MoodleSections.rows(f.value, courseId, g.value, m.value, mat.value, assignmentCount = assignments)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Why the section's last fetch failed, shown with Retry beside what it has, or in its place. */
    val sectionFailure: StateFlow<Throwable?> =
        combine(forums.state, materials.state, grades.state, members.state, summary.state) { f, mat, g, m, s ->
            MoodleSections.failure(listOf(f, mat, g, m, s))
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Fetches every kind the section shows, side by side, as its Retry does. */
    fun retrySection() {
        scope.launch { refreshForums() }
        scope.launch { refreshMaterials() }
        scope.launch { refreshGrades() }
        scope.launch { refreshMembers() }
        scope.launch { refreshSummary() }
    }

    /** Fetches every kind again, the discussions and threads opened included, as for another language. */
    fun reloadAll() {
        retrySection()
        synchronized(this) { discussionPages.values.toList() }.forEach { pages -> scope.launch { pages.refresh() } }
        synchronized(this) { threads.keys.toList() }.forEach { id -> scope.launch { refreshThread(id) } }
    }

    /** What the foot of the section shows; null while the course has no summary worth showing. */
    val shownSummary: StateFlow<MoodleCourseSummary?> = summary.state
        .map { state -> state.value?.takeIf { MoodleSections.isWorthShowing(it, courseCodes) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // --- Forums ---

    /** When this course's forums were fetched; null while the copy does not cover the course. */
    val forumsFetchedAtMs: Long?
        get() = forums.state.value.takeIf { it.value?.courseIds?.contains(courseId) == true }?.fetchedAtMs

    val newsForum: StateFlow<MoodleForum?> = forums.state
        .map { MoodleSections.newsForum(it.value, courseId) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The course's other forums that have something in them, as the forums screen lists them. */
    val generalForums: StateFlow<List<MoodleForum>> = forums.state
        .map { MoodleSections.generalForums(it.value, courseId) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun currentNewsForum(): MoodleForum? = MoodleSections.newsForum(forums.value, courseId)

    /** The course's forum [forumId], news forum included, while the cached copy has it. */
    fun forum(forumId: Int): MoodleForum? =
        MoodleSections.courseForums(forums.value, courseId).firstOrNull { it.id == forumId }

    /**
     * One call for every course of the term. A site that does not allow the forum calls answers
     * as if no course had a forum, which hides the announcements and the forums; one that does not
     * allow the posts call keeps only the announcements, as a discussion would show no reply.
     */
    suspend fun refreshForums() {
        val ids = termCourseIds
        forums.refresh {
            val functions = api.functions()
            if (!functions.containsAll(FORUM_FUNCTIONS)) return@refresh MoodleTermForums(ids, emptyList())
            val all = api.forums(ids)
            val shown = if (MoodleCourseApi.DISCUSSION_POSTS_FUNCTION in functions) all else all.filter { it.isNews }
            MoodleTermForums(ids, shown)
        }
    }

    // --- Discussions ---

    private val discussionPages = mutableMapOf<Int, MoodleDiscussionPages>()

    /** Keep announcement cache paths stable so upgrading does not discard their offline copies. */
    @Synchronized
    fun discussions(forum: MoodleForum): MoodleDiscussionPages = discussionPages.getOrPut(forum.id) {
        val feed = if (forum.isNews) {
            MoodleFeed<MoodleDiscussionList>(
                "courses/$courseId/announcements",
                object : TypeToken<MoodleDiscussionList>() {}.type,
                MoodleRefreshInterval.ANNOUNCEMENTS_MS,
                context,
                scope,
            )
        } else {
            MoodleFeed(
                "courses/$courseId/forums/${forum.id}",
                object : TypeToken<MoodleDiscussionList>() {}.type,
                MoodleRefreshInterval.FORUMS_MS,
                context,
                scope,
            )
        }
        MoodleDiscussionPages(forum.id, feed, byModified = !forum.isNews, forums, api, scope)
    }

    /** The announcements, once the course's news forum is known. */
    fun announcementPages(): MoodleDiscussionPages? = currentNewsForum()?.let(::discussions)

    /** An announcement opened with nothing cached; see [ensureDiscussion]. */
    suspend fun ensureAnnouncement(discussionId: Int): MoodleLookup = ensureDiscussion(discussionId, forumId = null)

    /**
     * What a discussion opened with nothing cached needs, as after the system ended the app: the
     * course's forums, then forum [forumId]'s discussions (the news forum's for null), each
     * fetched only when the cache lacks it, and further pages while the discussion is older than
     * those listed.
     */
    suspend fun ensureDiscussion(discussionId: Int, forumId: Int?): MoodleLookup {
        awaitCache()
        fun target(): MoodleForum? = if (forumId == null) currentNewsForum() else forum(forumId)
        if (target() == null) refreshForums()
        val pages = target()?.let(::discussions)
            ?: return forums.state.value.failure?.let(MoodleLookup::Failed) ?: MoodleLookup.Missing
        pages.feed.awaitCache()
        fun listed(): Boolean = pages.feed.value?.takeIf { it.forumId == pages.forumId }
            ?.discussions.orEmpty().any { it.id == discussionId }
        if (listed()) return MoodleLookup.Found
        pages.refresh()
        if (!listed()) pages.feed.state.value.failure?.let { return MoodleLookup.Failed(it) }
        var loaded = 0
        while (!listed() && loaded++ < MAX_LOOKUP_PAGES) {
            val list = pages.feed.value?.takeIf { it.forumId == pages.forumId && it.hasMore } ?: break
            pages.loadMore(MoodleDiscussions.sorted(list, pages.byModified).lastOrNull()?.id)
            pages.pageFailure.value?.let { return MoodleLookup.Failed(it) }
        }
        return if (listed()) MoodleLookup.Found else MoodleLookup.Missing
    }

    /** What a forum opened with nothing cached needs: the course's forums, when the cache lacks it. */
    suspend fun ensureForum(forumId: Int): MoodleLookup {
        awaitCache()
        if (forum(forumId) != null) return MoodleLookup.Found
        refreshForums()
        if (forum(forumId) != null) return MoodleLookup.Found
        return forums.state.value.failure?.let(MoodleLookup::Failed) ?: MoodleLookup.Missing
    }

    /**
     * A discussion opened on screen: its unread mark goes here, on the section row and on its
     * thread's posts at once, and Moodle logs the view, which marks it read there. A list load
     * never marks anything read. The view event is best effort and not retried.
     */
    fun open(discussion: MoodleDiscussion, forum: MoodleForum? = currentNewsForum()) {
        if (!context.isCurrent()) return
        forum?.let { discussions(it).markRead(discussion.id) }
        synchronized(this) { threads[discussion.id] }?.let { thread ->
            if (thread.value?.posts?.any { it.isUnread } == true) {
                thread.update { value -> value.copy(posts = value.posts.map { it.copy(isUnread = false) }) }
            }
        }
        scope.launch {
            try {
                if (!context.isCurrent()) return@launch
                if (MoodleCourseApi.VIEW_DISCUSSION_FUNCTION !in api.functions()) return@launch
                if (!context.isCurrent()) return@launch
                api.markViewed(discussion.id) { context.isCurrent() }
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "discussion view not logged: ${e.logSummary()}")
            }
        }
    }

    // --- Threads ---

    private val threads = mutableMapOf<Int, MoodleFeed<MoodleDiscussionThread>>()

    /** Discussion [discussionId]'s posts, cached with the course's other Moodle data. */
    @Synchronized
    fun thread(discussionId: Int): MoodleFeed<MoodleDiscussionThread> = threads.getOrPut(discussionId) {
        MoodleFeed(
            "courses/$courseId/discussions/$discussionId",
            object : TypeToken<MoodleDiscussionThread>() {}.type,
            MoodleRefreshInterval.FORUMS_MS,
            context,
            scope,
        )
    }

    /**
     * A site that does not allow the posts call answers with none, which leaves the first post as
     * the discussion's list brought it.
     */
    suspend fun refreshThread(discussionId: Int) {
        thread(discussionId).refresh {
            if (MoodleCourseApi.DISCUSSION_POSTS_FUNCTION !in api.functions()) {
                return@refresh MoodleDiscussionThread(discussionId, emptyList())
            }
            MoodleDiscussionThread(discussionId, api.posts(discussionId))
        }
    }

    /**
     * Every opening fetches the posts, as the list may not know of a reply yet, and keeps which
     * were unread for their dots. Moodle marks the whole discussion read on the view event, so it
     * goes only once the posts load: an opening whose fetch fails sends nothing and returns null,
     * leaving the replies unread for the next opening.
     */
    suspend fun openThread(discussion: MoodleDiscussion, forum: MoodleForum): Set<Int>? {
        if (!context.isCurrent()) return null
        val feed = thread(discussion.id)
        refreshThread(discussion.id)
        val state = feed.state.value
        if (!context.isCurrent() || state.failure != null) return null
        val posts = state.value?.posts ?: return null
        val unread = posts.filter { it.isUnread }.map { it.id }.toSet()
        open(discussion, forum)
        return unread
    }

    // --- Materials ---

    /**
     * A site that does not allow the call answers as if the course had no materials, which hides
     * their row. Copies of files Moodle has a newer version of go once the answer is in.
     */
    suspend fun refreshMaterials() {
        materials.refresh {
            if (MoodleCourseApi.CONTENTS_FUNCTION !in api.functions()) return@refresh MoodleCourseMaterials(emptyList())
            api.materials(courseId)
        }
        val state = materials.state.value
        val fresh = state.value ?: return
        if (state.failure != null) return
        val downloads = downloads ?: return
        downloads.removeOutdated(MoodleMaterials.fileRefs(fresh).mapNotNull(::location))
    }

    /**
     * A file or link opened on screen, which Moodle logs as a view of its module when the site
     * allows the call. A failed call is not retried and shows nothing.
     */
    fun open(material: MoodleMaterial) {
        val function = MoodleMaterials.VIEW_CALLS[material.module]?.first ?: return
        scope.launch {
            try {
                if (!context.isCurrent() || function !in api.functions()) return@launch
                // A screen can outlive a sign-out, and the next account's token would log the
                // view as theirs.
                api.markMaterialViewed(material) { context.isCurrent() }
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "material view not logged: ${e.logSummary()}")
            }
        }
    }

    // --- Grades ---

    /** A site that does not allow the call answers as if the course had none, which hides the row. */
    suspend fun refreshGrades() {
        grades.refresh {
            if (MoodleCourseApi.GRADE_ITEMS_FUNCTION !in api.functions()) return@refresh MoodleCourseGrades(emptyList())
            MoodleCourseGrades(api.gradeItems(courseId))
        }
    }

    // --- Members ---

    /** The member a post's author is, while the cached members have them. */
    fun member(id: Int?): MoodleMember? {
        id ?: return null
        return members.value?.members?.firstOrNull { it.id == id }
    }

    /** What a member's page opened with nothing cached needs: the members, when the cache lacks them. */
    suspend fun ensureMember(memberId: Int): MoodleLookup {
        awaitCache()
        if (member(memberId) != null) return MoodleLookup.Found
        refreshMembers()
        if (member(memberId) != null) return MoodleLookup.Found
        return members.state.value.failure?.let(MoodleLookup::Failed) ?: MoodleLookup.Missing
    }

    /**
     * The whole list in one call. A site that does not allow the call, or a course that keeps its
     * participants from the account, answers as if the course had no members, which hides their
     * row for good rather than failing on every refresh.
     */
    suspend fun refreshMembers(joinRunning: Boolean = true) {
        members.refresh(joinRunning) {
            if (MoodleCourseApi.MEMBERS_FUNCTION !in api.functions()) return@refresh MoodleMemberList(null, emptyList())
            try {
                api.members(courseId)
            } catch (e: org.ntust.app.tigerduck.network.MoodleWebserviceError.MalformedResponse) {
                if (e.detail == MoodleCourseApi.MEMBERS_HIDDEN_CODE) MoodleMemberList(null, emptyList()) else throw e
            }
        }
    }

    // --- The account's own picture ---

    /** Whether the site lets the account change its profile picture (#446), as the iPhone asks. */
    suspend fun canChangePicture(): Boolean = try {
        MoodleCourseApi.UPDATE_PICTURE_FUNCTION in api.functions()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private val _changingPicture = MutableStateFlow(false)

    /** True from a picture change's upload until the members show its result; see [changePicture]. */
    val changingPicture: StateFlow<Boolean> = _changingPicture.asStateFlow()

    /**
     * Makes the JPEG [encode] makes the account's profile picture, then fetches the members again,
     * so the list and the account's page show it, and has the account's other courses fetch
     * theirs ([onOwnPictureChanged]). Null once Moodle took it, else what Moodle refused it with;
     * the old picture shows meanwhile, and stays after a refusal.
     *
     * In the account's scope, not the caller's, the encoding too: a page left meanwhile does not
     * stop a change confirmed there, or one Moodle may already have made, from reaching the
     * members, and a sign-out ends it. The fetch after it is a new one, never one already running,
     * which would bring back the old picture.
     */
    suspend fun changePicture(encode: () -> ByteArray): Throwable? = scope.async {
        _changingPicture.value = true
        try {
            val jpeg = withContext(background) { encode() }
            api.changePicture(jpeg)
            onOwnPictureChanged()
            refreshMembers(joinRunning = false)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "profile picture not changed: ${e.logSummary()}")
            e
        } finally {
            _changingPicture.value = false
        }
    }.await()

    // --- Summary ---

    /** A site that does not allow the call answers as if the course had no summary. */
    suspend fun refreshSummary() {
        summary.refresh {
            if (MoodleCourseApi.COURSES_FUNCTION !in api.functions()) return@refresh MoodleCourseSummary.EMPTY
            api.summary(courseId)
        }
    }

    // --- Files ---

    init {
        // So the rows show the copies already downloaded, from their first frame on.
        val dir = filesDir
        if (dir != null && downloads != null) scope.launch { downloads.index(dir) }
    }

    /**
     * Where [ref]'s copy is kept on the device; null for a file that is not downloaded into the
     * app: one off the Moodle site, which no key may be sent to, or one too large.
     */
    fun location(ref: MoodleFileRef): File? {
        val dir = filesDir ?: return null
        val downloads = downloads ?: return null
        if (!MoodleFileUrls.isMoodleFile(ref.file.url) || ref.file.size > MAX_DOWNLOAD_BYTES) return null
        return downloads.location(dir, ref.file, ref.owner)
    }

    /** [ref]'s state in [snapshot], which a screen collects from [downloads]. */
    fun downloadState(ref: MoodleFileRef, snapshot: MoodleFileDownloads.Snapshot): MoodleDownloadState =
        downloads?.state(location(ref), snapshot) ?: MoodleDownloadState.NotDownloaded

    /**
     * [ref] ready to open: its copy on the device, downloaded first when there is none, or, for a
     * file off the Moodle site or one too large to download here, a link with no key in it. A
     * download stops past the size Moodle lists, goes on after the screen that asked closes, and
     * is joined by a second tap while it runs; one stopped, or landing after a sign-out, leaves
     * nothing to show.
     */
    suspend fun download(ref: MoodleFileRef): MoodleAttachment {
        val file = ref.file
        if (!MoodleFileUrls.isMoodleFile(file.url)) return MoodleAttachment.Link(file.url)
        if (file.size > MAX_DOWNLOAD_BYTES) return MoodleAttachment.Link(MoodleFileUrls.webUrl(file.url) ?: file.url)
        val downloads = downloads
        val location = location(ref)
        if (downloads == null || location == null) {
            return MoodleAttachment.Failed(IllegalStateException("no folder for files"))
        }
        val copy = downloads.download(
            location,
            fetch = { target, progress ->
                api.download(file, target, maxBytes = if (file.size > 0) file.size else MAX_DOWNLOAD_BYTES, progress)
            },
            isCurrent = context.isCurrent,
        )
        if (copy != null) return MoodleAttachment.Local(copy)
        val state = downloads.state(location)
        return (state as? MoodleDownloadState.Failed)?.let { MoodleAttachment.Failed(it.error) } ?: MoodleAttachment.Dropped
    }

    /** Stops [ref]'s download, as a tap on its progress ring does. */
    fun cancelDownload(ref: MoodleFileRef) {
        location(ref)?.let { downloads?.cancel(it) }
    }

    // --- Pictures ---

    private val _pictures = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Members' own pictures by URL, decoded once per store. */
    val pictures: StateFlow<Map<String, ImageBitmap>> = _pictures.asStateFlow()

    /** One download at a time, in the order rows ask, so the pictures on screen come first. */
    private val pictureTurn = Mutex()

    /**
     * A member's own picture, from the device, else from Moodle once and then kept with the
     * account's other Moodle files. One who keeps Moodle's default picture, or whose download
     * fails or lands after a sign-out, keeps their initial.
     */
    suspend fun loadPicture(member: MoodleMember) {
        val url = member.pictureUrl ?: return
        if (url in _pictures.value) return
        val file = pictureFile(member.id, url)
        val local = file?.let { f ->
            withContext(Dispatchers.IO) { runCatching { if (f.exists()) f.readBytes() else null }.getOrNull() }
        }
        local?.let { bytes -> withContext(background) { decodePicture(bytes) } }?.let { image ->
            _pictures.update { it + (url to image) }
            return
        }
        val bytes = pictureTurn.withLock {
            if (url in _pictures.value) return
            try {
                api.picture(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "member picture not downloaded: ${e.logSummary()}")
                null
            }
        } ?: return
        val image = withContext(background) { decodePicture(bytes) } ?: return
        if (!context.isCurrent()) return
        file?.let { savePicture(bytes, it) }
        _pictures.update { it + (url to image) }
    }

    /** One file per member and picture revision, which a new upload changes. */
    private fun pictureFile(memberId: Int, url: String): File? {
        val dir = picturesDir ?: return null
        return File(dir, "$memberId-${pictureRevision(url)}")
    }

    private fun pictureRevision(url: String): String =
        Regex("[?&]rev=([^&]+)").find(url)?.groupValues?.get(1)?.filter { it.isLetterOrDigit() }.orEmpty()

    private val openingLock = Any()
    private val _openingPictures = MutableStateFlow<Set<Int>>(emptySet())

    /** The members whose picture is getting ready to open full screen; see [fullPicture]. */
    val openingPictures: StateFlow<Set<Int>> = _openingPictures.asStateFlow()

    /**
     * [member]'s own picture as a file a viewer can open full screen, as the iPhone opens it
     * (tigerduck-app/tigerduck-app#741): Moodle's 512 px size, downloaded on the first open and
     * kept for the account; the 100 px one the page shows when that download fails, and the next
     * open tries again. Named after the member, whose name the viewer shows. Null for Moodle's
     * default picture or none, while an open of the member is already getting ready
     * ([openingPictures]), or after a sign-out meanwhile.
     */
    suspend fun fullPicture(member: MoodleMember): MoodleOpenedPicture? {
        val url = member.pictureUrl ?: return null
        val dir = openedPicturesDir ?: return null
        val claimed = synchronized(openingLock) {
            (member.id !in _openingPictures.value).also { if (it) _openingPictures.value += member.id }
        }
        if (!claimed) return null
        try {
            val folder = "${member.id}-${pictureRevision(url)}"
            withContext(Dispatchers.IO) { keptOpenedPicture(File(dir, folder)) }?.let { kept ->
                return kept.takeIf { context.isCurrent() }
            }
            val large = try {
                api.picture(largePictureUrl(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "member picture's large size not downloaded: ${e.logSummary()}")
                null
            }
            if (!context.isCurrent()) return null
            return if (large != null) {
                keepOpenedPicture(dir, folder, member.name, large, replacing = "${member.id}-")
            } else {
                // Apart from the large size's folder, so the next open tries the download again.
                val shown = pictureFile(member.id, url)?.let { file ->
                    withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
                } ?: return null
                keepOpenedPicture(dir, "$folder$SHOWN_SIZE_SUFFIX", member.name, shown, replacing = null)
            }
        } finally {
            synchronized(openingLock) { _openingPictures.value -= member.id }
        }
    }

    /** The picture kept in [folder], its one whole file: never one a write left cut short. */
    private fun keptOpenedPicture(folder: File): MoodleOpenedPicture? {
        val file = folder.listFiles()?.singleOrNull { it.isFile && !it.name.startsWith(".") } ?: return null
        return MoodleOpenedPicture(file, MoodlePictureType.ofExtension(file.extension).mimeType)
    }

    /**
     * Writes [bytes] as [name]'s picture in [folder], dropping the member's other copies, named
     * [replacing]; null when it could not, or when a sign-out began meanwhile. One blocking step,
     * the sign-out's question last: a cancellation, as the sign-out ends the screen, cannot come
     * between the write and the question. A sign-out says so before its wipe starts, so a copy
     * written as it began goes here, and one written before is the wipe's to delete.
     */
    private suspend fun keepOpenedPicture(
        dir: File,
        folder: String,
        name: String,
        bytes: ByteArray,
        replacing: String?,
    ): MoodleOpenedPicture? = withContext(Dispatchers.IO) {
        runCatching {
            val type = MoodlePictureType.of(bytes)
            if (replacing != null) {
                dir.listFiles { file -> file.name.startsWith(replacing) && file.name != folder }
                    ?.forEach { it.deleteRecursively() }
            }
            val target = File(dir, folder).apply {
                deleteRecursively()
                mkdirs()
            }
            val file = File(target, "${openedPictureName(name)}.${type.extension}")
            // Written apart and moved in once whole: a write cut short, as on a full disk, leaves
            // no copy a later open would take.
            val part = File(target, ".${file.name}.part")
            try {
                part.writeBytes(bytes)
                check(part.renameTo(file)) { "not moved into place" }
            } finally {
                part.delete()
            }
            if (context.isCurrent()) {
                MoodleOpenedPicture(file, type.mimeType)
            } else {
                target.deleteRecursively()
                null
            }
        }.onFailure { Log.w(TAG, "member picture not kept to open: ${it.logSummary()}") }.getOrNull()
    }

    /** Replaces the member's earlier pictures. */
    private suspend fun savePicture(bytes: ByteArray, file: File) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = file.parentFile ?: return@runCatching
            dir.mkdirs()
            val prefix = file.name.substringBefore('-') + "-"
            dir.listFiles { _, name -> name.startsWith(prefix) }?.forEach { it.delete() }
            file.writeBytes(bytes)
        }
    }

    companion object {
        private const val TAG = "MoodleCourseStore"

        /** The folder of a 100 px picture opened in the large size's stead. */
        private const val SHOWN_SIZE_SUFFIX = "-shown"

        /** Moodle's 512 px size of a member's picture: `f3` where the list's is `f1`. */
        internal fun largePictureUrl(url: String): String = url.replace(Regex("/f1(?=[?#]|$)"), "/f3")

        /** A file name of [name], with nothing a file system or a viewer would trip on. */
        internal fun openedPictureName(name: String): String =
            name.map { if (it.isISOControl() || it in FORBIDDEN_NAME_CHARS) '_' else it }
                .joinToString("").trim().trimStart('.').take(80).ifEmpty { "picture" }

        private const val FORBIDDEN_NAME_CHARS = "/\\:*?\"<>|"

        /** The largest attachment downloaded into the app; a larger one opens on the website. */
        const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024

        /** How many pages an announcement opened cold is looked for in. */
        private const val MAX_LOOKUP_PAGES = 10

        val FORUM_FUNCTIONS = setOf(MoodleCourseApi.FORUMS_FUNCTION, MoodleCourseApi.DISCUSSIONS_FUNCTION)

        /**
         * The codes a course's Moodle summary is checked against: every idnumber the Moodle course
         * [courseId] goes by in [idMap], since a co-listed course answers to each department's, the
         * course number in each, and [courseNo] from the class table.
         */
        fun courseCodes(courseId: Int, courseNo: String?, idMap: Map<String, Int>): List<String> {
            val idnumbers = idMap.filterValues { it == courseId }.keys.toList()
            return idnumbers + idnumbers.map { it.drop(4) } + listOfNotNull(courseNo)
        }

        /**
         * The term Moodle course [courseId] is of, by its idnumbers' term prefix, such as "1151";
         * a course listed under more than one gets them all. Null with none known.
         */
        fun term(courseId: Int, idMap: Map<String, Int>): String? =
            idMap.filterValues { it == courseId }.keys
                .mapNotNull(org.ntust.app.tigerduck.network.MoodleCourseIds::semesterPrefix)
                .toSortedSet()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("-")

        /** Every Moodle course of the term [courseId] belongs to, by the idnumbers' term prefix. */
        fun termCourseIds(courseId: Int, idMap: Map<String, Int>): List<Int> {
            val terms = idMap.filterValues { it == courseId }.keys
                .mapNotNull(org.ntust.app.tigerduck.network.MoodleCourseIds::semesterPrefix)
                .toSet()
            if (terms.isEmpty()) return listOf(courseId)
            return idMap.filterKeys { org.ntust.app.tigerduck.network.MoodleCourseIds.semesterPrefix(it) in terms }
                .values.distinct().sorted()
        }
    }
}

/** A file as it opens: the copy in the app, its own link off the Moodle site, or why neither. */
sealed interface MoodleAttachment {
    data class Local(val file: File) : MoodleAttachment
    data class Link(val url: String) : MoodleAttachment
    data class Failed(val error: Throwable) : MoodleAttachment

    /** Stopped, or landed after the account signed out and was not kept: there is nothing to show. */
    data object Dropped : MoodleAttachment
}

/** What a page opened with nothing cached found: what it shows, nothing (it is gone), or why not. */
sealed interface MoodleLookup {
    data object Found : MoodleLookup
    data object Missing : MoodleLookup
    data class Failed(val error: Throwable) : MoodleLookup
}

/** A member's picture as a file to open full screen; see [MoodleCourseStore.fullPicture]. */
data class MoodleOpenedPicture(val file: File, val mimeType: String)

/** A picture's file type, by its first bytes; JPEG, as Moodle mostly serves, when they say none. */
internal enum class MoodlePictureType(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp"),
    ;

    companion object {
        fun of(bytes: ByteArray): MoodlePictureType {
            fun at(index: Int, vararg expected: Int) =
                bytes.size >= index + expected.size &&
                    expected.withIndex().all { (i, b) -> bytes[index + i] == b.toByte() }
            return when {
                at(0, 0x89, 'P'.code, 'N'.code, 'G'.code) -> PNG
                at(0, 'G'.code, 'I'.code, 'F'.code, '8'.code) -> GIF
                at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code) -> WEBP
                else -> JPEG
            }
        }

        fun ofExtension(extension: String): MoodlePictureType =
            entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) } ?: JPEG
    }
}
