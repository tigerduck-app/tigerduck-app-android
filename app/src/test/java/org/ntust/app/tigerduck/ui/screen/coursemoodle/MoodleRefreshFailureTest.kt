package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Type

/** A failed Moodle fetch says why, with Retry, whether or not a copy shows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleRefreshFailureTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private var failing = true
    private var gradeCalls = 0
    private val graded = MoodleGradeItem(1, "HW1", "mod", "assign", 31, "95", 100.0, false, "")

    /** Moodle under maintenance until [failing] is cleared. */
    private val api = object : MoodleCourseApi {
        private fun check() {
            if (failing) throw MoodleWebserviceError.SiteMaintenance()
        }
        override suspend fun functions(): Set<String> {
            check()
            return setOf(MoodleCourseApi.GRADE_ITEMS_FUNCTION)
        }
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(null, emptyList())
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int): List<MoodleGradeItem> {
            gradeCalls++
            return listOf(graded)
        }
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: org.ntust.app.tigerduck.network.model.moodle.MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
    }

    private fun cache(grades: MoodleCourseGrades?) = object : MoodleCachePort {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? =
            if (name == "courses/5/grades" && grades != null) (grades to 0L) as Pair<T, Long> else null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private fun store(grades: MoodleCourseGrades?) =
        MoodleCourseStore(5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache(grades), { true }), scope)

    private fun text(id: Int) = composeRule.activity.getString(id)

    @Test
    fun `the section's first load failing says why, with Retry, rather than showing nothing`() {
        val store = store(grades = null)
        composeRule.setContent { MoodleCourseSection(store = store, browserPreference = "", onOpenRoute = {}) }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text(R.string.error_moodle_site_maintenance)).fetchSemanticsNodes().isNotEmpty()
        }
        failing = false
        composeRule.onNodeWithText(text(R.string.action_retry)).performClick()

        composeRule.waitUntil(5_000) { store.sectionRows.value.isNotEmpty() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text(R.string.error_moodle_site_maintenance)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `a failed refresh shows beside the section's cached rows`() {
        val store = store(grades = MoodleCourseGrades(listOf(graded)))
        composeRule.setContent { MoodleCourseSection(store = store, browserPreference = "", onOpenRoute = {}) }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text(R.string.error_moodle_site_maintenance)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(R.string.course_detail_moodle_grades_label), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a list keeps its copy and offers Retry when its refresh fails`() {
        val store = store(grades = MoodleCourseGrades(listOf(graded)))
        composeRule.setContent { GradesList(store = store, browserPreference = "") }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text(R.string.error_moodle_site_maintenance)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("HW1", useUnmergedTree = true).assertExists()
        failing = false
        composeRule.onNodeWithText(text(R.string.action_retry)).performClick()

        composeRule.waitUntil(5_000) { gradeCalls == 1 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text(R.string.error_moodle_site_maintenance)).fetchSemanticsNodes().isEmpty()
        }
    }
}
