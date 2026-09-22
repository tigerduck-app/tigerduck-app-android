package org.ntust.app.tigerduck.ui.screen.mail

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.testApplicationScope
import java.io.File
import org.ntust.app.tigerduck.mail.schoolMailSite

class SchoolMailSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val state = InMemoryMailStateStore().apply {
        displayName = "中文名"
        diagnostics = listOf("1789466442000|ALARM|NewMail", "garbage")
    }
    private val scheduler = RecordingScheduler()
    private val credentials = InMemoryCredentialStore()
    private val server = FakeMailServer()
    private val demo = FakeDemoGate()
    private val cache by lazy { MailCache(tmp.root) }

    /**
     * Built lazily: [MailAccount] reads the credential store once, at
     * construction, to seed `signedIn`, so a test that wants a signed-in
     * account has to sign in before the view model exists.
     */
    private fun viewModel(): SchoolMailSettingsViewModel {
        val account = MailAccount(
            credentials, state, server.factory(), cache, demo, schoolMailSite(),
            RecordingScheduler(), RecordingNotifier(), testApplicationScope(),
        )
        // The same TestDispatcher backs Dispatchers.Main and the injected @IoDispatcher, so the
        // cache walk stays synchronous under the test.
        return SchoolMailSettingsViewModel(state, scheduler, ExactAlarmAccess { false }, cache, main.dispatcher, account)
    }

    private fun signIn(studentId: String, password: String) = runBlocking {
        val account = MailAccount(
            credentials, state, server.factory(), cache, demo, schoolMailSite(),
            RecordingScheduler(), RecordingNotifier(), testApplicationScope(),
        )
        val error = withTimeout(5_000) { account.signIn(studentId, password) }
        assertNull("the sign-in fixture must succeed", error)
    }

    @Test
    fun `initial state reads the store`() {
        val ui = viewModel().ui.value
        assertEquals(true, ui.notificationsEnabled)
        assertEquals("中文名", ui.displayName)
        assertFalse(ui.canExactAlarm)
        assertEquals(listOf("09/15 18:00:42 · ALARM · NewMail"), ui.diagnostics)
    }

    @Test
    fun `turning notifications off cancels checks and on schedules them`() {
        val vm = viewModel()
        vm.setNotifications(false)
        assertFalse(state.notificationsEnabled)
        assertEquals(1, scheduler.cancelled)
        vm.setNotifications(true)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `display name is saved as typed`() {
        val vm = viewModel()
        vm.setDisplayName("王小明")
        assertEquals("王小明", state.displayName)
        assertEquals("王小明", vm.ui.value.displayName)
    }

    /**
     * `signedIn` is what both greyed-out controls bind their `enabled` to:
     * the School Mail notifications row in Settings → Notifications, and
     * "Name shown to recipients" on the School Mail settings page. Signed out,
     * both are visible and dead.
     */
    @Test
    fun `signed out, the notification row and the display name field are disabled`() {
        assertFalse(viewModel().signedIn.value)
    }

    @Test
    fun `signed in, the notification row and the display name field are enabled`() {
        signIn("b10000001", "pw")
        assertTrue(viewModel().signedIn.value)
    }

    @Test
    fun `the cache size covers the whole mail cache and clearing it empties the row`() {
        cache.saveBody("INBOX", 1, 1, MailBody(null, "x".repeat(2_000), emptyList(), emptyMap()))
        cache.attachmentsDir.mkdirs()
        File(cache.attachmentsDir, "a.pdf").writeText("0123456789")

        val vm = viewModel()
        assertNull("nothing is claimed before the first walk finishes", vm.cacheBytes.value)
        vm.refreshCacheSize()
        val measured = vm.cacheBytes.value!!
        assertEquals(cache.sizeBytes(), measured)
        assertTrue("the attachments directory counts too", measured > 2_000)

        vm.clearCache()
        assertEquals(0L, vm.cacheBytes.value)
        assertFalse(cache.attachmentsDir.exists())
    }

    /** The demo mailbox is a sign-in like any other, so it must not grey anything out. */
    @Test
    fun `the demo mailbox counts as signed in`() {
        signIn(demo.box.studentId.orEmpty(), demo.box.password.orEmpty())
        assertTrue(viewModel().signedIn.value)
    }
}
