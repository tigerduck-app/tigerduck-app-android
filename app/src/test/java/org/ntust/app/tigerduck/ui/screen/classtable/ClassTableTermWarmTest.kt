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
}
