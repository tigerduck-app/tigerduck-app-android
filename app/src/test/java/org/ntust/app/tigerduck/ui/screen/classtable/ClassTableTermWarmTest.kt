package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filling the other terms' caches after the first fetch (tigerduck-app-android#265): every
 * catalogue term with no cache yet, one at a time, once per sign-in, and no further than the
 * school servers answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassTableTermWarmTest {

    private val catalogue = listOf("1152", "1151", "114H", "1142", "1141")
    private val cached = mutableSetOf<String>()
    private val fetched = mutableListOf<String>()
    private val failing = mutableSetOf<String>()
    private var mayFetch = true
    private var shown = "1152"

    private fun warm() = ClassTableTermWarm(
        terms = { catalogue },
        isCached = { it in cached },
        mayFetch = { mayFetch },
        shownTerm = { shown },
    )

    /** The fetch the view model hands in: caches the term, and says whether the school answered. */
    private suspend fun fetch(term: String, isCurrent: () -> Boolean = { true }): Boolean {
        fetched += term
        if (term in failing) return false
        if (isCurrent()) cached += term
        return true
    }

    @Test
    fun `a reset, or a pick and fetch, of the term while its warm is suspended makes the warm's result stale`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val current = mutableMapOf<String, Pair<Boolean, Boolean>>()
        val warm = warm()
        val job = launch {
            warm.run { term, isCurrent ->
                val before = isCurrent()
                if (term == "1151") gate.await()
                current[term] = before to isCurrent()
                fetch(term, isCurrent)
            }
        }
        advanceUntilIdle()
        // While 115-1's lookups run: the user resets it, or picks it and its own fetch starts.
        // Either wipes or refetches the term, and the warm's older rows must not land over that.
        warm.supersede("1151")
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals(true to false, current["1151"])
        assertEquals(true to true, current["114H"])
        assertEquals(setOf("114H", "1142", "1141"), cached)
    }

    @Test
    fun `a supersede of another term, or before the term's warm began, does not make it stale`() = runTest {
        val warm = warm()
        warm.supersede("1151")
        warm.run { term, isCurrent ->
            if (term == "1151") warm.supersede("114H")
            assertTrue("$term's warm counted as stale", isCurrent())
            fetch(term, isCurrent)
        }
        assertEquals(listOf("1151", "114H", "1142", "1141"), fetched)
    }

    @Test
    fun `every term with no cache is fetched, newest first, the shown term and the cached ones not`() = runTest {
        cached += "1142"
        warm().run(::fetch)
        assertEquals(listOf("1151", "114H", "1141"), fetched)
    }

    @Test
    fun `the terms are fetched one at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var mostInFlight = 0
        val job = launch {
            warm().run { term, _ ->
                inFlight++
                mostInFlight = maxOf(mostInFlight, inFlight)
                gate.await()
                inFlight--
                fetch(term)
            }
        }
        advanceUntilIdle()
        assertEquals(1, inFlight)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, mostInFlight)
        assertEquals(listOf("1151", "114H", "1142", "1141"), fetched)
        job.join()
    }

    @Test
    fun `a term the school did not answer ends the run`() = runTest {
        // The rest would fail the same way, and each failed lookup is a request the server still takes.
        failing += "114H"
        warm().run(::fetch)
        assertEquals(listOf("1151", "114H"), fetched)
    }

    @Test
    fun `the run is once per sign-in, and a sign-out lets the next account have one`() = runTest {
        val warm = warm()
        warm.run(::fetch)
        fetched.clear()
        cached.clear()
        warm.run(::fetch)
        assertEquals(emptyList<String>(), fetched)
        warm.reset()
        warm.run(::fetch)
        assertEquals(listOf("1151", "114H", "1142", "1141"), fetched)
    }

    @Test
    fun `nothing is fetched while offline, signed out or on the demo account, before or during the run`() = runTest {
        mayFetch = false
        warm().run(::fetch)
        assertEquals(emptyList<String>(), fetched)

        mayFetch = true
        warm().run { term, _ ->
            // The connection went after the first term.
            mayFetch = false
            fetch(term)
        }
        assertEquals(listOf("1151"), fetched)
    }

    @Test
    fun `a term cached meanwhile, or picked meanwhile, is left to its own fetch`() = runTest {
        warm().run { term, _ ->
            // Picking a term fetches it, and so may a cache written while this one ran.
            if (term == "1151") {
                shown = "114H"
                cached += "1142"
            }
            fetch(term)
        }
        assertEquals(listOf("1151", "1141"), fetched)
    }

    // --- Answers fetched once for every term (iOS WarmTermsTests, tigerduck-app#890) ---

    /**
     * Moodle and the transcript answer once for every term the warm fetches, for the account the
     * fetch began under. Answers that outlive a sign-out reach no term, and each term commits only
     * while that account is still the one signed in.
     */
    @Test
    fun `answers fetched for an account that signed out meanwhile reach no term`() = runTest {
        var account = "TEST00001"
        val warm = warm()
        val reached = mutableMapOf<String, String?>()

        warm.run(stillSignedIn = { account == "TEST00001" }, shared = {
            // Signed out, and in as someone else, while the score system answered.
            account = "TEST00002"
            "answers of TEST00001"
        }) { term, answers, _ ->
            reached[term] = answers
            fetch(term)
        }
        assertEquals(emptyMap<String, String?>(), reached)

        // The next account's own warm, after the sign-out.
        warm.reset()
        val current = mutableMapOf<String, Boolean>()
        warm.run(stillSignedIn = { account == "TEST00002" }, shared = { "answers of TEST00002" }) { term, answers, isCurrent ->
            reached[term] = answers
            // Signed out while 114-2's lookups ran: it must not commit, and the rest are not fetched.
            if (term == "1142") account = "TEST00003"
            current[term] = isCurrent()
            fetch(term, isCurrent)
        }
        assertEquals(listOf("1151", "114H", "1142"), reached.keys.toList())
        assertEquals(setOf<String?>("answers of TEST00002"), reached.values.toSet())
        assertEquals(mapOf("1151" to true, "114H" to true, "1142" to false), current)
        assertEquals(setOf("1151", "114H"), cached)
    }

    /** The answers are asked for once, and only when some term still needs them. */
    @Test
    fun `the shared answers are fetched once, and not at all when every term is cached`() = runTest {
        var asked = 0
        warm().run(stillSignedIn = { true }, shared = { asked++; "answers" }) { term, _, isCurrent -> fetch(term, isCurrent) }
        assertEquals(1, asked)
        assertEquals(listOf("1151", "114H", "1142", "1141"), fetched)

        asked = 0
        cached += catalogue
        warm().run(stillSignedIn = { true }, shared = { asked++; "answers" }) { term, _, isCurrent -> fetch(term, isCurrent) }
        assertEquals(0, asked)
    }

    /**
     * The transcript failed, so no term but the one course selection serves has a source: that
     * one is warmed from course selection as it was before the transcript, and the rest wait for
     * a later fetch, which does not find the run spent. Each term picks its source itself, so
     * every one is handed the missing answer.
     */
    @Test
    fun `without the shared answers a term that needs none is still warmed, and the rest wait for a later fetch`() = runTest {
        val served = "1151"
        val handed = mutableMapOf<String, String?>()
        /** Course selection's term needs no transcript; any other is the transcript's to build. */
        suspend fun fetchFrom(term: String, answers: String?, isCurrent: () -> Boolean): Boolean {
            handed[term] = answers
            if (term != served && answers == null) return true
            return fetch(term, isCurrent)
        }
        val warm = warm()
        warm.run<String>(stillSignedIn = { true }, shared = { null }) { term, answers, isCurrent -> fetchFrom(term, answers, isCurrent) }
        assertEquals(setOf(served), cached)
        assertEquals(mapOf<String, String?>(served to null, "114H" to null, "1142" to null, "1141" to null), handed)

        handed.clear()
        warm.run<String>(stillSignedIn = { true }, shared = { "answers" }) { term, answers, isCurrent -> fetchFrom(term, answers, isCurrent) }
        assertEquals(mapOf<String, String?>("114H" to "answers", "1142" to "answers", "1141" to "answers"), handed)
        assertEquals(setOf(served, "114H", "1142", "1141"), cached)
    }

    // --- The wait for the shared answers ---

    /**
     * The shared answers are asked for as the first term's turn comes, and take seconds. The
     * user can pick that term meanwhile and leave it before its own fetch lands: the pick
     * superseded the warm, whose older answers must not land over that fetch's.
     */
    @Test
    fun `a term picked while the shared answers are on their way makes its warm stale`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val warm = warm()
        val current = mutableMapOf<String, Boolean>()
        val job = launch {
            warm.run(stillSignedIn = { true }, shared = { gate.await(); "answers" }) { term, _, isCurrent ->
                current[term] = isCurrent()
                fetch(term, isCurrent)
            }
        }
        advanceUntilIdle()
        // Picked, its own fetch begun, and left again before that fetch landed.
        shown = "1151"
        warm.supersede("1151")
        shown = "1152"
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertTrue("115-1's warm still counts as current", current["1151"] != true)
        assertEquals(setOf("114H", "1142", "1141"), cached)
    }

    /** Picked, or cached by another fetch, while the shared answers were on their way: left to that fetch. */
    @Test
    fun `a term picked or cached while the shared answers are on their way is not fetched`() = runTest {
        for (meanwhile in listOf<() -> Unit>({ shown = "1151" }, { cached += "1151" })) {
            fetched.clear()
            cached.clear()
            shown = "1152"
            val gate = CompletableDeferred<Unit>()
            val warm = warm()
            val job = launch {
                warm.run(stillSignedIn = { true }, shared = { gate.await(); "answers" }) { term, _, isCurrent -> fetch(term, isCurrent) }
            }
            advanceUntilIdle()
            meanwhile()
            gate.complete(Unit)
            advanceUntilIdle()
            job.join()
            assertEquals(listOf("114H", "1142", "1141"), fetched)
        }
    }
}
