package org.ntust.app.tigerduck.shared.tls

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A student's Continue on a pin set (TLS pins spec, A6): the SPKIs of the leaf and the
 * intermediates of the validated chain then served, never the root's (D17), kept for that set
 * while [appVersion], the build number (versionCode) it was made under, is the running one.
 */
data class PinAcceptance(
    val setId: String,
    val spkis: Set<String>,
    val appVersion: Long,
    val acceptedAt: Long,
)

/** What enforcement made of a pinned host's validated chain. */
sealed interface PinDecision {
    /** The chain matches the set's effective pins, or the set has expired. */
    data object Allowed : PinDecision

    /** The chain shares no SPKI with the set's effective pins. */
    data class Mismatch(val set: PinSet) : PinDecision
}

/**
 * The pins in force (A4): per set, a valid TigerSync document's pins when its serial is above the
 * built-in table's [baselineSerial] and it lists the set, the built-in pins otherwise, joined by
 * the SPKIs the student accepted for that set under [appVersion]. A set's expiry is the later of
 * its built-in and remote ones (D18). Sets only the document lists come from the document.
 */
class PinPolicy(
    private val builtIn: List<PinSet> = BuiltInPins.sets,
    private val baselineSerial: Long = BuiltInPins.BASELINE_SERIAL,
    val document: PinDocument? = null,
    val acceptances: Collection<PinAcceptance> = emptyList(),
    val appVersion: Long,
    /**
     * Debug builds only: this set's pins become one no chain has, so the prompt can be seen with
     * a document that offers the chain.
     */
    val simulatedMismatch: String? = null,
) {

    private val withDocument: List<PinSet> by lazy { effectiveSets(document) }
    private val withoutDocument: List<PinSet> by lazy { effectiveSets(null) }

    /**
     * The document in force at [now]: one past its own `expires_at` counts as none (A1.3), so a
     * document held since before it expired stops applying without a restart.
     */
    fun documentAt(now: Instant): PinDocument? = document?.takeIf { now.isBefore(it.expiresAt) }

    /** Every set in force at [now], with its effective pins and expiry. */
    fun sets(now: Instant): List<PinSet> = if (documentAt(now) != null) withDocument else withoutDocument

    private fun effectiveSets(document: PinDocument?): List<PinSet> {
        val remote = document?.takeIf { it.serial > baselineSerial }?.sets.orEmpty().associateBy { it.id }
        val builtInIds = builtIn.map { it.id }.toSet()
        val merged = builtIn.map { own ->
            val newer = remote[own.id] ?: return@map own
            newer.copy(expiresAt = maxOf(own.expiresAt, newer.expiresAt))
        } + remote.values.filter { it.id !in builtInIds }
        val accepted = acceptances.filter { it.appVersion == appVersion }.groupBy { it.setId }
        return merged.map { set ->
            val base = if (set.id == simulatedMismatch) setOf(SIMULATED_PIN) else set.pins
            set.copy(pins = base + accepted[set.id].orEmpty().flatMap { it.spkis })
        }
    }

    /** The set [host] falls under at [now], or null for an unpinned host. */
    fun setFor(host: String, now: Instant): PinSet? = sets(now).matching(host)

    /**
     * Whether [mismatch] may be offered to the student (D18): its chain's anchor is one of the
     * `prompt_roots`, Mozilla's TLS roots, of the document in force. With none, nothing is.
     */
    fun mayPrompt(mismatch: PinMismatch, now: Instant): Boolean =
        documentAt(now)?.promptRoots?.contains(mismatch.anchorSpki) == true

    /** Whether these pins allow [mismatch]'s chain now, as a newer document or a Continue may. */
    fun allows(mismatch: PinMismatch, now: Instant): Boolean {
        val set = setFor(mismatch.host, now) ?: return true
        return decide(set, mismatch.spkis, now) == PinDecision.Allowed
    }

    /** What the pinned [set] makes of a validated chain with [spkis], at [now]. */
    fun decide(set: PinSet, spkis: Set<String>, now: Instant): PinDecision = when {
        !now.isBefore(set.expiresAt) -> PinDecision.Allowed
        spkis.any { it in set.pins } -> PinDecision.Allowed
        else -> PinDecision.Mismatch(set)
    }

    fun with(
        document: PinDocument? = this.document,
        acceptances: Collection<PinAcceptance> = this.acceptances,
        simulatedMismatch: String? = this.simulatedMismatch,
    ) = PinPolicy(builtIn, baselineSerial, document, acceptances, appVersion, simulatedMismatch)

    companion object {
        /** A pin no certificate has: base64 of 32 zero bytes. */
        const val SIMULATED_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}

/** The [PinPolicy] every connection reads, replaced whole as documents and acceptances change. */
class PinState(initial: PinPolicy) {
    private val current = AtomicReference(initial)
    private val listeners = CopyOnWriteArrayList<(PinPolicy, PinPolicy) -> Unit>()

    val policy: PinPolicy get() = current.get()

    /** Applies [change]; each listener then hears the policy before it and after. */
    fun update(change: (PinPolicy) -> PinPolicy): PinPolicy {
        while (true) {
            val old = current.get()
            val new = change(old)
            if (current.compareAndSet(old, new)) {
                listeners.forEach { it(old, new) }
                return new
            }
        }
    }

    fun onChange(listener: (old: PinPolicy, new: PinPolicy) -> Unit) {
        listeners += listener
    }
}
