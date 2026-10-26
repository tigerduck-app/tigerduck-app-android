package org.ntust.app.tigerduck.network.tls

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.tls.PinDomain
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.ntust.app.tigerduck.util.JvmContext

/** The prompt queue (TLS pins spec, A6 and D5). */
class PinMismatchPromptsTest {

    private val context = JvmContext()
    private val store = PinAcceptanceStore(context.getSharedPreferences(PinAcceptanceStore.FILE, Context.MODE_PRIVATE), 30)
    private var now = 1_000_000L
    private val continued = mutableListOf<String>()
    private val prompts = PinMismatchPrompts(store, { now }, onContinue = { continued += it.setId })

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
    fun `after a Cancel the set stays quiet for an hour, past the next launch`() {
        prompts.queue(school)
        prompts.cancel()

        val nextLaunch = PinMismatchPrompts(store, { now }, onContinue = {})
        now += PinAcceptanceStore.QUIET_MS - 1
        nextLaunch.queue(school)
        assertNull(nextLaunch.pending.value)

        now += 1
        nextLaunch.queue(school)
        assertEquals("ntust", nextLaunch.pending.value?.setId)
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
        val gated = PinMismatchPrompts(store, { now }, { continued += it.setId }) { eligible }
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
        assertFalse("not a Cancel: no quiet hour", store.isQuiet("ntust", now))
    }

    @Test
    fun `an ineligible mismatch isn't queued`() {
        val gated = PinMismatchPrompts(store, { now }, {}) { false }

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
        val prompts = PinMismatchPrompts(store, { now }, onContinue = { continued += it.setId }, eligible = { it.setId in eligible })
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
