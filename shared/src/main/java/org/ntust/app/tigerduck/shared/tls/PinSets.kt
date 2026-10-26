package org.ntust.app.tigerduck.shared.tls

import java.time.Instant

/** A name a pin set covers, and whether its subdomains come with it. */
data class PinDomain(val name: String, val includeSubdomains: Boolean)

/**
 * A group of hosts that share pins: each pin the base64 SHA-256 of a certificate's
 * SubjectPublicKeyInfo DER. Past [expiresAt] the set stops enforcing and its hosts get system
 * trust alone.
 */
data class PinSet(
    val id: String,
    val domains: List<PinDomain>,
    val pins: Set<String>,
    val expiresAt: Instant,
)

/**
 * The pins the apps ship with (TLS pins spec, A2 and A9), the fallback whenever no newer
 * signed document from TigerSync is held. CA pins only, intermediates and roots (D20). Rotating one means changing it here and in the Apple
 * repo's table together; app/src/test's BuiltInPinsTest fails 45 days before any set expires
 * and checks PIN_EXPIRY_EPOCH against the earliest.
 *
 * To make a pin:
 *
 *     echo | openssl s_client -servername HOST -connect HOST:443 -showcerts \
 *       | openssl x509 -noout -pubkey | openssl pkey -pubin -outform der \
 *       | openssl dgst -sha256 -binary | openssl enc -base64
 */
object BuiltInPins {

    /**
     * The serial of the TigerSync document these sets were copied from, the first production one
     * (issued 2026-10-25). A remote document replaces a set only when its serial is greater, so a
     * newer app's own pins win over an older document still cached.
     */
    const val BASELINE_SERIAL: Long = 1_792_888_799

    val sets: List<PinSet> = listOf(
        // CA pins only (D20), never a leaf: *.ntust.edu.tw's TWCA Secure SSL intermediate; the
        // TWCA SSL intermediate (TWCA CYBER Root CA) NTUST's newer certificates, such as
        // cardsystem's, come from; and both TWCA roots (D13), so a renewal through any
        // intermediate TWCA issues under them keeps working.
        PinSet(
            id = "ntust",
            domains = listOf(PinDomain("ntust.edu.tw", includeSubdomains = true)),
            pins = setOf(
                // TWCA Secure SSL Certification Authority
                "9VZ7Yd685RTXsE6rL/puuMbnejYaXwaZasGL7c+Uolc=",
                // TWCA SSL Certification Authority
                "BxOmElsPeD54MBOb5h+TPNJnBlsAk6spRc/wLnko0cc=",
                // TWCA Global Root CA
                "xES1tmzl1x4bXkDyc4XJXL/SSgW1b3DKwJkvD1DDN5w=",
                // TWCA CYBER Root CA
                "BmALlMAxi7aWfwx3h8yKEDKhecTpXjxXYLMuKQ9/7Js=",
            ),
            expiresAt = Instant.parse("2027-08-18T00:00:00Z"),
        ),
        // api.lib.ntust.edu.tw's Sectigo DV E36 intermediate, plus Sectigo Public Server
        // Authentication Root E46 (ECC) and Root R46 (RSA). Every renewal since 2022 changed the
        // key and the issuer, once from ECC to RSA, and Sectigo issues new public TLS
        // certificates only under these two roots.
        PinSet(
            id = "library",
            domains = listOf(PinDomain("api.lib.ntust.edu.tw", includeSubdomains = true)),
            pins = setOf(
                // Sectigo Public Server Authentication CA DV E36
                "ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY=",
                // Sectigo Public Server Authentication Root E46
                "sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=",
                // Sectigo Public Server Authentication Root R46
                "Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q=",
            ),
            expiresAt = Instant.parse("2027-05-04T00:00:00Z"),
        ),
    )
}

/**
 * The set whose domain matches [host] most specifically, or null for an unpinned host: an exact
 * name beats a suffix match, and a longer suffix beats a shorter one (A2).
 */
fun List<PinSet>.matching(host: String): PinSet? {
    val name = host.trimEnd('.').lowercase()
    var best: PinSet? = null
    var bestRank = -1
    for (set in this) {
        for (domain in set.domains) {
            val rank = when {
                name == domain.name -> Int.MAX_VALUE
                domain.includeSubdomains && name.endsWith(".${domain.name}") -> domain.name.length
                else -> continue
            }
            if (rank > bestRank) {
                best = set
                bestRank = rank
            }
        }
    }
    return best
}
