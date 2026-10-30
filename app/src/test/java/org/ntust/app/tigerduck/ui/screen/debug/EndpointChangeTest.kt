package org.ntust.app.tigerduck.ui.screen.debug

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.ui.screen.settings.SignOutLoss
import java.io.File

/**
 * The API endpoint setting asks before it moves the app to another backend, and signs out of
 * every account held before it stores the endpoint, as the iPhone's does (tigerduck-app#745).
 */
class EndpointChangeTest {

    private var stored: String? = null
    private var school = false
    private var library = false
    private var mail = false
    private val events = mutableListOf<String>()

    private fun change(movedBackend: () -> Unit = {}) = EndpointChange(
        stored = { stored },
        store = { stored = it; events += "store $it" },
        holdsSchoolAccount = { school },
        signOutLosses = { events += "losses read"; setOf(SignOutLoss.IMPORTED_CLASS_TABLES) },
        signOutSchool = {
            school = false
            events += "school"
        },
        holdsLibraryAccount = { library },
        signOutLibrary = {
            library = false
            events += "library"
        },
        holdsMailAccount = { mail },
        signOutMail = {
            mail = false
            events += "mail"
        },
        registerAgain = { events += "register" },
        movedBackend = movedBackend,
    )

    // When it asks

    @Test
    fun `the endpoint in use is saved without asking`() {
        assertFalse(change().asks(null))
        assertFalse(change().asks(BuildConfig.PUSH_BASE_URL))

        stored = OTHER
        assertFalse(change().asks(OTHER))
        assertFalse(change().asks("$OTHER/"))
        assertFalse(change().asks("https://OTHER.example:443/v3"))
    }

    @Test
    fun `another endpoint asks, and so does Reset over one in use`() {
        assertTrue(change().asks(OTHER))

        stored = OTHER
        assertTrue(change().asks(null))
        assertTrue(change().asks("https://third.example/v3"))
    }

    @Test
    fun `Reset over a stored endpoint the app no longer uses asks nothing`() {
        // Plain HTTP to a public host: the default is what the app talks to.
        stored = "http://backend.example/v3"

        assertFalse(change().asks(null))
    }

    // What it signs out

    @Test
    fun `a move to another backend fetches the school's course changes from it, and staying does not`() {
        var moved = 0
        change(movedBackend = { moved++; events += "moved" }).apply(OTHER)
        assertEquals(1, moved)
        assertTrue("after the endpoint is stored", events.indexOf("moved") > events.indexOf("store $OTHER"))
        change(movedBackend = { moved++ }).apply(OTHER)
        assertEquals(1, moved)
    }

    @Test
    fun `a change signs out of every account held before the endpoint is stored`() {
        school = true
        library = true
        mail = true

        change().apply(OTHER)

        assertEquals(listOf("school", "library", "mail", "store $OTHER", "register"), events)
    }

    @Test
    fun `an account the school sign-out took with it is not signed out again`() {
        school = true
        library = true
        val change = EndpointChange(
            stored = { stored },
            store = { stored = it; events += "store $it" },
            holdsSchoolAccount = { school },
            signOutLosses = { emptySet() },
            signOutSchool = {
                school = false
                library = false
                events += "school"
            },
            holdsLibraryAccount = { library },
            signOutLibrary = { events += "library" },
            holdsMailAccount = { false },
            signOutMail = { events += "mail" },
            registerAgain = { events += "register" },
        )

        change.apply(OTHER)

        assertEquals(listOf("school", "store $OTHER", "register"), events)
    }

    @Test
    fun `only the accounts held are signed out`() {
        mail = true

        change().apply(OTHER)

        assertEquals(listOf("mail", "store $OTHER", "register"), events)
    }

    @Test
    fun `Reset signs out before the endpoint is cleared`() {
        stored = OTHER
        school = true

        change().apply(null)

        assertEquals(listOf("school", "store null", "register"), events)
    }

    @Test
    fun `saving the endpoint in use signs nothing out`() {
        stored = OTHER
        school = true
        library = true
        mail = true

        change().apply(OTHER)

        assertEquals(listOf("store $OTHER", "register"), events)
    }

    // The question's lines

    @Test
    fun `the question names what the sign-out deletes`() = runTest {
        school = true

        assertEquals(setOf(SignOutLoss.IMPORTED_CLASS_TABLES), change().losses())
    }

    @Test
    fun `with no school account the question names nothing, and nothing is read`() = runTest {
        library = true

        assertEquals(emptySet<SignOutLoss>(), change().losses())
        assertEquals(emptyList<String>(), events)
    }

    // The one writer

    @Test
    fun `only EndpointChange stores the API endpoint`() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "app/src/main/java").isDirectory && !File(root, "src/main/java").isDirectory) {
            root = root.parentFile
        }
        val main = File(root ?: throw AssertionError("no :app module"), if (File(root, "app").isDirectory) "app/src/main/java" else "src/main/java")
        val write = Regex("""\bannouncementApiBaseUrlOverride\s*=[^=]""")
        val writers = main.walk().filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val code = line.trimStart()
                    !code.startsWith("*") && !code.startsWith("//") && write.containsMatchIn(line)
                }
            }
            .map { it.name }.toSet()

        assertEquals(setOf("EndpointChange.kt"), writers)
    }

    private companion object {
        const val OTHER = "https://other.example/v3"
    }
}
