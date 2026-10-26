package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.shared.tls.BuiltInPins
import org.ntust.app.tigerduck.shared.tls.spki
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The built-in pin table in :shared (TLS pins spec, A2 and A9). Past a set's expiry it stops
 * enforcing unless a TigerSync document extends it, so these fail while there is still time to
 * rotate it in both apps. They also keep PIN_EXPIRY_EPOCH in step, check the pins against the
 * certificates they stand for, and check that no pin is left in network_security_config.xml,
 * where a stale pin-set would be enforced by the platform with no way past it.
 */
class BuiltInPinsTest {

    private fun set(id: String) = BuiltInPins.sets.single { it.id == id }

    @Test
    fun `no set is within 45 days of its expiry`() {
        val deadline = LocalDate.now(ZoneOffset.UTC).plusDays(45).atStartOfDay(ZoneOffset.UTC).toInstant()
        BuiltInPins.sets.forEach { set ->
            assertTrue("The ${set.id} pins expire on ${set.expiresAt}; rotate them in both apps", !set.expiresAt.isBefore(deadline))
        }
    }

    @Test
    fun `PIN_EXPIRY_EPOCH is the earliest set expiry`() {
        assertEquals(BuiltInPins.sets.minOf { it.expiresAt }.toEpochMilli(), BuildConfig.PIN_EXPIRY_EPOCH)
    }

    @Test
    fun `the table's baseline is the first production document's serial, which it was copied from`() {
        // :shared's ProductionPinDocumentTest checks that document against this table.
        assertEquals(1_792_888_799L, BuiltInPins.BASELINE_SERIAL)
    }

    private fun certificates(resource: String): List<X509Certificate> =
        requireNotNull(javaClass.getResourceAsStream(resource)) { "missing $resource" }.use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificates(stream).map { it as X509Certificate }
        }

    private fun List<X509Certificate>.fingerprints(): Set<String> = map { certificate ->
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }
    }.toSet()

    /** The chain api.lib.ntust.edu.tw served on 2026-10-14 (public data): leaf, Sectigo DV E36, Root E46. */
    private val libraryChain get() = certificates("/tls/api.lib.ntust.edu.tw.pem")

    /** Sectigo Public Server Authentication Root E46 and Root R46. */
    private val sectigoRoots get() = certificates("/tls/sectigo-public-server-authentication-roots.pem")

    /** TWCA Global Root CA and TWCA CYBER Root CA. */
    private val twcaRoots get() = certificates("/tls/twca-roots.pem")

    @Test
    fun `the root fixtures hold the roots the system stores ship`() {
        // SHA-256 fingerprints as crt.sectigo.com, Mozilla and the macOS store list Sectigo's, and
        // Android's system store TWCA's, so a swapped fixture can't vouch for its own pins.
        assertEquals(
            setOf(
                "C90F26F0FB1B4018B22227519B5CA2B53E2CA5B3BE5CF18EFE1BEF47380C5383",
                "7BB647A62AEEAC88BF257AA522D01FFEA395E0AB45C73F93F65654EC38F25A06",
            ),
            sectigoRoots.fingerprints(),
        )
        assertEquals(
            setOf(
                "59769007F7685D0FCD50872F9F95D5755A5B2B457D81F3692B610A98672F0E1B",
                "3F63BB2814BE174EC8B6439CF08D6D56F0B7C405883A5648A334424D6B3EC558",
            ),
            twcaRoots.fingerprints(),
        )
    }

    @Test
    fun `the library pins are its CAs, never its leaf`() {
        val leaf = libraryChain.first()
        val cas = (libraryChain.drop(1) + sectigoRoots).map { it.spki }.toSet()

        assertEquals(emptySet<String>(), set("library").pins - cas)
        assertFalse("the leaf is pinned", leaf.spki in set("library").pins)
        // A renewal from either Sectigo root keeps working: every one since 2022 changed issuer.
        sectigoRoots.forEach { root -> assertTrue("${root.subjectX500Principal.name} is not pinned", root.spki in set("library").pins) }
    }

    @Test
    fun `the school pins hold both TWCA roots and no leaf`() {
        twcaRoots.forEach { root -> assertTrue("${root.subjectX500Principal.name} is not pinned", root.spki in set("ntust").pins) }
        // The *.ntust.edu.tw leaf the 2026-10-25 table carried (D20).
        assertFalse("Nz3wUtBXZ+2HPXuSyx4enXs62i/PH4MKtayV9N4X0PE=" in set("ntust").pins)
    }

    private fun declaredInput(property: String): String =
        checkNotNull(System.getProperty(property)) {
            "$property is not set: declare the file as an input of the unit-test tasks in app/build.gradle.kts"
        }

    private val parser get() = DocumentBuilderFactory.newInstance().newDocumentBuilder()

    private fun config(path: String) = parser.parse(File(path))

    private val configs: Map<String, String> = mapOf(
        "release" to declaredInput("tigerduck.nsc.release"),
        "debug" to declaredInput("tigerduck.nsc.debug"),
        "watch" to "../wear/src/main/res/xml/network_security_config.xml",
    )

    @Test
    fun `no network config pins anything, and the pinned hosts stay HTTPS only`() {
        configs.forEach { (name, path) ->
            val document = config(path)
            assertEquals("$name still has a pin-set", 0, document.getElementsByTagName("pin-set").length)
            val domainConfigs = document.getElementsByTagName("domain-config")
            val httpsOnly = (0 until domainConfigs.length).map { domainConfigs.item(it) as Element }
                .filter { it.getAttribute("cleartextTrafficPermitted") == "false" }
                .flatMap { config ->
                    val domains = config.getElementsByTagName("domain")
                    (0 until domains.length).map { domains.item(it).textContent.trim() }
                }
            val expected = if (name == "watch") listOf("api.lib.ntust.edu.tw") else listOf("ntust.edu.tw", "api.lib.ntust.edu.tw")
            assertTrue("$name lets a pinned host use cleartext", httpsOnly.containsAll(expected))
        }
    }

    @Test
    fun `the release and watch configs allow no cleartext by default`() {
        listOf(configs.getValue("release"), configs.getValue("watch")).forEach { path ->
            val base = config(path).getElementsByTagName("base-config").item(0) as Element
            assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        }
    }

    @Test
    fun `the watch applies its config`() {
        val application = parser.parse(File("../wear/src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config", application.getAttribute("android:networkSecurityConfig"))
    }

    @Test
    fun `the watch's versionCode is the phone's plus the offset the watch reads acceptances by`() {
        val watchCode = Regex("""versionCode = (\d+)""").find(File("../wear/build.gradle.kts").readText())!!.groupValues[1].toLong()

        assertEquals(BuildConfig.VERSION_CODE.toLong() + WearProtocol.TlsPins.WATCH_VERSION_CODE_OFFSET, watchCode)
    }
}
