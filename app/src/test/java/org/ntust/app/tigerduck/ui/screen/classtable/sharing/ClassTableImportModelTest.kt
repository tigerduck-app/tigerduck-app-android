@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.ImportJob
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.Prompt
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.ScanAnswer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** The import's steps over a real store: which prompt a code raises, the lookup, and what is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableImportModelTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private var storeFile = ImportedClassTableStore.fileIn(context)
    private val account = "B11300000"
    private val store by lazy { ImportedClassTableStore({ storeFile }, { account }, Dispatchers.Unconfined) }
    private var current = true

    private val sharedAt = Instant.parse("2026-10-14T03:20:00Z")
    private val mingCode = ClassTableShareCode.encode(
        "B11300001", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001", "MA1010001"))),
    )!!
    private val calculus = Course(courseNo = "MA1010001", courseName = "Calculus")

    private fun TestScope.model(
        lookup: suspend (String, List<String>) -> List<Course> = { _, _ -> listOf(calculus) },
    ) = ClassTableImportModel(store, { account.lowercase() }, { current }, lookup, this)

    @Test
    fun `another app's QR code is passed over while scanning, and a damaged share code is refused`() = runTest {
        val model = model()

        assertFalse(model.scanned("https://example.com"))
        assertTrue(model.state.value.isIdle)

        assertTrue(model.scanned(mingCode.dropLast(1) + "A"))
        assertEquals(Prompt.Invalid, model.state.value.prompt)
        // Nothing else is taken while the prompt is up.
        assertFalse(model.scanned(mingCode))
    }

    @Test
    fun `a picture opens its first share code, or says why it has none`() = runTest {
        val model = model()

        model.imageRead(listOf("https://example.com"))
        assertEquals(Prompt.NoCode, model.state.value.prompt)
        model.dismissPrompt()

        model.imageRead(listOf("https://example.com", "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/1.X"))
        assertEquals(Prompt.Invalid, model.state.value.prompt)
        model.dismissPrompt()

        model.imageRead(listOf("https://example.com", mingCode.lowercase()))
        assertEquals("B11300001", model.state.value.pending?.studentId)
        // The nickname starts as the student ID.
        assertEquals("B11300001", model.state.value.nickname)
    }

    @Test
    fun `a code from a newer app asks for an update, whether a version or a type this one does not know`() = runTest {
        val newer = listOf(
            "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/2.B11300001",
            "https://share.tigerduck.app/FRIEND/1.B11300001",
        )
        newer.forEach { text ->
            val model = model()
            assertTrue(text, model.scanned(text))
            assertEquals(text, Prompt.NeedsUpdate, model.state.value.prompt)
        }

        // Asked of a picture too, before a damaged code beside it.
        val picture = model()
        picture.imageRead(listOf("https://example.com", "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/1.X", newer[1]))
        assertEquals(Prompt.NeedsUpdate, picture.state.value.prompt)
    }

    @Test
    fun `the user's own code is refused`() = runTest {
        val own = ClassTableShareCode.encode(account, sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001"))))!!
        val model = model()

        model.imageRead(listOf(own))

        assertEquals(Prompt.OwnCode, model.state.value.prompt)
        assertNull(model.state.value.pending)
    }

    @Test
    fun `an import saves the person under the nickname with the courses looked up, and shows them`() = runTest {
        val asked = mutableListOf<Pair<String, List<String>>>()
        val model = model { semester, courseNos -> asked += semester to courseNos; listOf(calculus) }
        model.imageRead(listOf(mingCode))

        model.setNickname("  ")
        model.importPending()
        assertFalse(model.state.value.isLookingUp)
        model.setNickname("Ming")
        model.importPending()
        advanceUntilIdle()

        assertEquals(listOf("1151" to listOf("CS1010001", "MA1010001")), asked)
        assertTrue(model.state.value.isFinished)
        val person = store.tables.value.shownPerson!!
        assertEquals("Ming", person.nickname)
        assertEquals(listOf("Calculus"), person.courses("1151").map { it.courseName })
    }

    @Test
    fun `a student ID imported already asks to update it, which keeps its nickname`() = runTest {
        model().run {
            imageRead(listOf(mingCode))
            setNickname("Ming")
            importPending()
        }
        advanceUntilIdle()
        val model = model()

        model.imageRead(listOf(mingCode))
        val prompt = model.state.value.prompt as Prompt.Replace
        assertEquals("Ming", prompt.job.nickname)
        model.lookUp(prompt.job)
        advanceUntilIdle()

        assertTrue(model.state.value.isFinished)
        assertEquals(listOf("Ming"), store.tables.value.people.map { it.nickname })
    }

    @Test
    fun `a failed lookup saves nothing and offers a retry, which imports`() = runTest {
        var fails = true
        val model = model { _, _ -> if (fails) throw IOException("offline") else listOf(calculus) }
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()

        val prompt = model.state.value.prompt as Prompt.LookupFailed
        assertEquals(ImportJob(model.state.value.pending!!, "B11300001"), prompt.job)
        assertTrue(store.tables.value.people.isEmpty())

        fails = false
        model.lookUp(prompt.job)
        advanceUntilIdle()
        assertTrue(model.state.value.isFinished)
        assertEquals(1, store.tables.value.people.size)
    }

    @Test
    fun `a failed save keeps the confirm step and offers a retry`() = runTest {
        val obstacle = File(context.noBackupFilesDir, "obstacle").apply { parentFile!!.mkdirs(); writeText("") }
        storeFile = File(obstacle, "imported_class_tables.json")
        val model = model()
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()

        assertTrue(model.state.value.prompt is Prompt.SaveFailed)
        assertEquals("B11300001", model.state.value.pending?.studentId)
        assertFalse(model.state.value.isFinished)

        obstacle.delete()
        model.lookUp((model.state.value.prompt as Prompt.SaveFailed).job)
        advanceUntilIdle()
        assertTrue(model.state.value.isFinished)
    }

    @Test
    fun `a lookup that ends after a sign-out saves nothing and closes the import`() = runTest {
        val answer = CompletableDeferred<List<Course>>()
        val model = model { _, _ -> answer.await() }
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()

        current = false
        answer.complete(listOf(calculus))
        advanceUntilIdle()

        assertTrue(model.state.value.isFinished)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a cancelled lookup saves nothing even when its answer comes later`() = runTest {
        val answer = CompletableDeferred<List<Course>>()
        val model = model { _, _ -> answer.await() }
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()
        assertTrue(model.state.value.isLookingUp)

        model.cancel()
        answer.complete(listOf(calculus))
        advanceUntilIdle()

        assertTrue(model.state.value.isIdle)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a picture is read while nothing else can start, and its codes then open`() = runTest {
        val picture = CompletableDeferred<List<String>?>()
        val model = model()

        model.readImage { picture.await() }
        advanceUntilIdle()
        assertTrue(model.state.value.isReadingImage)
        assertFalse(model.scanned(mingCode))

        picture.complete(null)
        advanceUntilIdle()
        assertEquals(Prompt.NoCode, model.state.value.prompt)
    }

    @Test
    fun `the confirm step lists the student ID, the terms, the course count and the share time`() {
        val code = ClassTableShareCode.decode(mingCode)

        val rows = importConfirmRows(code, { it.joinToString(", ") }, { it.toString() }, { it.toString() })

        assertEquals(
            listOf(
                ImportConfirmRow(R.string.sign_in_student_id, "B11300001"),
                ImportConfirmRow(R.string.class_table_semester_picker_label, "115-1"),
                ImportConfirmRow(R.string.class_table_import_course_count, "2"),
                ImportConfirmRow(R.string.class_table_import_shared_at, sharedAt.toString()),
            ),
            rows,
        )
        val tat = ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT11142         CS1010001")
        assertEquals(
            listOf(R.string.class_table_semester_picker_label, R.string.class_table_import_course_count),
            importConfirmRows(tat, { it.joinToString() }, { it.toString() }, { "" }).map { it.title },
        )
    }

    @Test
    fun `a picture that fails to read says it has no code, and the import goes on`() = runTest {
        val model = model()

        model.readImage { throw IllegalStateException("decoder gave up") }
        advanceUntilIdle()

        assertEquals(Prompt.NoCode, model.state.value.prompt)
        assertFalse(model.state.value.isReadingImage)
    }

    @Test
    fun `cancel while the person is being saved changes nothing, and the import ends saved and shown`() = runTest {
        // The store writes on a dispatcher of its own, held until the test lets it run.
        val storeThread = HeldDispatcher()
        val slowStore = ImportedClassTableStore({ storeFile }, { account }, storeThread)
        val model = ClassTableImportModel(slowStore, { account }, { current }, { _, _ -> listOf(calculus) }, this)
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()
        assertTrue(model.state.value.isLookingUp)

        // The lookup is done and the write has begun: too late to call it off.
        model.cancel()
        storeThread.release()
        advanceUntilIdle()

        assertTrue(model.state.value.isFinished)
        val saved = slowStore.tables.value
        assertEquals(listOf("B11300001"), saved.people.map { it.nickname })
        assertEquals(saved.people.single().id, saved.shownPersonId)
    }

    /**
     * A link from a reminder, a bulletin or School Mail opens another page while the person is
     * being written, which nothing stops: the import stays until it ends saved, and then leaves the
     * link's page on screen rather than show the class table over it.
     */
    @Test
    fun `a link while the person is being written leaves the import to end saved, off the class table`() = runTest {
        val storeThread = HeldDispatcher()
        val slowStore = ImportedClassTableStore({ storeFile }, { account }, storeThread)
        val model = ClassTableImportModel(slowStore, { account }, { current }, { _, _ -> listOf(calculus) }, this)
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()
        assertTrue(model.state.value.isSaving)

        // Read before the write is let go, and checked after: a write held for good would hold
        // the test with it.
        val stays = model.staysForLink()
        storeThread.release()
        advanceUntilIdle()

        assertTrue(stays)
        assertTrue(model.state.value.isFinished)
        assertTrue(model.state.value.leftForLink)
        assertEquals(listOf("B11300001"), slowStore.tables.value.people.map { it.nickname })
    }

    /** The import stays over the link's page then, so a write that fails still says so, with a retry. */
    @Test
    fun `a write that fails after a link says so`() = runTest {
        val obstacle = File(context.noBackupFilesDir, "obstacle").apply { parentFile!!.mkdirs(); writeText("") }
        storeFile = File(obstacle, "imported_class_tables.json")
        val storeThread = HeldDispatcher()
        val slowStore = ImportedClassTableStore({ storeFile }, { account }, storeThread)
        val model = ClassTableImportModel(slowStore, { account }, { current }, { _, _ -> listOf(calculus) }, this)
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()

        val stays = model.staysForLink()
        storeThread.release()
        advanceUntilIdle()

        assertTrue(stays)
        assertTrue(model.state.value.prompt is Prompt.SaveFailed)
        assertFalse(model.state.value.isFinished)
        obstacle.delete()
    }

    /** Before the write, a link calls the import off as its Close does. */
    @Test
    fun `a link before the person is being written does not keep the import`() = runTest {
        val model = model()
        model.imageRead(listOf(mingCode))

        assertFalse(model.staysForLink())
        assertFalse(model.state.value.leftForLink)
    }

    // --- What the camera hears of a code it read (#493) ---

    /** Codes of fake students, of year 000. */
    private val fakeA = ClassTableShareCode.encode(
        "B00015001", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001"))),
    )!!
    private val fakeB = ClassTableShareCode.encode(
        "B00015002", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("MA1010001"))),
    )!!

    @Test
    fun `the camera hears whether a code was taken, is not a share code, waits, or is the one the import holds`() = runTest {
        val model = model()

        assertEquals(ScanAnswer.NOT_SHARE_CODE, model.scan("https://example.com"))
        assertEquals(ScanAnswer.TAKEN, model.scan(fakeA))
        // A's confirm step is open: A, in any form, is the code it holds, and B waits for it.
        assertEquals(ScanAnswer.HOLDING, model.scan(fakeA))
        assertEquals(ScanAnswer.HOLDING, model.scan(" ${fakeA.removePrefix("HTTPS://").lowercase()}?x=1"))
        assertEquals(ScanAnswer.BUSY, model.scan(fakeB))
        assertEquals(ScanAnswer.NOT_SHARE_CODE, model.scan("https://example.com"))

        // Called off, the import holds A no more: it is taken again.
        model.cancel()
        assertEquals(ScanAnswer.TAKEN, model.scan(fakeA))
    }

    @Test
    fun `a damaged code's prompt holds that code, and a picture's code is the one held after it`() = runTest {
        val model = model()
        val damaged = fakeA.dropLast(1) + if (fakeA.last() == '0') '1' else '0'

        assertEquals(ScanAnswer.TAKEN, model.scan(damaged))
        assertEquals(ScanAnswer.HOLDING, model.scan(damaged))
        assertEquals(ScanAnswer.BUSY, model.scan(fakeA))
        model.dismissPrompt()

        assertEquals(ScanAnswer.TAKEN, model.scan(fakeA))
        model.cancel()
        // A picture's code opens: it is the one held, not A, which the camera took before.
        model.imageRead(listOf(fakeB))
        assertEquals(ScanAnswer.BUSY, model.scan(fakeA))
        assertEquals(ScanAnswer.HOLDING, model.scan(fakeB))
    }
}

/** Holds what is dispatched to it until [release] runs it, as a busy I/O thread would. */
private class HeldDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        held.addLast(block)
    }

    fun release() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }
}
