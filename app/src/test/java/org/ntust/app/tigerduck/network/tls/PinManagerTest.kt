package org.ntust.app.tigerduck.network.tls

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.tls.BuiltInPins
import org.ntust.app.tigerduck.shared.tls.PinDocumentVerifier
import org.ntust.app.tigerduck.shared.tls.PinSet
import org.ntust.app.tigerduck.shared.tls.spki
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.annotation.Config

/** The phone's pin manager: the document (A5), a mismatch's outcome (A4) and Continue (A6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PinManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val signer = PinTestSigner()
    private var now = Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
    private val chain = PinTestChain()
    private val file = File(context.noBackupFilesDir, "tls/pin-document.json")

    private val served = mutableListOf<ByteArray?>()
    private val fetches = AtomicInteger()
    private var foreground = true
    private val direct = Executor { it.run() }

    private fun manager(
        client: PinDocumentSource = PinDocumentSource { _ -> fetches.incrementAndGet(); served.removeFirstOrNull() },
        executor: Executor = direct,
        clock: () -> Long = { now },
    ): PinManager {
        val verifier = PinDocumentVerifier(signer.keys) { Instant.ofEpochMilli(now) }
        return PinManager(
            documents = PinDocumentStore(file, verifier),
            acceptances = PinAcceptanceStore(context.getSharedPreferences(PinAcceptanceStore.FILE, Context.MODE_PRIVATE), 30),
            client = client,
            verifier = verifier,
            versionCode = 30,
            clock = clock,
            isForeground = { foreground },
            executor = executor,
        )
    }

    private val expiry get() = Instant.ofEpochMilli(now).plusSeconds(30L * 86_400)

    /** The school set as a document lists it, with [pins]. */
    private fun school(vararg pins: String) = BuiltInPins.sets.single { it.id == "ntust" }.copy(pins = pins.toSet())

    /** A well-formed pin no certificate here has. */
    private fun fake(n: Int): String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { n.toByte() })

    /** Serials above the built-in table's, so a document's sets are in force. */
    private val base = BuiltInPins.BASELINE_SERIAL

    private fun document(serial: Long, vararg sets: PinSet, promptRoots: Set<String> = setOf(chain.root.certificate.spki)) =
        signer.envelope(serial, expiry, sets.toList(), promptRoots)

    @Test
    fun `a new document is taken, kept on disk and verified again on load`() {
        val envelope = document(base + 2, school(fake(1)))
        served += envelope
        val pins = manager()

        assertTrue(pins.refresh())

        assertArrayEquals(envelope, pins.envelope)
        assertArrayEquals(envelope, file.readBytes())
        assertEquals(base + 2, manager().document?.serial)
    }

    @Test
    fun `a document changed on disk counts as none`() {
        served += document(base + 2, school(fake(1)))
        manager().refresh()
        file.writeBytes(file.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })

        assertNull(manager().document)
    }

    @Test
    fun `an older document, the same serial with other bytes, or an expired one is refused`() {
        served += document(base + 5, school(fake(1)))
        val pins = manager()
        pins.refresh()

        served += document(base + 4, school(fake(2)))
        assertFalse(pins.refresh())
        served += signer.envelope(base + 5, expiry.plusSeconds(60), listOf(school(fake(2))))
        assertFalse(pins.refresh())
        served += signer.envelope(base + 6, Instant.ofEpochMilli(now), listOf(school(fake(2))))
        assertFalse(pins.refresh())
        assertEquals(base + 5, pins.document?.serial)
    }

    @Test
    fun `a stored document past its expiry no longer applies, but still keeps an older serial out`() {
        served += document(base + 5, school(fake(1)))
        manager().refresh()
        now = expiry.plusSeconds(1).toEpochMilli()
        val later = manager()

        assertEquals(base + 5, later.document?.serial)
        assertEquals(BuiltInPins.sets.single { it.id == "ntust" }.pins, later.state.policy.setFor("i.ntust.edu.tw", Instant.ofEpochMilli(now))?.pins)
        served += signer.envelope(base + 4, Instant.ofEpochMilli(now).plusSeconds(86_400), listOf(school(fake(2))))
        assertFalse(later.refresh())
    }

    @Test
    fun `a newer document that allows the chain fixes a mismatch silently`() {
        val pins = manager()
        served += document(base + 2, school(chain.intermediate.certificate.spki))

        assertTrue(pins.mayRetry(chain.mismatch()) { false })
        assertNull(pins.prompts.pending.value)
    }

    @Test
    fun `a mismatch refreshes the document once per set per launch`() {
        val pins = manager()
        served += null

        pins.mayRetry(chain.mismatch()) { false }
        pins.mayRetry(chain.mismatch()) { false }
        pins.onMismatch(chain.mismatch())

        assertEquals(1, fetches.get())
    }

    @Test
    fun `a mismatch anchored in a listed public root queues a prompt`() {
        served += document(base + 2, school(fake(1)))
        val pins = manager()
        pins.refresh()
        served += null

        foreground = false
        assertFalse("a background request doesn't wait", pins.mayRetry(chain.mismatch()) { false })
        assertEquals("ntust", pins.prompts.pending.value?.setId)
    }

    @Test
    fun `a root the document doesn't list gets no prompt`() {
        served += document(base + 2, school(fake(1)), promptRoots = setOf(PinTestChain(rootName = "Other Root").root.certificate.spki))
        val pins = manager()
        pins.refresh()

        assertFalse(pins.mayRetry(chain.mismatch()) { false })
        assertNull(pins.prompts.pending.value)
    }

    @Test
    fun `with no document, a mismatch gets no prompt`() {
        val pins = manager()
        served += null

        assertFalse(pins.mayRetry(chain.mismatch()) { false })
        assertNull(pins.prompts.pending.value)
    }

    @Test
    fun `Continue trusts the leaf and the intermediate, not the root, and the waiting request goes on`() {
        served += document(base + 2, school(fake(1)))
        val pins = manager()
        pins.refresh()
        served += null
        val waiting = CompletableFuture.supplyAsync { pins.mayRetry(chain.mismatch()) { false } }
        while (pins.prompts.pending.value == null) Thread.sleep(10)

        pins.prompts.continueToSite()

        assertTrue(waiting.get(5, TimeUnit.SECONDS))
        val accepted = pins.currentAcceptances.single()
        assertEquals(setOf(chain.leaf.certificate.spki, chain.intermediate.certificate.spki), accepted.spkis)
        assertEquals(30L, accepted.appVersion)
        assertTrue(pins.state.policy.allows(chain.mismatch(), Instant.ofEpochMilli(now)))
    }

    @Test
    fun `a call already given up doesn't wait for the student`() {
        served += document(base + 2, school(fake(1)))
        val pins = manager()
        pins.refresh()
        served += null

        val started = System.nanoTime()
        assertFalse(pins.mayRetry(chain.mismatch()) { true })
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5))
        // The prompt is still queued for the student.
        assertEquals("ntust", pins.prompts.pending.value?.setId)
    }

    @Test
    fun `Cancel stores nothing and the request stays failed`() {
        served += document(base + 2, school(fake(1)))
        val pins = manager()
        pins.refresh()
        served += null
        val waiting = CompletableFuture.supplyAsync { pins.mayRetry(chain.mismatch()) { false } }
        while (pins.prompts.pending.value == null) Thread.sleep(10)

        pins.prompts.cancel()

        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        assertTrue(pins.currentAcceptances.isEmpty())
    }

    @Test
    fun `an acceptance from another build is gone after the update`() {
        served += document(base + 2, school(fake(1)))
        val pins = manager()
        pins.refresh()
        pins.prompts.queue(chain.mismatch())
        pins.prompts.continueToSite()

        val updated = PinManager(
            documents = PinDocumentStore(file, PinDocumentVerifier(signer.keys) { Instant.ofEpochMilli(now) }),
            acceptances = PinAcceptanceStore(context.getSharedPreferences(PinAcceptanceStore.FILE, Context.MODE_PRIVATE), 31),
            client = { null },
            verifier = PinDocumentVerifier(signer.keys) { Instant.ofEpochMilli(now) },
            versionCode = 31,
            clock = { now },
            isForeground = { true },
            executor = direct,
        )

        assertTrue(updated.currentAcceptances.isEmpty())
        assertFalse(updated.state.policy.allows(chain.mismatch(), Instant.ofEpochMilli(now)))
    }

    @Test
    fun `a call given up stops waiting for the document fetch, which goes on for the calls after it`() {
        val stalled = CountDownLatch(1)
        val pins = manager(client = { _ -> stalled.await(30, TimeUnit.SECONDS); null }, executor = Executors.newCachedThreadPool())
        val started = System.nanoTime()
        val elapsedMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) }

        try {
            assertFalse(pins.mayRetry(chain.mismatch()) { elapsedMs() > 300 })
            assertTrue("waited ${elapsedMs()} ms", elapsedMs() < 3_000)
        } finally {
            stalled.countDown()
        }
    }

    @Test
    fun `while the app stays in use, the document is fetched every 6 hours, and not once it leaves`() = runTest {
        val start = now
        val pins = manager(clock = { start + testScheduler.currentTime })
        val inUse = launch { pins.keepFresh() }
        runCurrent()
        assertEquals("on coming to the foreground", 1, fetches.get())

        advanceTimeBy(6 * HOUR_MS - 60_000)
        runCurrent()
        assertEquals(1, fetches.get())
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("6 hours on, still in use", 2, fetches.get())
        advanceTimeBy(6 * HOUR_MS)
        runCurrent()
        assertEquals(3, fetches.get())

        inUse.cancel()
        advanceTimeBy(24 * HOUR_MS)
        runCurrent()
        assertEquals("in the background", 3, fetches.get())
    }

    @Test
    fun `coming back within 6 hours of the last fetch waits out the rest of them`() = runTest {
        val start = now
        val pins = manager(clock = { start + testScheduler.currentTime })
        launch { pins.keepFresh() }.also { runCurrent() }.cancel()
        advanceTimeBy(HOUR_MS)

        val inUse = launch { pins.keepFresh() }
        runCurrent()
        assertEquals(1, fetches.get())
        advanceTimeBy(5 * HOUR_MS + 60_000)
        runCurrent()
        assertEquals(2, fetches.get())
        inUse.cancel()
    }
}

private const val HOUR_MS = 60L * 60 * 1000
