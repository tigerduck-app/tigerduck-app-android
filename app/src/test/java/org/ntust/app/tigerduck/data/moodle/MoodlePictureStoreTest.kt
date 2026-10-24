package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.lang.reflect.Type

/**
 * The account's own picture (#446): a change Moodle takes fetches the members again, so the list
 * and the page show the new picture; one it refuses leaves the old picture and says why.
 */
class MoodlePictureStoreTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() = scope.cancel()

    private var revision = 1
    private var refusal: Exception? = null
    private val uploads = mutableListOf<ByteArray>()

    /** Holds the next members fetch, with the members as they were when it was asked, until completed. */
    private var heldFetch: CompletableDeferred<Unit>? = null

    private var encoded = 0

    /** Holds the upload until completed. */
    private var heldUpload: CompletableDeferred<Unit>? = null

    private fun me() = MoodleMember(
        id = 3, fullName = "B00015999 @ Student Three", idNumber = null, email = null,
        groups = emptyList(), roles = listOf(MoodleMemberRole("student", "", 0)),
        pictureUrl = "https://moodle2.ntust.edu.tw/pluginfile.php/3/user/icon/f1?rev=$revision",
        description = "", interests = emptyList(), fields = emptyList(),
    )

    private val api = object : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.MEMBERS_FUNCTION, MoodleCourseApi.UPDATE_PICTURE_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int): MoodleMemberList {
            val asked = MoodleMemberList(3, listOf(me()))
            heldFetch?.let { heldFetch = null; it.await() }
            return asked
        }
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
        override suspend fun changePicture(jpeg: ByteArray) {
            heldUpload?.await()
            refusal?.let { throw it }
            uploads += jpeg
            revision++
        }
    }

    private val cache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    // Encoding and decoding on the test's dispatcher too, so its clock covers them.
    private fun store() = MoodleCourseStore(
        5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache, { true }), scope, background = dispatcher,
    )

    @Test
    fun `a picture Moodle takes is fetched with the members again`() = runTest(dispatcher) {
        val store = store()
        store.refreshMembers()
        val jpeg = byteArrayOf(1, 2, 3)

        val failure = store.changePicture { jpeg }

        assertNull(failure)
        assertEquals(listOf(jpeg.toList()), uploads.map { it.toList() })
        assertTrue(store.members.value!!.members.single().pictureUrl!!.endsWith("rev=2"))
        assertFalse(store.changingPicture.value)
    }

    @Test
    fun `a picture Moodle refuses leaves the old one and says why`() = runTest(dispatcher) {
        val store = store()
        store.refreshMembers()
        refusal = MoodleWebserviceError.MalformedResponse("upload_error_ini_size")

        val failure = store.changePicture { byteArrayOf(1, 2, 3) }

        assertEquals(refusal, failure)
        assertTrue(store.members.value!!.members.single().pictureUrl!!.endsWith("rev=1"))
        assertFalse(store.changingPicture.value)
    }

    @Test
    fun `a members fetch already running when the picture changes does not stand for the one after it`() = runTest(dispatcher) {
        val store = store()
        heldFetch = CompletableDeferred()
        val hold = heldFetch!!
        launch { store.refreshMembers() }
        runCurrent()

        val change = async { store.changePicture { byteArrayOf(1) } }
        runCurrent()
        hold.complete(Unit)
        advanceUntilIdle()

        assertNull(change.await())
        assertTrue(store.members.value!!.members.single().pictureUrl!!.endsWith("rev=2"))
    }

    @Test
    fun `a change left by its page goes on, and the members are fetched again`() = runTest(dispatcher) {
        val store = store()
        store.refreshMembers()
        heldUpload = CompletableDeferred()
        // Left before the photo was even encoded: that goes in the account's scope too.
        val page = launch { store.changePicture { encoded++; byteArrayOf(1) } }
        runCurrent()

        page.cancel()
        heldUpload!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, encoded)
        assertTrue(store.members.value!!.members.single().pictureUrl!!.endsWith("rev=2"))
        assertFalse(store.changingPicture.value)
    }

    @Test
    fun `the picture can be changed only where the site allows it`() = runTest(dispatcher) {
        assertTrue(store().canChangePicture())
    }
}
