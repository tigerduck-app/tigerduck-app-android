package org.ntust.app.tigerduck.ui.screen.coursemoodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDownloadState
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import org.ntust.app.tigerduck.data.moodle.MoodleFileOwner
import org.ntust.app.tigerduck.data.moodle.MoodleFileRef
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.io.File
import java.lang.reflect.Type
import org.ntust.app.tigerduck.util.JvmContext

/** Only the latest tap, on a screen still in view, opens what finishes downloading. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleFileOpenerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    private val api = object : MoodleCourseApi {
        override suspend fun functions() = emptySet<String>()
        override suspend fun forums(courseIds: List<Int>) = emptyList<MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(null, emptyList())
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: MoodleFile, target: File, maxBytes: Long, progress: (Float) -> Unit) {
            target.writeText(file.name)
            gates.getOrPut(file.name) { CompletableDeferred() }.await()
        }
    }

    private fun ref(name: String) = MoodleFileRef(
        MoodleFile(
            name = name, size = 4, mimeType = "application/pdf", modified = 1L, path = "/",
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_resource/content/1/$name",
        ),
        MoodleFileOwner.Module(1),
    )

    private val presented = mutableListOf<Pair<String, Boolean>>()

    private fun kotlinx.coroutines.test.TestScope.opener(): Pair<MoodleFileOpener, MoodleCourseStore> {
        val store = MoodleCourseStore(
            courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
            context = MoodleCacheContext(
                "B1",
                object : MoodleCachePort {
                    override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
                    override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
                },
                isCurrent = { true },
            ),
            scope = backgroundScope,
            downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined),
            filesDir = File(tmp.root, "acct/5"),
        )
        val opener = MoodleFileOpener(store, JvmContext(), backgroundScope, { "system" }) { copy, _, share ->
            presented += copy.name to share
        }
        opener.isOnScreen = true
        return opener to store
    }

    @Test
    fun `of two files tapped while they download, only the second opens`() = runTest(UnconfinedTestDispatcher()) {
        val (files, _) = opener()
        files.tap(ref("a.pdf"), MoodleDownloadState.NotDownloaded)
        files.tap(ref("b.pdf"), MoodleDownloadState.NotDownloaded)

        gates.getValue("a.pdf").complete(Unit)
        runCurrent()
        assertTrue("the earlier tap opens nothing", presented.isEmpty())

        gates.getValue("b.pdf").complete(Unit)
        runCurrent()
        assertEquals(listOf("b.pdf" to false), presented)
    }

    @Test
    fun `a file that lands once the screen is out of view, or after a link was opened, opens nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val (files, _) = opener()
            files.tap(ref("a.pdf"), MoodleDownloadState.NotDownloaded)
            files.isOnScreen = false
            gates.getValue("a.pdf").complete(Unit)
            runCurrent()

            files.isOnScreen = true
            files.tap(ref("b.pdf"), MoodleDownloadState.NotDownloaded)
            files.forgetPendingOpen()
            gates.getValue("b.pdf").complete(Unit)
            runCurrent()

            assertTrue(presented.isEmpty())
        }

    @Test
    fun `a share hands over the copy and stops a tap's file opening after it`() = runTest(UnconfinedTestDispatcher()) {
        val (files, store) = opener()
        files.tap(ref("a.pdf"), MoodleDownloadState.NotDownloaded)
        files.share(ref("b.pdf"))
        gates.getValue("a.pdf").complete(Unit)
        gates.getValue("b.pdf").complete(Unit)
        runCurrent()

        assertEquals(listOf("b.pdf" to true), presented)
        // A copy already there opens at once, and a tap on one downloading stops it.
        files.tap(ref("a.pdf"), store.downloadState(ref("a.pdf"), store.downloads!!.snapshot.value))
        assertEquals("a.pdf" to false, presented.last())
    }
}
