package org.ntust.app.tigerduck.shared.tls

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The effective pins rule (A4) and set matching (A2). */
class PinPolicyTest {

    private val signer = TestSigner()
    private val now = Instant.parse("2026-10-25T04:00:00Z")

    private val school = PinSet("ntust", listOf(PinDomain("ntust.edu.tw", true)), setOf(pin(1)), Instant.parse("2027-08-18T00:00:00Z"))
    private val library = PinSet("library", listOf(PinDomain("api.lib.ntust.edu.tw", true)), setOf(pin(2)), Instant.parse("2027-05-04T00:00:00Z"))
    private val builtIn = listOf(school, library)

    private fun document(serial: Long, vararg sets: PinSet, promptRoots: Set<String> = emptySet()): PinDocument =
        PinDocument(serial, now, now.plusSeconds(86_400), sets.toList(), promptRoots, ByteArray(0))

    private fun policy(
        document: PinDocument? = null,
        acceptances: List<PinAcceptance> = emptyList(),
        version: Long = 30,
    ) = PinPolicy(builtIn, baselineSerial = 100, document = document, acceptances = acceptances, appVersion = version)

    @Test
    fun `the most specific domain wins`() {
        val sets = builtIn + PinSet("exact", listOf(PinDomain("x.api.lib.ntust.edu.tw", false)), setOf(pin(3)), now)

        assertEquals("ntust", sets.matching("moodle2.ntust.edu.tw")?.id)
        assertEquals("library", sets.matching("api.lib.ntust.edu.tw")?.id)
        assertEquals("library", sets.matching("y.api.lib.ntust.edu.tw")?.id)
        assertEquals("exact", sets.matching("x.api.lib.ntust.edu.tw")?.id)
        assertEquals("ntust", sets.matching("Moodle2.NTUST.edu.tw.")?.id)
        assertNull(sets.matching("api.tigerduck.app"))
        assertNull(sets.matching("notntust.edu.tw"))
    }

    @Test
    fun `the built-in table carries no tigerduck set and no leaf pins`() {
        assertEquals(listOf("ntust", "library"), BuiltInPins.sets.map { it.id })
        assertNull(BuiltInPins.sets.matching("api.tigerduck.app"))
        val leaves = setOf("Nz3wUtBXZ+2HPXuSyx4enXs62i/PH4MKtayV9N4X0PE=", "m8Epf0KqJFv9abCXfipePZ79hfOMjddCKxz+RSZIDKY=")
        assertTrue(BuiltInPins.sets.none { set -> set.pins.any { it in leaves } })
    }

    @Test
    fun `with no document, the built-in pins are in force`() {
        assertEquals(setOf(pin(1)), policy().setFor("i.ntust.edu.tw", now)?.pins)
    }

    @Test
    fun `a newer document's set replaces the built-in pins, and a set it leaves out keeps them`() {
        val newer = school.copy(pins = setOf(pin(9)))

        val rules = policy(document(101, newer))

        assertEquals(setOf(pin(9)), rules.setFor("i.ntust.edu.tw", now)?.pins)
        assertEquals(setOf(pin(2)), rules.setFor("api.lib.ntust.edu.tw", now)?.pins)
    }

    @Test
    fun `a document no newer than the built-in table's baseline is ignored`() {
        val older = school.copy(pins = setOf(pin(9)))

        assertEquals(setOf(pin(1)), policy(document(100, older)).setFor("i.ntust.edu.tw", now)?.pins)
    }

    @Test
    fun `with the shipped baseline, only a document newer than the one the table was copied from wins`() {
        val newer = school.copy(pins = setOf(pin(9)))
        fun shipped(serial: Long) = PinPolicy(builtIn, document = document(serial, newer), appVersion = 30)

        assertEquals(setOf(pin(1)), shipped(BuiltInPins.BASELINE_SERIAL).setFor("i.ntust.edu.tw", now)?.pins)
        assertEquals(setOf(pin(9)), shipped(BuiltInPins.BASELINE_SERIAL + 1).setFor("i.ntust.edu.tw", now)?.pins)
    }

