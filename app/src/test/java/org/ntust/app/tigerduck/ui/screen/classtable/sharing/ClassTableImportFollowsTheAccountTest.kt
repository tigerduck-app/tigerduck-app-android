package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.ui.screen.announcements.testSessions
import org.ntust.app.tigerduck.util.bare
import org.ntust.app.tigerduck.util.set
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An import ends with the account it was opened under, however soon the next signs in. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableImportFollowsTheAccountTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var sessions: AccountSessions
    private lateinit var imports: ClassTableImportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val credentials = CredentialManager(context.getSharedPreferences("credentials-test", 0))
        credentials.ntustStudentId = "B00015999"
        val auth = bare<AuthService>().also {
            it.set("authState", MutableStateFlow(true))
            it.set("credentials", credentials)
        }
        sessions = testSessions(context)
        sessions.school.signedInFrom(MutableStateFlow(true))
        imports = ClassTableImportViewModel(
            context,
            ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { "B00015999" }, Dispatchers.Unconfined),
            auth,
            bare(),
            sessions,
        )
    }

    @After
    fun tearDown() {
        imports.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a sign-out and a sign-in landing before it runs are both seen`() {
        dispatcher.scheduler.runCurrent()
        imports.start()
        assertNotNull(imports.session.value)

        sessions.emit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, sessions.school.signIns.current, null))
        sessions.emit(AccountEvent.SignedIn(Account.SCHOOL, byHand = false))
        dispatcher.scheduler.runCurrent()

        assertNull(imports.session.value)
    }
}
