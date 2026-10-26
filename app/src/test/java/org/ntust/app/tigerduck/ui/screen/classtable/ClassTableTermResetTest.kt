package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Resetting a term, over real files (tigerduck-app-android#241). The backend goes first: with
 * no connection, or a session that has expired, nothing on the phone may change. The hidden
 * courses used to come back before the DELETE was even sent, and the reset said nothing.
 */
class ClassTableTermResetTest {

    private lateinit var cache: DataCache
    private lateinit var reset: ClassTableTermReset

    private val term = "1151"
    private val portal = Course(courseNo = "CS1010001", courseName = "Portal")
    private val typed = Course(courseNo = "MA1010002", courseName = "Typed", isManual = true)

    // A course hidden in this term, one hidden in another, and a legacy bare entry.
    private val tombstones = setOf("1151:EE1010003", "1142:EE1010003", "PE1010004")
    private val names = mapOf(
        "CS1010001" to mapOf("zh" to "入門"),
        "MA1010002" to mapOf("en" to "Maths"),
        "XX9990001" to mapOf("zh" to "Another term's"),
    )

    @Before
    fun setUp() = runTest {
        val context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        reset = ClassTableTermReset(cache, clock = { 42L })
        cache.saveCourses(listOf(portal, typed), term)
        cache.saveDeletedCourseNos(tombstones)
        cache.saveCourseCustomNames(names)
        cache.saveServerKnownNos(mapOf(term to listOf("CS1010001"), "1142" to listOf("EE1010003")))
    }

    @Test
    fun `a reset the server could not take changes nothing on the phone`() = runTest {
        for (failure in listOf(IOException("offline"), PushApiException("deleteAllCourses failed: HTTP 401"))) {
            assertNull(reset.run(term) { throw failure })

            assertEquals(tombstones, cache.loadDeletedCourseNos())
            assertEquals(listOf("CS1010001", "MA1010002"), cache.loadCourses(term).map { it.courseNo })
            assertEquals(names, cache.loadCourseCustomNames())
            assertEquals(emptyMap<String, Long>(), cache.loadSemesterResetAt())
            assertEquals(listOf("CS1010001"), cache.loadServerKnownNos()[term])
        }
    }

    @Test
    fun `a reset the server took forgets the term here, its custom names included`() = runTest {
        val held = reset.run(term) {}

        assertEquals(setOf("CS1010001", "MA1010002"), held)
        assertEquals("the other term's hiding stays", setOf("1142:EE1010003"), cache.loadDeletedCourseNos())
        assertEquals(emptyList<Course>(), cache.loadCourses(term))
        assertEquals(mapOf("XX9990001" to mapOf("zh" to "Another term's")), cache.loadCourseCustomNames())
        assertEquals(42L, cache.loadSemesterResetAt()[term])
        assertNull(cache.loadServerKnownNos()[term])
        assertEquals(listOf("EE1010003"), cache.loadServerKnownNos()["1142"])
    }
}
