package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.lang.reflect.Type

/** One store per account and course, dropped at sign-out, on the demo account's Moodle in demo mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleCourseRepositoryTest {

    private class Api(val grade: String) : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.GRADE_ITEMS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(null, emptyList())
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) =
            listOf(MoodleGradeItem(1, "HW", "mod", "assign", 2, grade, 100.0, false, ""))
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        var downloadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) {
            target.writeText("half")
            downloadGate?.await()
        }
    }

    private val authState = MutableStateFlow(true)
    private var account: String? = "B11300001"
    private var demo = false
    private val live = Api("live")
    private var demoGrade = "demo"
    private val languageChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val cachedFor = mutableListOf<String>()

    private val noCache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private fun TestScope.repository(liveApi: MoodleCourseApi = live) = MoodleCourseRepository(
        signedIn = { authState.value },
        account = { account },
        isDemo = { demo },
        liveApi = liveApi,
        demoApi = { Api(demoGrade) },
        idMap = { mapOf("1151CS3005301" to 5) },
        cachePort = { account -> cachedFor += account; noCache },
        picturesDir = { null },
        downloads = null,
        downloadsDir = { null },
        decode = { null },
        authState = authState,
        appScope = backgroundScope,
        newScope = { kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob()) },
        languageChanges = languageChanges,
    )

    @Test
    fun `one store per account and course`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()

        val first = repository.store(5)
        assertSame(first, repository.store(5))
        assertNotSame(first, repository.store(6))

        account = "B11300002"
        val other = repository.store(5)
        assertNotSame("another account never gets the first one's store", first, other)
        assertFalse("and the first one's store knows it is no longer current", first!!.isCurrent())
        assertTrue(other!!.isCurrent())
    }

    @Test
    fun `a picture changed in one course has the account's other courses fetch their members again`() =
        runTest(UnconfinedTestDispatcher()) {
            val api = object : MoodleCourseApi by live {
                override suspend fun functions() = setOf(MoodleCourseApi.MEMBERS_FUNCTION, MoodleCourseApi.UPDATE_PICTURE_FUNCTION)
                override suspend fun changePicture(jpeg: ByteArray) = Unit
            }
            val repository = repository(api)
            val here = repository.store(5)!!
            val other = repository.store(6)!!
            other.refreshMembers()
            assertTrue(other.members.state.value.fetchedAtMs!! > 0)

            assertNull(here.changePicture { byteArrayOf(1) })

            // Out of date, so its members screen fetches them at once.
            assertEquals(0L, other.members.state.value.fetchedAtMs)
            assertTrue("this course fetched its own anew", here.members.state.value.fetchedAtMs!! > 0)
        }

    @Test
    fun `sign-out drops every store, and none is handed out signed out`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        val before = repository.store(5)

        authState.value = false
        assertNull(repository.store(5))
        assertFalse(before!!.isCurrent())

        authState.value = true
        assertNotSame("signing back in builds anew", before, repository.store(5))
    }

    @Test
    fun `the demo account's stores answer from the demo Moodle`() = runTest(UnconfinedTestDispatcher()) {
        demo = true
        val store = repository().store(5)!!

        store.refreshGrades()

        assertEquals("demo", store.grades.value!!.items.single().grade)
    }

    @Test
    fun `a real account's stores answer from Moodle`() = runTest(UnconfinedTestDispatcher()) {
        val store = repository().store(5)!!

        store.refreshGrades()

        assertEquals("live", store.grades.value!!.items.single().grade)
    }

    @Test
    fun `a store from before a sign-out is not current after signing back in as the same account`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        val before = repository.store(5)!!

        authState.value = false
        authState.value = true

        assertFalse("its fetches were stopped with the sign-out", before.isCurrent())
        assertTrue(repository.store(5)!!.isCurrent())
    }

    @Test
    fun `the demo account's Moodle follows a change of language, in stores already built`() = runTest(UnconfinedTestDispatcher()) {
        demo = true
        val repository = repository()
        val store = repository.store(5)!!
        store.refreshGrades()
        assertEquals("demo", store.grades.value!!.items.single().grade)

        demoGrade = "示範"
        languageChanges.emit(Unit)

        assertEquals("示範", store.grades.value!!.items.single().grade)
        store.refreshGrades()
        assertEquals("and stays so", "示範", store.grades.value!!.items.single().grade)
    }

    @Test
    fun `the demo account's copies stay in memory, so a restart shows the language the app has then`() = runTest(UnconfinedTestDispatcher()) {
        demo = true
        repository().store(5)

        assertTrue("nothing kept on disk for the demo account", cachedFor.isEmpty())

        demo = false
        repository().store(5)
        assertEquals(listOf("B11300001"), cachedFor)
    }

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `sign-out stops every download, so none lands for the account that left`() = runTest(UnconfinedTestDispatcher()) {
        live.downloadGate = kotlinx.coroutines.CompletableDeferred()
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope)
        val repository = MoodleCourseRepository(
            signedIn = { authState.value },
            account = { account },
            isDemo = { false },
            liveApi = live,
            demoApi = { null },
            idMap = { emptyMap() },
            cachePort = { noCache },
            picturesDir = { null },
            downloads = downloads,
            downloadsDir = { java.io.File(tmp.root, "account") },
            decode = { null },
            authState = authState,
            appScope = backgroundScope,
            newScope = { kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob()) },
        )
        val store = repository.store(5)!!
        val file = MoodleFile(
            name = "hw.pdf", size = 4, mimeType = "application/pdf", modified = 1L, path = "/",
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_forum/attachment/9/hw.pdf",
        )
        val opening = backgroundScope.async { store.download(MoodleFileRef(file, MoodleFileOwner.Post(9))) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { downloads.snapshot.first { it.progress.isNotEmpty() } }
        }

        authState.value = false

        assertEquals(MoodleAttachment.Dropped, opening.await())
        // The download ends before the sign-out's wipe does: store() waits for the lock the
        // sign-out holds until its wipe is done, and hands out nothing once it is.
        assertNull(repository.store(5))
        assertTrue(downloads.snapshot.value.progress.isEmpty())
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `sign-out deletes the account's downloaded files, once its downloads have stopped`() = runTest(UnconfinedTestDispatcher()) {
        // Every step on the disk runs where it is called, so the wipe is done once the sign-out is.
        val direct = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
        }
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = direct)
        val repository = MoodleCourseRepository(
            signedIn = { authState.value },
            account = { account },
            isDemo = { false },
            liveApi = live,
            demoApi = { null },
            idMap = { emptyMap() },
            cachePort = { noCache },
            picturesDir = { null },
            downloads = downloads,
            downloadsDir = { java.io.File(tmp.root, "account") },
            decode = { null },
            authState = authState,
            appScope = backgroundScope,
            newScope = { kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob()) },
        )
        val store = repository.store(5)!!
        val hw = MoodleFile(
            name = "hw.pdf", size = 4, mimeType = "application/pdf", modified = 1L, path = "/",
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_forum/attachment/9/hw.pdf",
        )
        val kept = (store.download(MoodleFileRef(hw, MoodleFileOwner.Post(9))) as MoodleAttachment.Local).file
        live.downloadGate = kotlinx.coroutines.CompletableDeferred()
        val notes = hw.copy(name = "notes.pdf", url = hw.url.replace("hw.pdf", "notes.pdf"))
        val opening = backgroundScope.async { store.download(MoodleFileRef(notes, MoodleFileOwner.Post(9))) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { downloads.snapshot.first { it.progress.isNotEmpty() } }
        }
        assertTrue(kept.isFile)

        authState.value = false

        assertEquals(MoodleAttachment.Dropped, opening.await())
        // As above: done once the sign-out's lock is free, not when the stopped download is.
        assertNull(repository.store(5))
        assertFalse("the copy downloaded before the sign-out stayed", kept.exists())
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }
}
