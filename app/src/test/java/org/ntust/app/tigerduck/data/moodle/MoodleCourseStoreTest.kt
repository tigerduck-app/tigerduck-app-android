package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import java.io.IOException
import java.lang.reflect.Type

@OptIn(ExperimentalCoroutinesApi::class)
class MoodleCourseStoreTest {

    private class MemoryCache : MoodleCachePort {
        val files = mutableMapOf<String, Pair<Any, Long>>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = files[name] as Pair<T, Long>?

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) {
            files[name] = value to fetchedAtMs
        }
    }

    private class FakeApi : MoodleCourseApi {
        var allowed = setOf(
            MoodleCourseApi.FORUMS_FUNCTION, MoodleCourseApi.DISCUSSIONS_FUNCTION,
            MoodleCourseApi.VIEW_DISCUSSION_FUNCTION, MoodleCourseApi.MEMBERS_FUNCTION,
            MoodleCourseApi.GRADE_ITEMS_FUNCTION, MoodleCourseApi.COURSES_FUNCTION,
        )
        var forumList = listOf<MoodleForum>()
        var pages = mapOf<Int, List<MoodleDiscussion>>()
        var gradeCalls = 0
        var forumCalls = 0
        var discussionCalls = 0
        var memberCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var gradesAnswer: () -> List<MoodleGradeItem> = { emptyList() }
        var membersAnswer: () -> MoodleMemberList = { MoodleMemberList(null, emptyList()) }
        val viewed = mutableListOf<Int>()

        override suspend fun functions() = allowed
        var gateForums: CompletableDeferred<Unit>? = null
        override suspend fun forums(courseIds: List<Int>): List<MoodleForum> {
            forumCalls++
            gateForums?.await()
            return forumList.filter { it.courseId in courseIds }
        }
        var failNextPage = false
        var discussionsGate: CompletableDeferred<Unit>? = null
        override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> {
            discussionCalls++
            discussionsGate?.await()
            if (failNextPage) {
                failNextPage = false
                throw IOException("offline")
            }
            return pages[page].orEmpty()
        }
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) {
            viewed += discussionId
        }
        override suspend fun members(courseId: Int): MoodleMemberList {
            memberCalls++
            return membersAnswer()
        }
        /** The picture URLs asked for, in order, and what each answers. */
        val pictureUrls = mutableListOf<String>()
        var pictureAnswer: suspend (String) -> ByteArray = { ByteArray(0) }
        override suspend fun picture(url: String): ByteArray {
            pictureUrls += url
            return pictureAnswer(url)
        }
        override suspend fun gradeItems(courseId: Int): List<MoodleGradeItem> {
            gradeCalls++
            gate?.await()
            return gradesAnswer()
        }
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun submission(assignId: Int) = throw UnsupportedOperationException("not asked here")
        override suspend fun comments(cmid: Int, submissionId: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment>()
        override suspend fun groups(courseId: Int) = emptyMap<Int, String>()
        var downloads = 0
        var downloadAnswer: (java.io.File) -> Unit = { it.writeText("pdf bytes") }
        var lastLimit = 0L
        var downloadGate: CompletableDeferred<Unit>? = null
        /** One signal per download as it starts, its partial file written. */
        val downloadStarted = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override suspend fun download(
            file: org.ntust.app.tigerduck.network.model.moodle.MoodleFile,
            target: java.io.File,
            maxBytes: Long,
            progress: (Float) -> Unit,
        ) {
            downloads++
            lastLimit = maxBytes
            downloadAnswer(target)
            progressShares.forEach(progress)
            downloadStarted.trySend(Unit)
            downloadGate?.await()
        }
        var progressShares = listOf<Float>()

        var postsAnswer: (Int) -> List<org.ntust.app.tigerduck.network.model.moodle.MoodlePost> = { emptyList() }
        var postsGate: CompletableDeferred<Unit>? = null
        var postCalls = 0
        override suspend fun posts(discussionId: Int): List<org.ntust.app.tigerduck.network.model.moodle.MoodlePost> {
            postCalls++
            postsGate?.await()
            return postsAnswer(discussionId)
        }

        var materialsAnswer: () -> org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials = { testMaterials() }
        override suspend fun materials(courseId: Int) = materialsAnswer()

        val materialViews = mutableListOf<Int>()
        override suspend fun markMaterialViewed(
            material: org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial,
            isCurrent: () -> Boolean,
        ) {
            if (isCurrent()) materialViews += material.instance
        }
    }

    private var now = 1_000_000L
    private var signedIn = true
    private val cache = MemoryCache()
    private val api = FakeApi()

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private val records = kotlinx.coroutines.flow.MutableStateFlow(emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord>())

    private fun TestScope.store(): MoodleCourseStore {
        if (!::downloadScope.isInitialized) downloadScope = backgroundScope
        return buildStore()
    }

    private fun TestScope.buildStore() = MoodleCourseStore(
        courseId = 5,
        termCourseIds = listOf(6),
        courseCodes = listOf("CS3005301"),
        api = api,
        context = MoodleCacheContext("B1", cache, isCurrent = { signedIn }, now = { now }),
        scope = backgroundScope,
        downloads = downloads,
        filesDir = java.io.File(tmp.root, "account/5"),
        assignmentRecords = records,
    )

    private val downloads by lazy { MoodleFileDownloads(root = { tmp.root }, scope = downloadScope) }

    /** Where downloads run, as the app's scope: apart from the store's, which a sign-out cancels. */
    private lateinit var downloadScope: kotlinx.coroutines.CoroutineScope

    private fun ref(file: org.ntust.app.tigerduck.network.model.moodle.MoodleFile = attachment) =
        MoodleFileRef(file, MoodleFileOwner.Post(90))

    @Test
    fun `a round that ends while the course detail shows brings the assignments row`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        assertTrue(store.sectionRows.value.isEmpty())

        records.value = listOf(testRecord(701, courseId = 5), testRecord(702, courseId = 5), testRecord(801, courseId = 6))
        runCurrent()

        assertEquals(
            listOf(MoodleSectionRow(MoodleSectionRow.Kind.ASSIGNMENTS, 2, hasUnread = false)),
            store.sectionRows.value,
        )
    }

    private val attachment = org.ntust.app.tigerduck.network.model.moodle.MoodleFile(
        name = "hw3.pdf", size = 9, mimeType = "application/pdf", modified = 1L, path = "/",
        url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_forum/attachment/90/hw3.pdf",
    )

    private val item = MoodleGradeItem(1, "HW1", "mod", "assign", 31, "95", 100.0, false, "")

    @Test
    fun `the cached copy shows before any fetch`() = runTest(UnconfinedTestDispatcher()) {
        cache.files["courses/5/grades"] = MoodleCourseGrades(listOf(item)) to 999L
        val store = store()
        store.awaitCache()

        assertEquals(listOf(item), store.grades.value!!.items)
        assertEquals(999L, store.grades.state.value.fetchedAtMs)
        assertEquals(0, api.gradeCalls)
    }

    @Test
    fun `a refresh asked for while one runs joins it`() = runTest(UnconfinedTestDispatcher()) {
        api.gate = CompletableDeferred()
        api.gradesAnswer = { listOf(item) }
        val store = store()

        val first = async { store.refreshGrades() }
        val second = async { store.refreshGrades() }
        runCurrent()
        api.gate!!.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, api.gradeCalls)
        assertEquals(listOf(item), store.grades.value!!.items)
        assertEquals(now, cache.files["courses/5/grades"]!!.second)
    }

    @Test
    fun `a failed refresh keeps the copy and says why`() = runTest(UnconfinedTestDispatcher()) {
        cache.files["courses/5/grades"] = MoodleCourseGrades(listOf(item)) to 999L
        api.gradesAnswer = { throw IOException("offline") }
        val store = store()

        store.refreshGrades()

        assertEquals(listOf(item), store.grades.value!!.items)
        assertTrue(store.grades.state.value.failure is IOException)
        assertEquals("a failure is not dated as a fetch", 999L, store.grades.state.value.fetchedAtMs)
    }

    @Test
    fun `an answer that lands after a sign-out is neither shown nor kept`() = runTest(UnconfinedTestDispatcher()) {
        api.gradesAnswer = {
            signedIn = false
            listOf(item)
        }
        val store = store()

        store.refreshGrades()

        assertNull(store.grades.value)
        assertTrue(cache.files.isEmpty())
    }

    @Test
    fun `a site that does not allow a call hides its row rather than failing`() = runTest(UnconfinedTestDispatcher()) {
        api.allowed = emptySet()
        val store = store()

        store.refreshGrades()
        store.refreshMembers()
        store.refreshForums()

        assertTrue(store.grades.value!!.items.isEmpty())
        assertNull(store.grades.state.value.failure)
        assertTrue(store.sectionRows.value.isEmpty())
    }

    @Test
    fun `a course that hides its participants has no members row`() = runTest(UnconfinedTestDispatcher()) {
        api.membersAnswer = { throw MoodleWebserviceError.MalformedResponse("nopermissions") }
        val store = store()

        store.refreshMembers()

        assertTrue(store.members.value!!.members.isEmpty())
        assertNull(store.members.state.value.failure)
    }

    @Test
    fun `one forums call covers the term, and the course's news forum is its announcements`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(
            testForum(1, courseId = 5, type = "news", count = 2, unread = 1),
            testForum(2, courseId = 6, type = "news", count = 4),
        )
        val store = store()

        store.refreshForums()

        assertEquals(listOf(5, 6), store.forums.value!!.courseIds)
        assertEquals(1, store.newsForum.value!!.id)
        assertEquals(MoodleSectionRow(MoodleSectionRow.Kind.ANNOUNCEMENTS, 2, true), store.sectionRows.value.single())
    }

    @Test
    fun `opening an announcement clears its dot and logs the view`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()
        assertEquals("loading the list marks nothing read", emptyList<Int>(), api.viewed)

        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        runCurrent()

        assertEquals(listOf(10), api.viewed)
        assertEquals(0, pages.list.value!!.discussions.first { it.id == 10 }.unreadCount)
        assertEquals(false, store.sectionRows.value.single().hasUnread)
    }

    @Test
    fun `the next page loads at the list's end, and a failed one can be retried`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 21))
        api.pages = mapOf(0 to (1..20).map { testDiscussion(it, created = 100L + it) })
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()
        val last = MoodleDiscussions.sorted(pages.list.value!!).last().id
        assertTrue(pages.list.value!!.hasMore)

        api.failNextPage = true
        pages.loadMore(last)
        assertTrue(pages.pageFailure.value is IOException)
        assertEquals("a failed page leaves the list as it was", 20, pages.list.value!!.discussions.size)

        api.pages = api.pages + (1 to listOf(testDiscussion(21, created = 1)))
        pages.loadMore(last)

        assertNull(pages.pageFailure.value)
        assertEquals(21, pages.list.value!!.discussions.size)
        assertEquals(false, pages.list.value!!.hasMore)
    }

    @Test
    fun `an announcement opened with nothing cached fetches the forums and the list, once`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10)))
        val store = store()

        store.ensureAnnouncement(10)

        assertEquals(1, api.forumCalls)
        assertEquals(1, api.discussionCalls)
        assertEquals(listOf(10), store.announcementPages()!!.list.value!!.discussions.map { it.id })

        store.ensureAnnouncement(10)
        assertEquals("what is there is not fetched again", 1 to 1, api.forumCalls to api.discussionCalls)
    }

    @Test
    fun `a member opened with nothing cached fetches the members, once`() = runTest(UnconfinedTestDispatcher()) {
        api.membersAnswer = {
            MoodleMemberList(1, listOf(org.ntust.app.tigerduck.network.model.moodle.MoodleMember(
                7, "B1 @ Amy", null, null, emptyList(), emptyList(), null, "", emptyList(), emptyList(),
            )))
        }
        val store = store()

        store.ensureMember(7)
        store.ensureMember(7)

        assertEquals(1, api.memberCalls)
        assertEquals("B1 @ Amy", store.member(7)!!.fullName)
    }

    @Test
    fun `an attachment is downloaded into the app once, and opened from there after`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()

        val first = store.download(ref(attachment))
        val second = store.download(ref(attachment))

        val file = (first as MoodleAttachment.Local).file
        assertEquals("pdf bytes", file.readText())
        assertTrue("kept under the account's own folder", file.canonicalPath.startsWith(tmp.root.canonicalPath))
        assertEquals(file, (second as MoodleAttachment.Local).file)
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a failed download leaves nothing behind and says why`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadAnswer = { target ->
            target.writeText("half")
            throw IOException("connection reset")
        }
        val store = store()

        val result = store.download(ref(attachment))

        assertTrue((result as MoodleAttachment.Failed).error is IOException)
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `a file that is not on the Moodle site opens as a link, with no key and no download`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val elsewhere = attachment.copy(url = "https://example.com/hw3.pdf")

        assertEquals(MoodleAttachment.Link("https://example.com/hw3.pdf"), store.download(ref(elsewhere)))
        assertEquals(0, api.downloads)
    }

    @Test
    fun `a member's picture is decoded off the thread that asked`() = runTest(UnconfinedTestDispatcher()) {
        val background = object : kotlinx.coroutines.CoroutineDispatcher() {
            var used = 0
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                used++
                block.run()
            }
        }
        var decoded = 0
        val store = MoodleCourseStore(
            courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
            context = MoodleCacheContext("B1", cache, isCurrent = { true }, now = { now }),
            scope = backgroundScope,
            decodePicture = { decoded++; null },
            background = background,
        )
        val member = org.ntust.app.tigerduck.network.model.moodle.MoodleMember(
            7, "B1 @ Amy", null, null, emptyList(), emptyList(),
            "https://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/f1?rev=3", "", emptyList(), emptyList(),
        )

        store.loadPicture(member)

        assertEquals(1, decoded)
        assertTrue(background.used > 0)
    }

    @Test
    fun `an announcement older than the first page is found on the pages after it`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 25))
        api.pages = mapOf(
            0 to (1..20).map { testDiscussion(it, created = 1_000L - it) },
            1 to (21..25).map { testDiscussion(it, created = 1_000L - it) },
        )

        assertEquals(MoodleLookup.Found, store().ensureAnnouncement(24))
    }

    @Test
    fun `an announcement that is gone, or a fetch that failed, says so rather than waiting`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10)))
        assertEquals(MoodleLookup.Missing, store().ensureAnnouncement(99))

        cache.files.clear()
        api.failNextPage = true
        val failed = store().ensureAnnouncement(10)
        assertTrue((failed as MoodleLookup.Failed).error is IOException)
    }

    @Test
    fun `a member page says when its member is gone or the fetch failed`() = runTest(UnconfinedTestDispatcher()) {
        api.membersAnswer = { MoodleMemberList(1, emptyList()) }
        assertEquals(MoodleLookup.Missing, store().ensureMember(7))

        cache.files.clear()
        api.membersAnswer = { throw IOException("offline") }
        assertTrue((store().ensureMember(7) as MoodleLookup.Failed).error is IOException)
    }

    @Test
    fun `same-named files in different places, or a replaced file, get copies of their own`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val first = (store.download(ref(attachment)) as MoodleAttachment.Local).file
        val elsewhere = (store.download(ref(attachment.copy(url = attachment.url.replace("/90/", "/91/")))) as MoodleAttachment.Local).file
        val replaced = (store.download(ref(attachment.copy(modified = 2L))) as MoodleAttachment.Local).file

        assertEquals(setOf("hw3.pdf"), setOf(first.name, elsewhere.name, replaced.name))
        assertEquals(3, setOf(first, elsewhere, replaced).size)
        assertEquals(3, api.downloads)
    }

    @Test
    fun `a file Moodle lists no type for is kept once downloaded, as any other`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val untyped = attachment.copy(mimeType = null)

        store.download(ref(untyped))
        store.download(ref(untyped))
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a download is held to the size Moodle lists, and an oversized file opens on the website`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.download(ref(attachment))
        assertEquals(9L, api.lastLimit)

        val huge = attachment.copy(size = 2L * 1024 * 1024 * 1024)
        assertEquals(
            MoodleAttachment.Link("https://moodle2.ntust.edu.tw/pluginfile.php/1/mod_forum/attachment/90/hw3.pdf"),
            store.download(ref(huge)),
        )
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a download that lands after a sign-out is deleted, not opened`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadAnswer = { target ->
            target.writeText("pdf bytes")
            signedIn = false
        }
        val store = store()

        assertEquals(MoodleAttachment.Dropped, store.download(ref(attachment)))
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `a file name cannot climb out of its folder`() = runTest(UnconfinedTestDispatcher()) {
        val sneaky = attachment.copy(name = "..\\..\\x/y.pdf")
        val file = (store().download(ref(sneaky)) as MoodleAttachment.Local).file

        assertTrue(file.canonicalPath.startsWith(tmp.root.canonicalPath))
        assertTrue(!file.name.contains('/') && !file.name.contains('\\') && !file.name.startsWith("."))
    }

    @Test
    fun `opening a file twice at once downloads it once`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadGate = CompletableDeferred()
        val store = store()

        val first = async { store.download(ref(attachment)) }
        api.downloadStarted.receive()
        val second = async { store.download(ref(attachment)) }
        runCurrent()
        api.downloadGate!!.complete(Unit)

        assertEquals(first.await(), second.await())
        assertTrue(first.await() is MoodleAttachment.Local)
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a download stopped by a sign-out leaves no partial file and nothing to open`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadAnswer = { it.writeText("half") }
        api.downloadGate = CompletableDeferred()
        val store = store()
        val opening = async { store.download(ref()) }
        api.downloadStarted.receive()
        assertTrue("the partial file is there while it downloads", tmp.root.walkTopDown().any { it.isFile })

        downloads.clear()

        assertEquals(MoodleAttachment.Dropped, opening.await())
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `a tap on the progress ring stops the download and leaves nothing`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadAnswer = { it.writeText("half") }
        api.downloadGate = CompletableDeferred()
        val store = store()
        val opening = async { store.download(ref()) }
        api.downloadStarted.receive()
        assertTrue(store.downloadState(ref(), downloads.snapshot.value) is MoodleDownloadState.Downloading)

        store.cancelDownload(ref())

        assertEquals(MoodleAttachment.Dropped, opening.await())
        assertEquals(MoodleDownloadState.NotDownloaded, store.downloadState(ref(), downloads.snapshot.value))
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `progress moves in whole percents, and a failure shows on the row`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadGate = CompletableDeferred()
        api.progressShares = listOf(0.123f, 0.5f)
        val store = store()
        val opening = async { store.download(ref()) }
        api.downloadStarted.receive()

        assertEquals(MoodleDownloadState.Downloading(0.5f), store.downloadState(ref(), downloads.snapshot.value))
        api.downloadGate!!.complete(Unit)
        opening.await()
        assertTrue(store.downloadState(ref(), downloads.snapshot.value) is MoodleDownloadState.Downloaded)

        api.downloadGate = null
        api.downloadAnswer = { throw IOException("offline") }
        val other = ref(attachment.copy(url = attachment.url.replace("/90/", "/93/")))
        assertTrue(store.download(other) is MoodleAttachment.Failed)
        assertTrue(store.downloadState(other, downloads.snapshot.value) is MoodleDownloadState.Failed)
    }

    @Test
    fun `a copy downloaded before shows as downloaded when the course opens again`() = runTest(UnconfinedTestDispatcher()) {
        val file = (store().download(ref()) as MoodleAttachment.Local).file
        // A new run of the app: nothing known but what is on disk.
        val fresh = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val again = MoodleCourseStore(
            courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
            context = MoodleCacheContext("B1", cache, isCurrent = { signedIn }, now = { now }),
            scope = backgroundScope, downloads = fresh, filesDir = java.io.File(tmp.root, "account/5"),
        )
        // The store learns the folder in a coroutine of its own.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { fresh.snapshot.first { it.copies.isNotEmpty() } }
        }

        assertEquals(MoodleDownloadState.Downloaded(file), again.downloadState(ref(), fresh.snapshot.value))
    }

    @Test
    fun `a long name is cut to fit the file system, keeping its extension`() = runTest(UnconfinedTestDispatcher()) {
        val long = attachment.copy(name = "資料結構期中考試題與詳解".repeat(12) + ".pdf")
        val file = (store().download(ref(long)) as MoodleAttachment.Local).file

        assertTrue(file.name.toByteArray().size <= 200)
        assertTrue(file.name.endsWith(".pdf"))
        assertTrue(file.name.startsWith("資料結構"))
    }

    @Test
    fun `a long name whose extension cannot be kept does not end in another`() = runTest(UnconfinedTestDispatcher()) {
        // Cut at 200 bytes, it would end in ".html", which another app reads as a web page.
        val long = attachment.copy(name = "a".repeat(195) + ".html." + "x".repeat(20), mimeType = "application/octet-stream")
        val file = (store().download(ref(long)) as MoodleAttachment.Local).file

        assertTrue(file.name.toByteArray().size <= 200)
        assertTrue("got ${file.name}", !file.name.contains('.'))
    }

    @Test
    fun `each term keeps its own copy of the forums`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(
            testForum(1, courseId = 5, type = "news", count = 2),
            testForum(7, courseId = 3, type = "news", count = 9),
        )
        fun termStore(courseId: Int, term: String) = MoodleCourseStore(
            courseId = courseId, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
            context = MoodleCacheContext("B1", cache, isCurrent = { signedIn }, now = { now }),
            scope = backgroundScope, term = term,
        )
        termStore(5, "1151").refreshForums()
        // A course of an older term, opened after.
        termStore(3, "1142").refreshForums()

        // Offline, after a restart: the current course's announcements come from its own term's copy.
        val reopened = termStore(5, "1151")
        reopened.awaitCache()

        assertEquals(1, reopened.newsForum.value?.id)
    }

    @Test
    fun `a course's term is read off its idnumbers`() {
        val ids = mapOf("1151CS3005301" to 5, "1151CS3204301" to 6, "1142CS2001301" to 3)
        assertEquals("1151", MoodleCourseStore.term(5, ids))
        assertEquals("1142", MoodleCourseStore.term(3, ids))
        assertNull("none known", MoodleCourseStore.term(99, ids))
    }

    @Test
    fun `an announcement opened while the list refreshes stays read`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()

        // The answer under way was read before Moodle logged the view, so it still says unread.
        api.discussionsGate = CompletableDeferred()
        val refresh = async { pages.refresh() }
        runCurrent()
        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        api.discussionsGate!!.complete(Unit)
        refresh.await()

        assertEquals(0, pages.list.value!!.discussions.first { it.id == 10 }.unreadCount)
        assertEquals(false, store.sectionRows.value.single().hasUnread)
        @Suppress("UNCHECKED_CAST")
        val saved = cache.files["courses/5/announcements"]!!.first as org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
        assertEquals("and so it is kept", 0, saved.discussions.first { it.id == 10 }.unreadCount)
    }

    @Test
    fun `a section row's dot cleared while the forums refresh stays cleared`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()

        api.gateForums = CompletableDeferred()
        val refresh = async { store.refreshForums() }
        runCurrent()
        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        api.gateForums!!.complete(Unit)
        refresh.await()

        assertEquals(false, store.sectionRows.value.single().hasUnread)
    }

    @Test
    fun `a forums answer that already counts the read keeps the other announcement's dot`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 2))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11, unread = 1)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()

        // Moodle logs the view before it answers the forums request under way, so the answer
        // already leaves the opened announcement out: replaying the read must not take it off again.
        api.gateForums = CompletableDeferred()
        val refresh = async { store.refreshForums() }
        runCurrent()
        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.gateForums!!.complete(Unit)
        refresh.await()

        assertEquals(1, store.forums.value!!.forums.single().unreadPostCount)
        assertEquals(true, store.sectionRows.value.single().hasUnread)
    }

    // --- threads ---

    private val postsAllowed get() = api.allowed + MoodleCourseApi.DISCUSSION_POSTS_FUNCTION

    @Test
    fun `opening a thread fetches its posts, keeps which were unread and only then marks it read`() = runTest(UnconfinedTestDispatcher()) {
        api.allowed = postsAllowed
        val general = testForum(id = 2, courseId = 5, type = "general", count = 1, unread = 2)
        api.forumList = listOf(general)
        api.pages = mapOf(0 to listOf(testDiscussion(40, unread = 2)))
        api.postsAnswer = { listOf(testPost(400), testPost(401, parent = 400, unread = true), testPost(402, parent = 400, unread = true)) }
        api.postsGate = CompletableDeferred()
        val store = store()
        store.refreshForums()
        val pages = store.discussions(general)
        pages.refresh()

        val opening = async { store.openThread(pages.list.value!!.discussions.single(), general) }
        runCurrent()
        assertTrue("nothing is marked read before the posts show", api.viewed.isEmpty())
        api.postsGate!!.complete(Unit)

        assertEquals(setOf(401, 402), opening.await())
        assertEquals(listOf(40), api.viewed)
        assertEquals(0, pages.list.value!!.discussions.single().unreadCount)
        assertTrue("the thread's own dots clear", store.thread(40).value!!.posts.none { it.isUnread })
        assertEquals(false, store.sectionRows.value.single { it.kind == MoodleSectionRow.Kind.FORUMS }.hasUnread)
    }

    @Test
    fun `a thread that fails to load is not marked read, and keeps the list's first post`() = runTest(UnconfinedTestDispatcher()) {
        api.allowed = postsAllowed
        val general = testForum(id = 2, courseId = 5, type = "general", count = 1)
        api.postsAnswer = { throw IOException("offline") }
        val store = store()

        assertNull(store.openThread(testDiscussion(40, unread = 1), general))
        assertTrue(api.viewed.isEmpty())
        assertEquals(listOf(400), MoodleThreads.entries(testDiscussion(40), store.thread(40).value).map { it.post.id })
    }

    @Test
    fun `a site without the posts call keeps the first post and fetches no replies`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.refreshThread(40)

        assertEquals(0, api.postCalls)
        assertTrue(store.thread(40).value!!.posts.isEmpty())
    }

    @Test
    fun `the forums row needs a general forum with discussions, and a site without posts shows none`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(
            testForum(id = 1, courseId = 5, type = "news", count = 2),
            testForum(id = 2, courseId = 5, type = "general", count = 3, unread = 1),
        )
        val store = store()
        store.refreshForums()
        assertTrue(store.sectionRows.value.none { it.kind == MoodleSectionRow.Kind.FORUMS })

        api.allowed = postsAllowed
        store.refreshForums()
        assertEquals(MoodleSectionRow(MoodleSectionRow.Kind.FORUMS, 3, true), store.sectionRows.value.last())
        assertEquals(listOf(2), store.generalForums.value.map { it.id })
    }

    // --- materials ---

    private val contentsAllowed get() = api.allowed + MoodleCourseApi.CONTENTS_FUNCTION + "mod_resource_view_resource"

    @Test
    fun `materials count files and links, and a site without the call hides their row`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.refreshMaterials()
        assertTrue(store.materials.value!!.sections.isEmpty())
        assertTrue(store.sectionRows.value.isEmpty())

        api.allowed = contentsAllowed
        store.refreshMaterials()
        // Waited for, not read at once: refreshMaterials ends on the thread its outdated-copy sweep
        // ran on, so the rows the new materials make can land just after it returns. Read at once,
        // the row was missing now and then on CI.
        assertEquals(
            MoodleSectionRow(MoodleSectionRow.Kind.MATERIALS, 4, false),
            store.sectionRows.first { it.isNotEmpty() }.single(),
        )
    }

    @Test
    fun `opening a file logs a view only where the site allows it, and not after a sign-out`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val syllabus = testMaterials().sections[0].materials[0]
        val labs = testMaterials().sections[1].materials[0]
        api.allowed = contentsAllowed

        store.open(syllabus)
        store.open(labs)
        assertEquals("the folder view is not allowed here", listOf(7), api.materialViews)

        signedIn = false
        store.open(syllabus)
        assertEquals(listOf(7), api.materialViews)
    }

    @Test
    fun `a copy of a version Moodle no longer lists is deleted once the materials come in`() = runTest(UnconfinedTestDispatcher()) {
        api.allowed = contentsAllowed
        val store = store()
        store.refreshMaterials()
        val old = MoodleMaterials.fileRefs(store.materials.value!!).first()
        val oldCopy = (store.download(old) as MoodleAttachment.Local).file
        val untouched = MoodleMaterials.fileRefs(store.materials.value!!)[1]
        val untouchedCopy = (store.download(untouched) as MoodleAttachment.Local).file

        api.materialsAnswer = {
            val listed = testMaterials()
            listed.copy(
                sections = listed.sections.map { section ->
                    section.copy(
                        materials = section.materials.map { m ->
                            if (m.id == 101) m.copy(files = m.files.map { it.copy(modified = 2L) }) else m
                        },
                    )
                },
            )
        }
        store.refreshMaterials()

        assertTrue(!oldCopy.exists())
        assertTrue("a file Moodle lists unchanged stays", untouchedCopy.exists())
        val newer = MoodleMaterials.fileRefs(store.materials.value!!).first()
        assertEquals(MoodleDownloadState.NotDownloaded, store.downloadState(newer, downloads.snapshot.value))
        assertEquals(MoodleDownloadState.NotDownloaded, store.downloadState(old, downloads.snapshot.value))
    }

    // --- A member's picture full screen (tigerduck-app#741, #698) ---

    private val small = "https://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/moove/f1?rev=3"
    private val large = "https://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/moove/f3?rev=3"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    private fun amy(pictureUrl: String? = small) = org.ntust.app.tigerduck.network.model.moodle.MoodleMember(
        7, "B00015007 @ Amy Lin", null, null, emptyList(), emptyList(), pictureUrl, "", emptyList(), emptyList(),
    )

    private fun TestScope.pictureStore(isCurrent: () -> Boolean = { signedIn }) = MoodleCourseStore(
        courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
        context = MoodleCacheContext("B1", cache, isCurrent = isCurrent, now = { now }),
        scope = backgroundScope,
        picturesDir = java.io.File(tmp.root, "moodle/pictures"),
        openedPicturesDir = java.io.File(tmp.root, "moodle_files/pictures"),
        decodePicture = { OnePixel },
    )

    /** A decoded picture, without Android's Bitmap. */
    private object OnePixel : androidx.compose.ui.graphics.ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
    }

    @Test
    fun `a member's picture opens in Moodle's 512 px size, downloaded once and named after the member`() = runTest(UnconfinedTestDispatcher()) {
        api.pictureAnswer = { jpeg }
        val store = pictureStore()

        val first = store.fullPicture(amy())!!
        val again = store.fullPicture(amy())!!

        assertEquals(listOf(large), api.pictureUrls)
        assertEquals("Amy Lin.jpg", first.file.name)
        assertEquals("image/jpeg", first.mimeType)
        assertEquals(first.file, again.file)
        assertTrue(first.file.readBytes().contentEquals(jpeg))
        assertTrue("where the system's viewers may read it", first.file.canonicalPath.startsWith(java.io.File(tmp.root, "moodle_files").canonicalPath))
    }

    @Test
    fun `with the large size not downloaded the picture shown opens, and the next open tries again`() = runTest(UnconfinedTestDispatcher()) {
        val store = pictureStore()
        api.pictureAnswer = { url -> if (url == small) jpeg else throw IOException("offline") }
        store.loadPicture(amy())

        val fallback = store.fullPicture(amy())!!
        assertTrue(fallback.file.readBytes().contentEquals(jpeg))

        api.pictureAnswer = { byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 9) }
        val retried = store.fullPicture(amy())!!
        assertEquals(listOf(small, large, large), api.pictureUrls)
        assertEquals("image/png", retried.mimeType)
        assertEquals("Amy Lin.png", retried.file.name)
    }

    @Test
    fun `Moodle's default picture opens nothing`() = runTest(UnconfinedTestDispatcher()) {
        assertNull(pictureStore().fullPicture(amy(pictureUrl = null)))
        assertEquals(emptyList<String>(), api.pictureUrls)
    }

    @Test
    fun `a second open while the first gets ready opens nothing, and the member shows it is getting ready`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        api.pictureAnswer = { gate.await(); jpeg }
        val store = pictureStore()

        val first = async { store.fullPicture(amy()) }
        assertEquals(setOf(7), store.openingPictures.value)
        assertNull(store.fullPicture(amy()))

        gate.complete(Unit)
        assertTrue(first.await() != null)
        assertEquals(emptySet<Int>(), store.openingPictures.value)
        assertEquals(1, api.pictureUrls.size)
    }

    @Test
    fun `a new upload of the picture replaces the copy kept`() = runTest(UnconfinedTestDispatcher()) {
        api.pictureAnswer = { jpeg }
        val store = pictureStore()
        val old = store.fullPicture(amy())!!

        val newer = store.fullPicture(amy(pictureUrl = small.replace("rev=3", "rev=4")))!!

        assertFalse("the earlier revision's copy is gone", old.file.exists())
        assertTrue(newer.file.exists())
    }

    @Test
    fun `a picture that lands after a sign-out opens nothing and is not kept`() = runTest(UnconfinedTestDispatcher()) {
        api.pictureAnswer = { signedIn = false; jpeg }

        assertNull(pictureStore().fullPicture(amy()))
        assertTrue(java.io.File(tmp.root, "moodle_files").walkTopDown().none { it.isFile })
    }

    @Test
    fun `a picture kept as the sign-out begins is not left behind`() = runTest(UnconfinedTestDispatcher()) {
        api.pictureAnswer = { jpeg }
        // Signed in at the download's end, signed out once the copy is written.
        var checks = 0
        val store = pictureStore(isCurrent = { ++checks <= 1 })

        assertNull(store.fullPicture(amy()))
        assertTrue(java.io.File(tmp.root, "moodle_files").walkTopDown().none { it.isFile })
    }

    @Test
    fun `a kept copy is not handed out after a sign-out`() = runTest(UnconfinedTestDispatcher()) {
        api.pictureAnswer = { jpeg }
        val store = pictureStore()
        assertTrue(store.fullPicture(amy()) != null)

        signedIn = false

        assertNull(store.fullPicture(amy()))
    }

    @Test
    fun `a copy a write left cut short is never opened, and the next open downloads it again`() = runTest(UnconfinedTestDispatcher()) {
        // As a write cut short by a full disk leaves it, in the folder a whole copy would be in.
        val folder = java.io.File(tmp.root, "moodle_files/pictures/7-3").apply { mkdirs() }
        java.io.File(folder, ".Amy Lin.jpg.part").writeBytes(byteArrayOf(0xFF.toByte()))
        api.pictureAnswer = { jpeg }

        val opened = pictureStore().fullPicture(amy())!!

        assertEquals(listOf(large), api.pictureUrls)
        assertEquals("Amy Lin.jpg", opened.file.name)
        assertTrue(opened.file.readBytes().contentEquals(jpeg))
    }
}
