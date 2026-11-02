package org.ntust.app.tigerduck.auth.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each reason signs out, and in which order: today's, as Settings, School Mail's page, a move
 * to another backend and the full reset each ran their own until the plans took them over.
 */
class SignOutPlansTest {

    private val steps = mutableListOf<String>()
    private var school = true
    private var mail = true
    private var library = true

    private fun plans() = SignOutPlans(
        school = object : SchoolPlan by NoSchool {
            override val held: Boolean get() = school

            override fun signOut(reason: SignOutReason) {
                steps += "school $reason"
                school = false
            }

            override fun finishOwedSignOut(): Boolean {
                steps += "owed sign-out"
                return true
            }

            override fun finishOwedWipe() {
                steps += "owed wipe"
            }
        },
        mail = AccountPlan({ mail }) { reason ->
            steps += "mail $reason"
            mail = false
        },
        library = AccountPlan({ library }) { reason ->
            steps += "library $reason"
            library = false
        },
        readLibraryAgain = { steps += "library read again" },
        leaveDemoLibrary = { steps += "demo library" },
    )

    private fun signedOut(reason: SignOutReason): List<String> {
        steps.clear()
        plans().signOut(reason)
        return steps.toList()
    }

    @Test
    fun `each reason signs out the accounts it names, in today's order, and no other`() {
        val expected = mapOf(
            // Settings' Sign out, which reads the library again, as the school's leaves it.
            SignOutReason.SCHOOL to listOf("school SCHOOL", "library read again"),
            SignOutReason.MAIL to listOf("mail MAIL"),
            SignOutReason.LIBRARY to listOf("library LIBRARY"),
            SignOutReason.ENDPOINT_CHANGE to listOf(
                "school ENDPOINT_CHANGE", "library read again", "library ENDPOINT_CHANGE", "mail ENDPOINT_CHANGE",
            ),
            // The library account itself goes with the credentials, in the reset's own wipe.
            SignOutReason.FULL_RESET to listOf("mail FULL_RESET", "school FULL_RESET", "demo library"),
        )
        assertEquals(SignOutReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, wanted) ->
            school = true
            mail = true
            library = true
            assertEquals(reason.name, wanted, signedOut(reason))
        }
    }

    @Test
    fun `an endpoint change signs out only the accounts held, each asked as its turn comes`() {
        school = false
        library = false
        assertEquals(listOf("mail ENDPOINT_CHANGE"), signedOut(SignOutReason.ENDPOINT_CHANGE))

        school = true
        library = true
        mail = false
        assertEquals(
            listOf("school ENDPOINT_CHANGE", "library read again", "library ENDPOINT_CHANGE"),
            signedOut(SignOutReason.ENDPOINT_CHANGE),
        )
    }

    @Test
    fun `an account's own sign-out and a full reset go whether the account is held or not, as today`() {
        school = false
        mail = false
        library = false
        assertEquals(listOf("school SCHOOL", "library read again"), signedOut(SignOutReason.SCHOOL))
        assertEquals(listOf("mail MAIL"), signedOut(SignOutReason.MAIL))
        assertEquals(listOf("library LIBRARY"), signedOut(SignOutReason.LIBRARY))
        assertEquals(listOf("mail FULL_RESET", "school FULL_RESET", "demo library"), signedOut(SignOutReason.FULL_RESET))
    }

    @Test
    fun `whether an account is held is its own plan's answer`() {
        val plans = plans()
        mail = false
        assertTrue(plans.holds(Account.SCHOOL))
        assertFalse(plans.holds(Account.MAIL))
        assertTrue(plans.holds(Account.LIBRARY))
        school = false
        assertFalse(plans.holds(Account.SCHOOL))
    }

    @Test
    fun `the start finishes an owed sign-out through the school's plan`() {
        val plans = plans()
        assertTrue(plans.finishOwedSignOut())
        plans.finishOwedWipe()
        assertEquals(listOf("owed sign-out", "owed wipe"), steps)
    }
}
