@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A term's upload keeps to the order the backend's DELETEs run in ([PendingCourseAdds.removing]),
 * as an add's does: one past a DELETE of its term would bring the courses back.
 */
class PendingCourseAddsUploadingTest {

    @Test
    fun `an upload under way lands before a DELETE, and one begun after the DELETE waits for it`() = runTest {
        val context = JvmContext()
        val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> emptyList() }, signInGeneration = { 1 })
        val order = mutableListOf<String>()
        val uploadGate = CompletableDeferred<Unit>()
        val deleteGate = CompletableDeferred<Unit>()

        val upload = launch {
            adds.uploading {
                order += "upload begins"
                uploadGate.await()
                order += "upload lands"
            }
        }
        runCurrent()
        val delete = launch {
            adds.removing {
                order += "DELETE begins"
                deleteGate.await()
                order += "DELETE lands"
            }
        }
        runCurrent()
        val later = launch { adds.uploading { order += "later upload" } }
        runCurrent()
        assertEquals(listOf("upload begins"), order)

        uploadGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("upload begins", "upload lands", "DELETE begins"), order)

        deleteGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("upload begins", "upload lands", "DELETE begins", "DELETE lands", "later upload"), order)
        joinAll(upload, delete, later)
    }
}
