package org.ntust.app.tigerduck.demo

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.di.NetworkModule
import org.ntust.app.tigerduck.library.FakeLibraryServer
import org.ntust.app.tigerduck.library.LibraryReturnSignIn
import org.ntust.app.tigerduck.library.LibrarySession
import org.ntust.app.tigerduck.library.MemoryLibraryStore
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailRepository
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.index.InMemoryMailSearchIndex
import org.ntust.app.tigerduck.mail.index.MailSearchIndex
import org.ntust.app.tigerduck.mail.index.RecordingIndexScheduler
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.smtp.MailSender
import org.ntust.app.tigerduck.mail.smtp.MailTransport
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.CheckOutcome
import org.ntust.app.tigerduck.mail.sync.CheckSource
import org.ntust.app.tigerduck.mail.sync.MailAlarmScheduler
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.shared.LibraryService
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * The promise behind the demo: it never reaches a server (spec §5.2, Review Focus 5). Mail and the
 * library are signed in for real here, stored under the demo, as a student who tries the demo on
 * their own phone has them: neither is reached, and [DemoModeInterceptor], which covers OkHttp
 * alone, is on every client but the two this file names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DemoStaysOnTheDeviceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    // What the account launches, joined before TemporaryFolder deletes the cache.
    private val appScope = testApplicationScope()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val credentials = InMemoryCredentialStore()
    private val demo = FakeDemoGate()
    private val sent = mutableListOf<String>()
    private val transport = MailTransport { _, message -> sent += message.messageId }

    private val account by lazy {
        MailAccount(credentials, state, server.factory(), MailCache(tmp.root), demo, schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
    }

    private fun repository(scope: CoroutineScope, index: MailSearchIndex = InMemoryMailSearchIndex()) = MailRepository(
        account, server.factory(), MailCache(tmp.root), state, MailSender(MessageBuilder(), transport, pause = {}),
        MessageBuilder(), demo, index, RecordingIndexScheduler(), scope,
    )

    private fun checker() = MailChecker(account, state, server.factory(), RecordingNotifier(), MailCache(tmp.root)) { 1_000_000L }

    /** A real student's mail account, stored. */
    private suspend fun realMailStored() {
        server.passwords[STUDENT] = PASSWORD
        assertEquals(null, account.signIn(STUDENT, PASSWORD))
    }

    private fun demoSignsIn() {
        demo.appDemoActive = true
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    @Test
    fun `with the demo over a real mail account a request that gets past its own branch opens nothing`() = runTest {
        server.deliver("a")
        // The demo comes on with the search past its branch, so all that is left to stop it is
        // the check every session goes through.
        var opensWhenItCameOn = -1
        val inner = InMemoryMailSearchIndex()
        val index = object : MailSearchIndex by inner {
            override fun uidValidity(owner: String, folder: String): Long? {
                if (opensWhenItCameOn < 0) opensWhenItCameOn = server.opens
                demoSignsIn()
                return inner.uidValidity(owner, folder)
            }
        }
        val repository = repository(backgroundScope, index)
        realMailStored()

        val outcome = runCatching { repository.search("INBOX", "a") }

        assertTrue("was ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is MailError.DemoMode)
        assertEquals(opensWhenItCameOn, server.opens)
    }

    @Test
    fun `with the demo over a real mail account every request stays on the device`() = runTest {
        val uid = server.deliver("a")
        val repository = repository(backgroundScope)
        realMailStored()
        demoSignsIn()
        val opens = server.opens

        repository.folders()
        repository.loadPage("INBOX", null)
        repository.inboxStatus()
        runCatching { repository.body("INBOX", uid) }
        repository.search("INBOX", "a")
        runCatching { repository.move("INBOX", uid, "回收筒") }
        repository.send(demoMail(repository), null)

        assertEquals(opens, server.opens)
        assertTrue("no SMTP", sent.isEmpty())
    }

    @Test
    fun `with the demo over a real mail account the checker opens nothing`() = runTest {
        realMailStored()
        demoSignsIn()
        val opens = server.opens

        assertEquals(CheckOutcome.Demo, checker().check(CheckSource.WORKER))

        assertEquals(opens, server.opens)
    }

    @OptIn(StartsAccountWork::class)
    @Test
    fun `the demo's inbox leaves the real student's seen marker alone`() = runTest {
        val repository = repository(backgroundScope)
        server.deliver("a")
        realMailStored()
        val marker = state.inboxUidValidity to state.inboxSeenUidNext
        demoSignsIn()
        val demoInbox = repository.inboxStatus()
        assertNotEquals(marker, demoInbox.uidValidity to demoInbox.uidNext)

        // As the list page does, with the status it was given.
        checker().noteSeenByPage(demoInbox, account.currentSignIn)

        assertEquals(marker, state.inboxUidValidity to state.inboxSeenUidNext)
    }

    @Test
    fun `with the demo over a real mail account the checks are not scheduled, and those armed go`() = runTest {
        realMailStored()
        val scheduler = MailAlarmScheduler(context, credentials, state, demo)
        scheduler.schedule()
        assertTrue("armed for the real account", mailChecksArmed())

        demoSignsIn()
        scheduler.schedule()

        assertFalse(mailChecksArmed())
    }

    @Test
    fun `with the demo on another mail sign-in reaches no server`() = runTest {
        server.passwords[OTHER_STUDENT] = PASSWORD
        realMailStored()
        demoSignsIn()
        val opens = server.opens

        assertTrue(account.signIn(OTHER_STUDENT, PASSWORD) is MailError.AuthFailed)

        assertEquals(opens, server.opens)
        assertEquals(STUDENT, credentials.mailStudentId)
    }

    @Test
    fun `with a real library account stored nothing reaches the library's client`() = runBlocking {
        val fixtures = DebugFixtureStore(context).apply { demoMode = true }
        val store = MemoryLibraryStore().apply { storeExpired() }
        var reached = 0
        val network = Interceptor {
            reached++
            throw IOException("no network in this test")
        }
        val service = LibraryService(
            store,
            extraInterceptors = NetworkModule.libraryInterceptors(DemoModeInterceptor(fixtures), debug = false) + network,
        )

        val onReturn = LibraryReturnSignIn(LibrarySession(read = { service.hasAccount }), service, { fixtures.demoMode }, { true })
        assertFalse(onReturn.onAppResumed())
        // Past the app's own check, the interceptor refuses.
        assertTrue(runCatching { service.ensureToken() }.isFailure)
        assertTrue(runCatching { service.generateQRCode() }.isFailure)
        assertTrue(runCatching { service.login(STUDENT, PASSWORD, store.libraryAccountGeneration, byUser = true) }.isFailure)

        assertEquals(0, reached)
    }

    @Test
    fun `the school's client and the library's carry the interceptor`() {
        val fixtures = DebugFixtureStore(context)
        val demoMode = DemoModeInterceptor(fixtures)
        val prefs = AppPreferences(context)
        assertTrue(demoMode in NtustSessionManager(prefs, demoMode).client.interceptors)
        assertTrue(demoMode in NetworkModule.libraryInterceptors(demoMode, debug = false))
    }

    /** Every file that starts an OkHttp client, the phone's: the demo never runs on the watch. */
    private val roots = listOf("src/main/java", "src/play/java", "src/fdroid/java", "src/debug/java", "../shared/src/main/java")
        .map(::File).filter { it.isDirectory }

    private val sources: Map<String, String> = roots
        .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
        .associate { file -> file.invariantSeparatorsPath.substringAfterLast("/java/") to file.readText() }

    private val startsAClient = Regex("""pinnedClientBuilder\s*\(|OkHttpClient\s*\.\s*Builder\s*\(\s*\)|OkHttpClient\s*\(\s*\)""")

    private fun startingAClient() = sources.filter { (path, text) -> path !in BUILDER && startsAClient.containsMatchIn(text) }

    @Test
    fun `the sources were found`() {
        assertEquals(5, roots.size)
        assertTrue(BUILDER.single() in sources)
    }

    @Test
    fun `every client carries the demo's interceptor, or is named here`() {
        val without = startingAClient().filter { (path, text) -> path !in WITHOUT && !carriesTheInterceptor(path, text) }.keys
        assertEquals(emptySet<String>(), without)
    }

    @Test
    fun `the clients named here still are clients, and still go without it`() {
        val found = startingAClient()
        WITHOUT.forEach { path ->
            assertTrue("$path no longer starts a client", path in found)
            assertFalse("$path carries it now: take it off the list", carriesTheInterceptor(path, found.getValue(path)))
        }
    }

    /**
     * A client's own `addInterceptor(demoMode)`. The library's builds its client in `:shared`, which
     * knows no demo, from what the app hands it: there it is the app's call that says.
     */
    private fun carriesTheInterceptor(path: String, text: String): Boolean = when (path) {
        LIBRARY_SERVICE -> "extraInterceptors.forEach(::addInterceptor)" in text &&
            "extraInterceptors = libraryInterceptors(demoMode" in sources.getValue(NETWORK_MODULE)
        else -> Regex("""\.addInterceptor\(demoMode\)""").containsMatchIn(text)
    }

    @Test
    fun `a client started without the interceptor is caught`() {
        assertFalse(carriesTheInterceptor("x/New.kt", "val client = pinnedClientBuilder(tls).build()"))
        assertTrue(carriesTheInterceptor("x/New.kt", "val client = pinnedClientBuilder(tls).addInterceptor(demoMode).build()"))
    }

    private fun mailChecksArmed(): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("school_mail_periodic").get()
        return shadowOf(alarms).scheduledAlarms.isNotEmpty() || work.any { !it.state.isFinished }
    }

    private fun demoMail(repository: MailRepository) =
        OutgoingMail(repository.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b")

    private companion object {
        const val STUDENT = "B00015999"
        const val OTHER_STUDENT = "B00015998"
        const val PASSWORD = "made-up-password"

        const val NETWORK_MODULE = "org/ntust/app/tigerduck/di/NetworkModule.kt"
        const val LIBRARY_SERVICE = "org/ntust/app/tigerduck/shared/LibraryService.kt"

        /** The builder every client starts from, and nothing more. */
        val BUILDER = setOf("org/ntust/app/tigerduck/shared/tls/PinnedHttp.kt")

        /**
         * The two HTTPS clients that do not carry it: the public pin document's fetch, and the
         * in-app browser's downloads, which a person asks for by hand. Neither holds an account.
         * They stay until Sam decides (phase 2 plan, T2.20).
         */
        val WITHOUT = setOf(
            "org/ntust/app/tigerduck/network/tls/PinDocumentClient.kt",
            "org/ntust/app/tigerduck/browser/BrowserDownloads.kt",
        )
    }
}
