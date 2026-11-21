package org.ntust.app.tigerduck.network.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.tls.PinDomain
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The prompt queue (TLS pins spec, A6; tigerduck-app#958's Cancel and dismissal). */
class PinMismatchPromptsTest {

    private val continued = mutableListOf<String>()
    private val prompts = PinMismatchPrompts(onContinue = { continued += it.setId })

    private val school = PinTestChain().mismatch()
    private val library = PinTestChain(host = "api.lib.ntust.edu.tw").mismatch("library", listOf(PinDomain("api.lib.ntust.edu.tw", true)))

    @Test
    fun `one prompt at a time, the next after it is answered`() {
        prompts.queue(school)
        prompts.queue(library)

        assertEquals("ntust", prompts.pending.value?.setId)
        prompts.cancel()
        assertEquals("library", prompts.pending.value?.setId)
        prompts.continueToSite()
        assertNull(prompts.pending.value)
        assertEquals(listOf("library"), continued)
    }

    @Test
    fun `a set is asked about once per launch`() {
        prompts.queue(school)
        prompts.continueToSite()

        prompts.queue(school)

        assertNull(prompts.pending.value)
    }

    @Test
    fun `a set queued twice is asked about once`() {
        prompts.queue(school)
        prompts.queue(school)
        prompts.cancel()

        assertNull(prompts.pending.value)
    }

    @Test
    fun `after a Cancel the next launch asks again, with no quiet period`() {
        prompts.queue(school)
        prompts.cancel()
        prompts.queue(school)
        assertNull("not again this launch", prompts.pending.value)

        val nextLaunch = PinMismatchPrompts(onContinue = {})
        nextLaunch.queue(school)

        assertEquals("ntust", nextLaunch.pending.value?.setId)
    }

    @Test
    fun `a Cancel is not undone by a browser opening`() {
        prompts.queue(school)
        prompts.cancel()

        prompts.offerDismissedAgain()
        prompts.queue(school)

        assertNull(prompts.pending.value)
    }

    @Test
    fun `a prompt closed by a swipe answers no and stores nothing, and comes back once a browser opens`() {
        prompts.queue(school)
        val waiting = CompletableFuture.supplyAsync { prompts.awaitAnswer("ntust", 5_000) }
        Thread.sleep(50)

        prompts.dismiss(school)

        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        assertNull(prompts.pending.value)
        assertTrue(continued.isEmpty())
        // Requests that keep failing don't bring it back the moment it closes.
        prompts.queue(school)
        assertNull(prompts.pending.value)

        prompts.offerDismissedAgain()
        prompts.queue(school)

        assertEquals(school, prompts.pending.value)
    }

    @Test
    fun `a browser opening brings back only what was closed unanswered, once`() {
        prompts.queue(school)
        prompts.dismiss(school)
        prompts.offerDismissedAgain()
        prompts.queue(school)
        prompts.continueToSite()

        prompts.offerDismissedAgain()
        prompts.queue(school)

        assertNull(prompts.pending.value)
        assertEquals(listOf("ntust"), continued)
    }

    @Test
    fun `a late close of a prompt since replaced changes nothing`() {
        prompts.queue(school)
        prompts.queue(library)
        prompts.cancel()

        // The sheet that showed the first goes away after the second came up.
        prompts.dismiss(school)

        assertEquals(library, prompts.pending.value)
        prompts.offerDismissedAgain()
        prompts.queue(school)
        assertEquals("the Cancel stands", library, prompts.pending.value)
    }

    @Test
    fun `closing one prompt shows the next`() {
        prompts.queue(school)
        prompts.queue(library)

        prompts.dismiss(school)

        assertEquals(library, prompts.pending.value)
    }

    @Test
    fun `a waiting request hears the answer`() {
        prompts.queue(school)
        val waiting = CompletableFuture.supplyAsync { prompts.awaitAnswer("ntust", 5_000) }
        Thread.sleep(50)

        prompts.continueToSite()

        assertTrue(waiting.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a Cancel, a timeout or no prompt answers no`() {
        assertFalse("nothing queued", prompts.awaitAnswer("ntust", 10))
        prompts.queue(school)
        assertFalse("timed out", prompts.awaitAnswer("ntust", 10))
        val waiting = CompletableFuture.supplyAsync { prompts.awaitAnswer("ntust", 5_000) }
        Thread.sleep(50)

        prompts.cancel()

        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        assertTrue(continued.isEmpty())
    }

    @Test
    fun `a prompt no longer eligible stores nothing on Continue, and is skipped in the queue`() {
        var eligible = true
        val gated = PinMismatchPrompts({ continued += it.setId }) { eligible }
        gated.queue(school)
        gated.queue(library)
        val waiting = CompletableFuture.supplyAsync { gated.awaitAnswer("ntust", 5_000) }
        val skipped = CompletableFuture.supplyAsync { gated.awaitAnswer("library", 5_000) }
        Thread.sleep(50)

        // The document that made them eligible expired meanwhile.
        eligible = false
        gated.continueToSite()

        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        assertFalse(skipped.get(5, TimeUnit.SECONDS))
        assertTrue(continued.isEmpty())
        assertNull(gated.pending.value)
    }

    @Test
    fun `an ineligible mismatch isn't queued`() {
        val gated = PinMismatchPrompts({}) { false }

        gated.queue(school)

        assertNull(gated.pending.value)
    }

    @Test
    fun `a wait ends once its call is given up`() {
        prompts.queue(school)
        var canceled = false
        val waiting = CompletableFuture.supplyAsync { prompts.awaitAnswer("ntust", 60_000) { canceled } }
        Thread.sleep(50)

        canceled = true

        assertFalse(waiting.get(2, TimeUnit.SECONDS))
        // The prompt stays for the student.
        assertEquals("ntust", prompts.pending.value?.setId)
    }

    @Test
    fun `a prompt queued in the background waits for the foreground`() {
        // Nothing shows it until the app's root composes; it stays pending meanwhile.
        prompts.queue(school)
        assertEquals(school, prompts.pending.value)
    }

    @Test
    fun `a prompt no longer eligible when it comes to be shown is retired, and the next one shown`() {
        var eligible = setOf("ntust", "library")
        val prompts = PinMismatchPrompts(onContinue = { continued += it.setId }, eligible = { it.setId in eligible })
        prompts.queue(school)
        prompts.queue(library)

        // Its document expired, or was replaced by one without its root, before the app came back.
        eligible = setOf("library")
        prompts.revalidate()

        assertEquals("library", prompts.pending.value?.setId)
        assertFalse(prompts.awaitAnswer("ntust", 0))
        eligible = emptySet()
        prompts.revalidate()
        assertNull(prompts.pending.value)
        assertTrue(continued.isEmpty())
    }
}
