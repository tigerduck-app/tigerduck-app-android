package org.ntust.app.tigerduck.network

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.di.NetworkModule
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.PinnedTrustManager
import org.ntust.app.tigerduck.shared.tls.PinPolicy
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedTrustManager
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Every HTTPS client goes through [PinnedTrustManager] (TLS pins spec, A9): a source check that
 * no client is started any other way, and the root clients' wiring itself.
 */
class AllHttpsClientsPinnedTest {

    private val roots = listOf(
        "src/main/java",
        "src/play/java",
        "src/fdroid/java",
        "src/debug/java",
        "../shared/src/main/java",
        "../wear/src/main/java",
    ).map(::File).filter { it.isDirectory }

    private val sources: Map<String, String> = roots
        .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.map { it to root } }
        .associate { (file, _) -> file.invariantSeparatorsPath to file.readText() }

    /** The files allowed to start a client: the pinned builder, and the document fetch, unpinned on purpose. */
    private val allowed = setOf(
        "org/ntust/app/tigerduck/shared/tls/PinnedHttp.kt",
        "org/ntust/app/tigerduck/network/tls/PinDocumentClient.kt",
    )

    /** The sources, but those ending in one of [except], that match [pattern]. */
    private fun offenders(pattern: Regex, except: Set<String> = emptySet()) = sources
        .filter { (path, text) -> except.none { path.endsWith(it) } && pattern.containsMatchIn(text) }
        .keys
        .map { it.substringAfterLast("/java/") }
        .sorted()

    @Test
    fun `the sources were found`() {
        assertEquals(6, roots.size)
        assertEquals(true, sources.keys.any { it.endsWith("tls/PinnedHttp.kt") })
    }

    @Test
    fun `no client is started but through the pinned builder`() {
        val starts = Regex("""OkHttpClient\s*\.\s*Builder\s*\(\s*\)|OkHttpClient\s*\(\s*\)""")
        assertEquals(emptyList<String>(), offenders(starts, except = allowed))
    }

    @Test
    fun `every Retrofit sends through a client it is given`() {
        val offenders = sources.filter { (_, text) -> startsRetrofitWithoutClient(text) }.keys
        assertEquals(emptyList<String>(), offenders.map { it.substringAfterLast("/java/") }.sorted())
    }

    @Test
    fun `a Retrofit given no client is caught`() {
        assertTrue(startsRetrofitWithoutClient("val api = Retrofit.Builder().baseUrl(base).build()"))
        assertTrue(startsRetrofitWithoutClient("Retrofit.Builder()\n    .baseUrl(base)\n    .addConverterFactory(f)\n    .build()"))
        assertFalse(startsRetrofitWithoutClient("Retrofit.Builder().baseUrl(base).callFactory(calls).build()"))
        assertFalse(startsRetrofitWithoutClient("Retrofit.Builder()\n    .baseUrl(base)\n    .client(client)\n    .build()"))
    }

    /**
     * Whether [text] makes a Retrofit without naming the client it sends through, up to its
     * `build()`. Such a Retrofit makes an OkHttpClient of its own, inside the library, where the
     * check on `OkHttpClient` above cannot see it: an unpinned one.
     */
    private fun startsRetrofitWithoutClient(text: String): Boolean =
        Regex("""Retrofit\s*\.\s*Builder\s*\(\s*\)""").findAll(text).any { start ->
            val chain = text.substring(start.range.last).substringBefore(".build()")
            !Regex("""\.\s*(client|callFactory)\s*\(""").containsMatchIn(chain)
        }

    @Test
    fun `no production code asks the pinned builder for the platform's trust`() {
        assertEquals(emptyList<String>(), offenders(Regex("""pinnedClientBuilder\s*\(\s*null""")))
    }

    @Test
    fun `nothing opens an HTTPS connection of its own`() {
        assertEquals(emptyList<String>(), offenders(Regex("""HttpsURLConnection|\.openConnection\s*\(""")))
    }

    @Test
    fun `nothing builds a TLS context but the pin engine`() {
        val contexts = Regex("""SSLContext\s*\.\s*getInstance|TrustManagerFactory\s*\.\s*getInstance|SocketFactory\s*\.\s*getDefault""")
        assertEquals(
            emptyList<String>(),
            offenders(contexts, except = setOf("org/ntust/app/tigerduck/shared/tls/PinnedTrustManager.kt")),
        )
    }

    @Test
    fun `School Mail's sessions are given the pinned socket factory`() {
        val mailModule = sources.entries.single { it.key.endsWith("di/MailModule.kt") }.value
        assertEquals(true, Regex("""AngusMailSessionFactory\(configs, tls\.socketFactory\)""").containsMatchIn(mailModule))
        assertEquals(true, Regex("""AngusMailTransport\(configs, tls\.socketFactory\)""").containsMatchIn(mailModule))
        // Every production session is made from the given factory.
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""MailProperties\s*\.\s*(imap|smtp)\s*\(\s*config\s*\)""")),
        )
    }

    @Test
    fun `the root clients take the pinned socket factory`() {
        val context = JvmContext()
        val tls = PinnedTls(PinnedTrustManager(NoTrust, { _, _, _ -> emptyList() }, { PinPolicy(appVersion = 1) }, {}))
        val demoMode = DemoModeInterceptor(DebugFixtureStore(context))

        assertSame(tls.socketFactory, NetworkModule.provideOkHttpClient(AppPreferences(context), demoMode, tls).sslSocketFactory)
        assertSame(tls.socketFactory, NtustSessionManager(AppPreferences(context), demoMode, tls).client.sslSocketFactory)
    }

    private object NoTrust : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
