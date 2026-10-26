package org.ntust.app.tigerduck.ui.screen.mail

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The School Mail notifications page says when the system blocks notifications, and checks again on return. */
@RunWith(RobolectricTestRunner::class)
// API 33, the first with POST_NOTIFICATIONS; one test below takes 30, which has none.
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class SchoolMailSettingsPermissionTest {

    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    // What the account and the notices launch, joined before TemporaryFolder deletes the cache
    // a sign-out may still be wiping there.
    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun vm(): SchoolMailSettingsViewModel {
        val state = InMemoryMailStateStore()
        val cache = MailCache(tmp.root)
        val account = MailAccount(
            InMemoryCredentialStore(), state, FakeMailServer().factory(), cache, FakeDemoGate(), schoolMailSite(),
            RecordingScheduler(), RecordingNotifier(), appScope,
        )
        return SchoolMailSettingsViewModel(
            state, RecordingScheduler(), ExactAlarmAccess { true }, account, SystemPermissions(context),
        )
    }

    @Test
    fun `a blocked notification permission shows, and clears once granted and the page is back`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val vm = vm()
        assertTrue(vm.ui.value.notificationsDenied)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        vm.refresh()
        assertFalse(vm.ui.value.notificationsDenied)
    }

    // Before Android 13 there is no runtime permission, but the app's notifications can still be
    // turned off in the system settings.
    @Test
    @Config(application = Application::class, sdk = [Build.VERSION_CODES.R])
    fun `notifications the system settings turned off show, with no permission to ask for`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        val vm = vm()
        assertTrue(vm.ui.value.notificationsDenied)
        shadowOf(manager).setNotificationsEnabled(true)
        vm.refresh()
        assertFalse(vm.ui.value.notificationsDenied)
    }

    @Test
    fun `the School Mail channel turned off shows too`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NotificationChannels.SCHOOL_MAIL, "School Mail", NotificationManager.IMPORTANCE_NONE))
        assertTrue(vm().ui.value.notificationsDenied)
    }

    @Test
    fun `allowed notifications on a channel not created yet show nothing`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(vm().ui.value.notificationsDenied)
    }
}