    @Test
    fun `a document held past its own expiry stops applying`() {
        val rules = policy(document(101, school.copy(pins = setOf(pin(9)))))
        val later = now.plusSeconds(2 * 86_400)

        assertEquals(setOf(pin(9)), rules.setFor("i.ntust.edu.tw", now)?.pins)
        assertEquals(setOf(pin(1)), rules.setFor("i.ntust.edu.tw", later)?.pins)
        assertNull(rules.documentAt(later))
    }

    @Test
    fun `a document extends a set's expiry but never shortens it`() {
        val later = school.copy(expiresAt = Instant.parse("2028-01-01T00:00:00Z"))
        val earlier = school.copy(expiresAt = Instant.parse("2026-11-01T00:00:00Z"))

        assertEquals(later.expiresAt, policy(document(101, later)).setFor("i.ntust.edu.tw", now)?.expiresAt)
        assertEquals(school.expiresAt, policy(document(101, earlier)).setFor("i.ntust.edu.tw", now)?.expiresAt)
    }

    @Test
    fun `a set only the document lists is enforced from it`() {
        val extra = PinSet("extra", listOf(PinDomain("example.test", true)), setOf(pin(7)), now.plusSeconds(60))

        assertEquals(setOf(pin(7)), policy(document(101, extra)).setFor("www.example.test", now)?.pins)
    }

    @Test
    fun `an acceptance joins its set's pins under the build it was made in only`() {
        val acceptance = PinAcceptance("ntust", setOf(pin(5)), appVersion = 30, acceptedAt = 1L)

        assertEquals(setOf(pin(1), pin(5)), policy(acceptances = listOf(acceptance)).setFor("i.ntust.edu.tw", now)?.pins)
        assertEquals(setOf(pin(1)), policy(acceptances = listOf(acceptance), version = 31).setFor("i.ntust.edu.tw", now)?.pins)
        assertEquals(setOf(pin(2)), policy(acceptances = listOf(acceptance)).setFor("api.lib.ntust.edu.tw", now)?.pins)
    }

    @Test
    fun `an expired set allows any chain`() {
        val expired = school.copy(expiresAt = now)

        assertEquals(PinDecision.Allowed, policy().decide(expired, setOf(pin(8)), now))
        assertEquals(PinDecision.Mismatch(school), policy().decide(school, setOf(pin(8)), now))
        assertEquals(PinDecision.Allowed, policy().decide(school, setOf(pin(8), pin(1)), now))
    }

    @Test
    fun `a simulated mismatch empties a set of every real pin`() {
        val rules = PinPolicy(builtIn, appVersion = 30, simulatedMismatch = "ntust")

        assertEquals(setOf(PinPolicy.SIMULATED_PIN), rules.setFor("i.ntust.edu.tw", now)?.pins)
        assertEquals(setOf(pin(2)), rules.setFor("api.lib.ntust.edu.tw", now)?.pins)
    }

    @Test
    fun `only a chain anchored in the document's prompt roots may be offered to the student`() {
        val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Public Root").build()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("i.ntust.edu.tw").signedBy(root).build()
        val chain = listOf(leaf.certificate, root.certificate)
        val mismatch = PinMismatch("ntust", "i.ntust.edu.tw", school.domains, chain, chain.map { it.spki }.toSet())

        assertFalse("no document", policy().mayPrompt(mismatch, now))
        assertTrue(policy(document(1, promptRoots = setOf(root.certificate.spki))).mayPrompt(mismatch, now))
        assertFalse(policy(document(1, promptRoots = setOf(pin(6)))).mayPrompt(mismatch, now))
        // A leaf among the roots doesn't make its chain eligible: only the anchor counts.
        assertFalse(policy(document(1, promptRoots = setOf(leaf.certificate.spki))).mayPrompt(mismatch, now))
        // A debug build's simulated mismatch is held to the same rule.
        val simulated = PinPolicy(builtIn, appVersion = 30, simulatedMismatch = "ntust")
        assertFalse(simulated.mayPrompt(mismatch, now))
        assertTrue(simulated.with(document = document(1, promptRoots = setOf(root.certificate.spki))).mayPrompt(mismatch, now))
    }

    private companion object {
        /** A distinct, well-formed pin. */
        fun pin(n: Int): String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { n.toByte() })
    }
}
