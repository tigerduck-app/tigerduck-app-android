package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.coursecatalog.CatalogFixture
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogAnswer
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogFiles
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogStore
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogUnavailableException
import java.io.IOException

/**
 * The sheet's search over the stored and fetched catalogs: the stored copy answers first, one
 * revalidation per sheet, a wait for the backend only while nothing is stored, the search error in
 * place of "not found", and Enter. As the iPhone's `CourseSearchTests` model cases; a fake backend
 * answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseSearchModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A backend that logs each language and ETag it is asked with, waits for [gate], then answers. */
    private class Backend(
        private val gate: CompletableDeferred<Unit>? = null,
        private val answer: (language: String) -> CourseCatalogAnswer,
    ) {
        val sent = mutableListOf<String>()
        val fetch = CourseCatalogStore.Fetch { semester, language, etag ->
            assertEquals("1151", semester)
            sent += "$language ${etag ?: "-"}"
            gate?.await()
            answer(language)
        }
    }

    private fun files() = CourseCatalogFiles(folder.newFolder())

    private fun TestScope.model(
        files: CourseCatalogFiles,
        label: suspend (List<org.ntust.app.tigerduck.network.model.CourseSearchResult>, String) ->
            List<org.ntust.app.tigerduck.network.model.CourseSearchResult> = { rows, _ -> rows },
    ): CourseSearchModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CourseSearchModel(backgroundScope, files, label, io = dispatcher, compute = dispatcher)
    }

    private fun CourseSearchModel.found() = results.mapValues { (_, rows) -> rows.map { it.courseNo } }

    @Test
    fun `the stored catalog is searched before the backend answers, a 304 keeps it and a 200 replaces it`() = runTest {
        val files = files()
        val storedZh = CatalogFixture.stored("zh", "W/\"zh1\"")
        files.save(storedZh, "1151", "zh")
        files.save(CatalogFixture.stored("en", "W/\"en1\""), "1151", "en")
        val renamed = CatalogFixture.rows("en").map { if (it.courseNo == "CS1001301") it.copy(name = "Computing 101") else it }
        val freshEn = CatalogFixture.stored("en", "W/\"en2\"", renamed)
        val gate = CompletableDeferred<Unit>()
        val backend = Backend(gate) { if (it == "zh") CourseCatalogAnswer.NotModified else CourseCatalogAnswer.Changed(freshEn) }
        val model = model(files)

        model.search("CS10", "1151", backend.fetch)
        assertEquals(listOf("CS1001301", "CS1002301"), model.results["zh"]?.map { it.courseNo })
        assertEquals("Introduction to Computers", model.results["en"]?.first()?.courseName)
        assertEquals(listOf("A"), model.options.dimensions.map { it.code })
        assertFalse(model.isSearching)

        val revision = model.searchAgainCount
        runCurrent()
        assertEquals(setOf("zh W/\"zh1\"", "en W/\"en1\""), backend.sent.toSet())
        gate.complete(Unit)
        runCurrent()
        assertNotEquals(revision, model.searchAgainCount)
        assertEquals(storedZh, files.load("1151", "zh"))
        assertEquals(freshEn, files.load("1151", "en"))

        model.search("computing", "1151", backend.fetch)
        assertEquals(mapOf("en" to listOf("CS1001301")), model.found())
        assertEquals(2, backend.sent.size)

        val nextSheet = model(files)
        val next = Backend { CourseCatalogAnswer.NotModified }
        nextSheet.search("CS", "1151", next.fetch)
        runCurrent()
        assertEquals(setOf("zh W/\"zh1\"", "en W/\"en2\""), next.sent.toSet())
    }

    @Test
    fun `with nothing stored, a search waits for the catalog, stores it and has the app-language row`() = runTest {
        val files = files()
        val gate = CompletableDeferred<Unit>()
        val backend = Backend(gate) { CourseCatalogAnswer.Changed(CatalogFixture.stored(it, "W/\"$it\"")) }
        val model = model(files)

        val search = launch { model.search("data", "1151", backend.fetch) }
        runCurrent()
        assertEquals(2, backend.sent.size)
        assertTrue(model.isSearching)
        assertNull(model.failure)
        model.submit()
        assertEquals(0, model.searchAgainCount)

        gate.complete(Unit)
        search.join()
        assertFalse(model.isSearching)
        assertEquals(mapOf("en" to listOf("CS1002301")), model.found())
        assertEquals(0, model.searchAgainCount)
        assertEquals("W/\"zh\"", files.load("1151", "zh")?.etag)
        assertEquals("W/\"en\"", files.load("1151", "en")?.etag)

        assertEquals("資料結構", model.row("CS1002301", "zh")?.courseName)
        assertNull(model.row("CS9999301", "zh"))
        assertEquals(2, backend.sent.size)
    }

    @Test
    fun `with no catalog to be had, a search shows the error instead of not found`() = runTest {
        for (error in listOf(CourseCatalogUnavailableException(), IOException("offline"), NotSignedInException())) {
            val model = model(files())
            val backend = Backend { throw error }

            model.search("CS", "1151", backend.fetch)
            assertSame(error, model.failure)
            assertTrue(model.results.isEmpty())
            assertFalse(model.isSearching)

            model.search(" ", "1151", backend.fetch)
            assertNull(model.failure)
            assertFalse(model.hasSearched)
        }
    }

    @Test
    fun `the search error names a lost connection apart from a missing catalog`() {
        assertEquals(R.string.error_network_unavailable, searchFailureReason(IOException("offline")))
        assertEquals(R.string.error_courses_unavailable, searchFailureReason(CourseCatalogUnavailableException()))
        assertEquals(R.string.error_courses_unavailable, searchFailureReason(NotSignedInException()))
    }

    @Test
    fun `Enter searches again only to retry a failed search`() = runTest {
        var online = false
        val model = model(files())
        val backend = Backend {
            if (!online) throw IOException("offline")
            CourseCatalogAnswer.Changed(CatalogFixture.stored(it, "W/\"1\""))
        }

        model.search("CS", "1151", backend.fetch)
        assertNotNull(model.failure)
        model.search("CS1", "1151", backend.fetch)
        assertEquals(2, backend.sent.size)

        online = true
        model.submit()
        assertEquals(1, model.searchAgainCount)
        model.search("CS1", "1151", backend.fetch)
        assertNull(model.failure)
        assertEquals(listOf("CS1001301", "CS1002301"), model.results["zh"]?.map { it.courseNo })
        assertEquals(4, backend.sent.size)

        model.submit()
        assertEquals(1, model.searchAgainCount)
        assertEquals(4, backend.sent.size)
    }

    @Test
    fun `Enter also fetches again a language that failed while the other loaded`() = runTest {
        var online = false
        val model = model(files())
        val backend = Backend { language ->
            if (language != "zh" && !online) throw IOException("offline")
            CourseCatalogAnswer.Changed(CatalogFixture.stored(language, "W/\"1\""))
        }

        model.search("data", "1151", backend.fetch)
        assertNull(model.failure)
        assertTrue(model.results.isEmpty())

        online = true
        model.submit()
        assertEquals(1, model.searchAgainCount)
        model.search("data", "1151", backend.fetch)
        runCurrent()
        // The en catalog came in beside the zh one searched already, so the search runs again.
        assertEquals(2, model.searchAgainCount)
        model.search("data", "1151", backend.fetch)
        assertEquals(mapOf("en" to listOf("CS1002301")), model.found())
        assertEquals(setOf("zh W/\"1\"", "en -"), backend.sent.drop(2).toSet())

        model.submit()
        assertEquals(2, model.searchAgainCount)
    }

    @Test
    fun `with nothing stored, a search started after one language answered waits for the other`() = runTest {
        val files = files()
        val enAnswer = CompletableDeferred<Unit>()
        val model = model(files)
        val fetch = CourseCatalogStore.Fetch { _, language, _ ->
            if (language == "en") enAnswer.await()
            CourseCatalogAnswer.Changed(CatalogFixture.stored(language, "W/\"1\""))
        }

        val first = launch { model.search("dat", "1151", fetch) }
        runCurrent()
        assertNotNull(files.load("1151", "zh"))
        first.cancel()
        val second = launch { model.search("data", "1151", fetch) }
        runCurrent()
        assertTrue(model.isSearching)

        enAnswer.complete(Unit)
        second.join()
        assertEquals(mapOf("en" to listOf("CS1002301")), model.found())
        assertEquals(0, model.searchAgainCount)
    }

    @Test
    fun `rows show as the app labels them and are still found by what the backend sent`() = runTest {
        val files = files()
        files.save(CatalogFixture.stored("zh", "W/\"1\""), "1151", "zh")
        files.save(CatalogFixture.stored("en", "W/\"1\""), "1151", "en")
        val model = model(files) { rows, language ->
            if (language == "en") rows.map { it.copy(courseName = "Abbr. " + it.courseName) } else rows
        }
        val backend = Backend { CourseCatalogAnswer.NotModified }

        model.search("data", "1151", backend.fetch)
        assertEquals(listOf("Abbr. Data Structures"), model.results["en"]?.map { it.courseName })
        assertEquals("Abbr. Data Structures", model.row("CS1002301", "en")?.courseName)

        model.search("abbr", "1151", backend.fetch)
        assertTrue(model.results.isEmpty())
    }
}
