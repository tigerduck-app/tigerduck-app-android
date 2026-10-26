package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.notify.MailMutePreset
import org.ntust.app.tigerduck.mail.notify.MailMuteRule
import org.ntust.app.tigerduck.mail.notify.MailMuteSettings
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.notification.SystemPermissions
import java.io.File
import java.time.DayOfWeek
import org.ntust.app.tigerduck.mail.schoolMailSite

class SchoolMailSettingsViewModelTest {

    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    // What the account and the notices launch, joined before TemporaryFolder deletes the cache
    // a sign-out may still be wiping there.
    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

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
    private fun viewModel(account: MailAccount = account()): SchoolMailSettingsViewModel {
        // The permission check reads the stub SDK level, which predates the notification
        // permission, so it never touches the context.
        return SchoolMailSettingsViewModel(
            state, scheduler, ExactAlarmAccess { false }, account, SystemPermissions(Application()),
        )
    }

    private fun account() = MailAccount(
        credentials, state, server.factory(), cache, demo, schoolMailSite(),
        RecordingScheduler(), RecordingNotifier(), appScope,
    )

    private fun signIn(studentId: String, password: String) = runBlocking {
        val account = MailAccount(
            credentials, state, server.factory(), cache, demo, schoolMailSite(),
            RecordingScheduler(), RecordingNotifier(), appScope,
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
    fun `the new-mail switch works only while signed in with a password the server accepts`() {
        assertFalse("signed out, no check would run", viewModel().notificationsToggleEnabled.value)
        signIn("b10000001", "pw")
        val account = account()
        val vm = viewModel(account)
        assertTrue(vm.notificationsToggleEnabled.value)
        account.onAuthFailure()
        assertFalse("a rejected password is never sent again, so no check would run", vm.notificationsToggleEnabled.value)
    }

    @Test
    fun `quiet hours, presets and rules are saved as set`() {
        signIn("b10000001", "pw")
        val vm = viewModel()
        assertEquals(MailMuteSettings(), vm.ui.value.muteSettings)

        vm.setQuietHoursEnabled(true)
        vm.setQuietHoursStart(22 * 60 + 30)
        vm.setQuietHoursEnd(6 * 60)
        vm.toggleQuietDay(DayOfWeek.SATURDAY)
        vm.setPreset(MailMutePreset.BULLETIN, true)

        val quiet = state.muteSettings.quietHours
        assertTrue(quiet.enabled)
        assertEquals(22 * 60 + 30, quiet.startMinute)
        assertEquals(6 * 60, quiet.endMinute)
        assertEquals(DayOfWeek.entries.toSet() - DayOfWeek.SATURDAY, quiet.days)
        assertEquals(setOf(MailMutePreset.BULLETIN), state.muteSettings.presets)
        assertEquals(state.muteSettings, vm.ui.value.muteSettings)

        vm.setPreset(MailMutePreset.BULLETIN, false)
        assertEquals(emptySet<MailMutePreset>(), state.muteSettings.presets)
    }

    @Test
    fun `a rule is added, edited in place and deleted, with its value trimmed`() {
        signIn("b10000001", "pw")
        val vm = viewModel()
        val rule = MailMuteRule("a", MailMuteRule.Field.FROM, MailMuteRule.Operator.CONTAINS, "  list@example.com ")
        vm.saveRule(rule)
        vm.saveRule(MailMuteRule("b", MailMuteRule.Field.SUBJECT, MailMuteRule.Operator.IS, "Digest"))
        assertEquals(listOf("list@example.com", "Digest"), state.muteSettings.rules.map { it.value })

        vm.saveRule(rule.copy(op = MailMuteRule.Operator.IS, value = "other@example.com"))
        assertEquals(
            listOf(MailMuteRule("a", MailMuteRule.Field.FROM, MailMuteRule.Operator.IS, "other@example.com"), state.muteSettings.rules[1]),
            state.muteSettings.rules,
        )

        vm.deleteRule("a")
        assertEquals(listOf("b"), state.muteSettings.rules.map { it.id })
        assertEquals(state.muteSettings, vm.ui.value.muteSettings)
    }

    @Test
    fun `a rule with nothing to match is not saved`() {
        signIn("b10000001", "pw")
        val vm = viewModel()
        vm.saveRule(MailMuteRule("a", MailMuteRule.Field.FROM, MailMuteRule.Operator.CONTAINS, "   "))
        assertEquals(emptyList<MailMuteRule>(), state.muteSettings.rules)
    }

    @Test
    fun `signed out, nothing is saved for whoever signs in next`() {
        val vm = viewModel()
        vm.setQuietHoursEnabled(true)
        vm.setPreset(MailMutePreset.BULLETIN, true)
        vm.saveRule(MailMuteRule("a", MailMuteRule.Field.FROM, MailMuteRule.Operator.CONTAINS, "x@example.com"))
        assertEquals(MailMuteSettings(), state.muteSettings)
    }

    /** The demo mailbox is a sign-in like any other, so it must not grey anything out. */
    @Test
    fun `the demo mailbox counts as signed in`() {
        signIn(demo.box.studentId.orEmpty(), demo.box.password.orEmpty())
        assertTrue(viewModel().signedIn.value)
    }
}
