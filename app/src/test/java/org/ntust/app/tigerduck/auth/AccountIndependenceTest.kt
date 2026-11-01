package org.ntust.app.tigerduck.auth

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.library.LibraryQRCache
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * The three accounts sign in and out on their own (decided for Android on 2026-10-22): each
 * account's sign-out leaves the other two, their credentials and what they keep. The school's
 * sign-out over the real services is pinned beside the rest of the school's state in
 * `AccountSwitchTest`; here are the library's and the mail's, and the school's credentials half.
 *
 * Stand-ins for what each account keeps: the school's class-table term in [AppPreferences], the
 * mail's cache folder, and the library's QR code in memory.
 */
class AccountIndependenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = JvmContext()
    private val appScope = testApplicationScope()
    private lateinit var credentials: CredentialManager
    private lateinit var prefs: AppPreferences
    private lateinit var mailCache: MailCache
    private lateinit var mailFile: File
    private val qr = LibraryQRCache<String>()

    @After
    fun stopAppScope() = appScope.cancelAndJoinBlocking()

    @Before
    fun signedInToAllThree() {
        // Plain preferences: the JVM has no Android keystore for the encrypted ones.
        credentials = CredentialManager(context.getSharedPreferences("credentials-independence-test", Context.MODE_PRIVATE))
        prefs = AppPreferences(context)
        credentials.ntustStudentId = SCHOOL
        credentials.ntustPassword = "school-password"
        credentials.mailStudentId = MAIL
        credentials.mailPassword = "mail-password"
        credentials.saveLibrarySignIn(
            credentials.libraryAccountGeneration, LIBRARY, "library-password", "library-token", Long.MAX_VALUE, byUser = true,
        )
        prefs.classTableSelectedSemester = "1141"
        mailCache = MailCache(File(tmp.root, "mail"))
        mailCache.attachmentsDir.mkdirs()
        mailFile = File(mailCache.attachmentsDir, "note.txt").apply { writeText("kept") }
        qr.store("payload", null, account = 1, issuedAt = 0L)
    }

    private fun mailAccount() = MailAccount(
        credentials, InMemoryMailStateStore(), FakeMailServer().factory(), mailCache, FakeDemoGate(), schoolMailSite(),
        RecordingScheduler(), RecordingNotifier(), appScope,
    )

    private fun assertSchoolKept() {
        assertEquals(SCHOOL, credentials.ntustStudentId)
        assertEquals("school-password", credentials.ntustPassword)
        assertEquals("1141", prefs.classTableSelectedSemester)
    }

    private fun assertMailKept() {
        assertEquals(MAIL, credentials.mailStudentId)
        assertEquals("mail-password", credentials.mailPassword)
        assertTrue(mailFile.exists())
    }

    private fun assertLibraryKept() {
        assertEquals(LIBRARY, credentials.libraryUsername)
        assertEquals("library-password", credentials.libraryPassword)
        assertEquals("library-token", credentials.libraryToken)
        assertNotNull(qr.reusable(account = 1, now = 1L))
    }

    @Test
    fun `the school's sign-out leaves the mail and the library`() {
        credentials.signOutSchoolAccount()

        assertEquals(null, credentials.ntustStudentId)
        assertEquals(null, credentials.ntustPassword)
        assertMailKept()
        assertLibraryKept()
    }

    @Test
    fun `the library's sign-out leaves the school and the mail`() {
        credentials.clearLibraryCredentials()

        assertEquals(null, credentials.libraryUsername)
        assertEquals(null, credentials.libraryPassword)
        assertSchoolKept()
        assertMailKept()
    }

    @Test
    fun `the mail's sign-out leaves the school and the library`() {
        mailAccount().signOut()

        assertEquals(null, credentials.mailStudentId)
        assertEquals(null, credentials.mailPassword)
        assertSchoolKept()
        assertLibraryKept()
    }

    private companion object {
        const val SCHOOL = "B00015999"
        const val MAIL = "B00015998"
        const val LIBRARY = "B00015997"
    }
}
