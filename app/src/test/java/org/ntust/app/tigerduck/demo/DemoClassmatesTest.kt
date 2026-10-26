package org.ntust.app.tigerduck.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import java.time.Instant
import org.ntust.app.tigerduck.util.JvmContext
import android.content.Context

/** The demo sign-in imports each classmate as their share code would, under the demo account. */
class DemoClassmatesTest {

    private val context: Context = JvmContext()
    private var account: String? = null
    private fun store() = ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { account }, Dispatchers.Unconfined)

    private val sharedAt = Instant.parse("2026-10-14T11:30:00Z")
    private val probability = Course(courseNo = "MA2012301", courseName = "Probability")
    private val handTyped = Course(courseNo = "not a code", courseName = "Club")

    @Test
    fun `each classmate is imported on the term with the courses a share code can carry`() = runTest {
        val store = store()

        importDemoClassmates(
            store, "1151",
            listOf(DemoFixture.Classmate("B11308965", "Duckling", listOf(probability, handTyped))),
            sharedAt,
        )

        val person = store.tables.value.people.single()
        assertEquals("Duckling", person.nickname)
        assertEquals("B11308965", person.studentId)
        assertEquals(sharedAt, person.sharedAt)
        assertEquals(listOf(listOf("MA2012301")), person.terms.map { it.courseNos })
        assertEquals(listOf(probability), person.courses("1151"))
    }

    @Test
    fun `the classmates belong to the account signed in when they are imported`() = runTest {
        account = "B11308964"
        val store = store()

        importDemoClassmates(
            store, "1151",
            listOf(DemoFixture.Classmate("B11308965", "Duckling", listOf(probability))),
            sharedAt,
        )

        assertEquals(listOf("Duckling"), store().load().people.map { it.nickname })
        account = "B11399999"
        assertEquals(emptyList<String>(), store().load().people.map { it.nickname })
    }
}
