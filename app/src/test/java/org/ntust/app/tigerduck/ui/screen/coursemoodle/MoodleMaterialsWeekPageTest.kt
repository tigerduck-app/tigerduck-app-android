package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Type

/**
 * A course's materials open at this week's section, with its header marked. Ported from iOS
 * `MoodleMaterialsWeekPageTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleMaterialsWeekPageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val today = AppClock.instant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate()

    /** A weekly course started seven weeks ago, so today is in its eighth week. */
    private val weeklyCourse = MoodleCourseSummary(
        "", "", emptyList(), "weeks",
        today.minusWeeks(7).atStartOfDay(AppConstants.TAIPEI_ZONE).toEpochSecond(),
    )

    /**
     * With [datedAsWeek], each name also carries its week's first day, counted so that today falls
     * in that week rather than in the eighth the start date gives.
     */
    private fun sectionName(week: Int, datedAsWeek: Int? = null): String {
        if (week == 0) return "General"
        datedAsWeek ?: return "Week $week"
        val day = today.plusWeeks((week - datedAsWeek).toLong())
        return "Week $week ${day.monthValue}/${day.dayOfMonth}"
    }

    private fun materials(datedAsWeek: Int? = null, from: Int = 0) = MoodleCourseMaterials(
        (from..16).map { week ->
            MoodleMaterialSection(
                100 + week, sectionName(week, datedAsWeek),
                listOf(MoodleMaterial(9000 + week, 300 + week, MoodleMaterial.URL, "Slides $week", emptyList(), "https://www.example.com/w$week")),
                week,
            )
        },
    )

    private inner class Api(
        private val materials: MoodleCourseMaterials,
        private val materialsGate: CompletableDeferred<Unit>? = null,
        var summaryGate: CompletableDeferred<Unit>? = null,
        private val materialsFail: Boolean = false,
        /** How many summary fetches fail before one answers, each with the same error. */
        private var summaryFailures: Int = 0,
    ) : MoodleCourseApi {
        private val summaryError = MoodleWebserviceError.SiteMaintenance()

        override suspend fun functions() = setOf(MoodleCourseApi.CONTENTS_FUNCTION, MoodleCourseApi.COURSES_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(null, emptyList())
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun download(file: MoodleFile, target: File, maxBytes: Long, progress: (Float) -> Unit) = Unit
        override suspend fun materials(courseId: Int): MoodleCourseMaterials {
            materialsGate?.await()
            if (materialsFail) throw MoodleWebserviceError.SiteMaintenance()
            return materials
        }
        override suspend fun summary(courseId: Int): MoodleCourseSummary {
            summaryGate?.await()
            if (summaryFailures > 0) {
                summaryFailures--
                // One object for every failure, as Moodle's token renewal throws again in its pause.
                throw summaryError
            }
            return weeklyCourse
        }
    }

    /**
     * Copies of the course's [materials], fetched at [materialsFetchedAtMs], and [summary], if any;
     * a summary read waits for [summaryRead].
     */
    private fun cache(
        materials: MoodleCourseMaterials?,
        summary: MoodleCourseSummary?,
        summaryRead: CompletableDeferred<Unit>? = null,
        materialsFetchedAtMs: Long = System.currentTimeMillis(),
    ) = object : MoodleCachePort {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = when (name) {
            "courses/5/materials" -> materials?.let { it to materialsFetchedAtMs }
            "courses/5/summary" -> {
                summaryRead?.await()
                summary?.let { it to System.currentTimeMillis() }
            }
            else -> null
        } as Pair<T, Long>?
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private fun store(api: MoodleCourseApi, cache: MoodleCachePort) =
        MoodleCourseStore(5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache, { true }), scope)

    private fun show(store: MoodleCourseStore) = composeRule.setContent {
        MaterialsList(store, rememberMoodleFileOpener(store, ""), browserPreference = "")
    }

    private fun shows(label: String) = composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTopHeader(label: String) {
        val height = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().firstOrNull()
                ?.let { it.boundsInRoot.top < height / 3 } ?: false
        }
    }

    @Test
    fun `the materials open at this week's section, whose header alone is marked`() {
        val materials = materials()
        show(store(Api(materials), cache(materials, weeklyCourse)))

        waitForTopHeader("Week 8")

        composeRule.onNodeWithText("Week 8")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithText("Week 9")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    fun `materials that arrive after the page is shown still open at this week`() {
        val gate = CompletableDeferred<Unit>()
        val store = store(Api(materials(), materialsGate = gate), cache(null, weeklyCourse))
        show(store)
        composeRule.waitUntil(5_000) { store.materials.state.value.isRefreshing }

        gate.complete(Unit)

        waitForTopHeader("Week 8")
    }

    @Test
    fun `a summary still loading holds the scroll, so its weekly format wins over the names' dates`() {
        val gate = CompletableDeferred<Unit>()
        val materials = materials(datedAsWeek = 3)
        val store = store(Api(materials, summaryGate = gate), cache(materials, null))
        scope.launch { store.refreshSummary() }
        composeRule.waitUntil(5_000) { store.summary.state.value.isRefreshing }
        show(store)
        composeRule.waitUntil(5_000) { shows("General") }

        gate.complete(Unit)

        waitForTopHeader(sectionName(8, datedAsWeek = 3))
    }

    @Test
    fun `a cached summary still being read holds the scroll too`() {
        val read = CompletableDeferred<Unit>()
        val materials = materials(datedAsWeek = 3)
        show(store(Api(materials), cache(materials, weeklyCourse, summaryRead = read)))
        composeRule.waitUntil(5_000) { shows("General") }

        read.complete(Unit)

        waitForTopHeader(sectionName(8, datedAsWeek = 3))
    }

    @Test
    fun `a failure note that comes in above the sections does not count as the reader scrolling`() {
        val gate = CompletableDeferred<Unit>()
        val materials = materials()
        val store = store(
            Api(materials, summaryGate = gate, materialsFail = true),
            cache(materials, null, materialsFetchedAtMs = 0L),
        )
        scope.launch { store.refreshSummary() }
        composeRule.waitUntil(5_000) { store.summary.state.value.isRefreshing }
        show(store)
        composeRule.waitUntil(5_000) { shows(composeRule.activity.getString(R.string.error_moodle_site_maintenance)) }

        gate.complete(Unit)

        waitForTopHeader("Week 8")
    }

    @Test
    fun `a summary the course page could not fetch is fetched here`() {
        val materials = materials()
        show(store(Api(materials), cache(materials, null)))

        waitForTopHeader("Week 8")
    }

    @Test
    fun `a cached summary does not hold the scroll while it refreshes`() {
        val gate = CompletableDeferred<Unit>()
        val materials = materials()
        val store = store(Api(materials, summaryGate = gate), cache(materials, weeklyCourse))
        scope.launch { store.refreshSummary() }
        composeRule.waitUntil(5_000) { store.summary.state.value.isRefreshing }
        show(store)

        waitForTopHeader("Week 8")
        gate.complete(Unit)
    }

    @Test
    fun `the page waits for its own first fetch of a summary it has no copy of`() {
        val gate = CompletableDeferred<Unit>()
        val materials = materials(datedAsWeek = 3)
        show(store(Api(materials, summaryGate = gate), cache(materials, null)))
        composeRule.waitUntil(5_000) { shows("General") }

        gate.complete(Unit)

        waitForTopHeader(sectionName(8, datedAsWeek = 3))
    }

    @Test
    fun `a failure the course page left does not stand for this page's own fetch`() {
        val materials = materials(datedAsWeek = 3)
        val api = Api(materials, summaryFailures = 1)
        val store = store(api, cache(materials, null))
        scope.launch { store.refreshSummary() }
        composeRule.waitUntil(5_000) { store.summary.state.value.failure != null }
        val gate = CompletableDeferred<Unit>()
        api.summaryGate = gate
        show(store)
        composeRule.waitUntil(5_000) { shows("General") }

        gate.complete(Unit)

        waitForTopHeader(sectionName(8, datedAsWeek = 3))
    }

    @Test
    fun `a fetch here that fails as the one before did still ends the wait`() {
        val materials = materials(datedAsWeek = 3)
        val api = Api(materials, summaryFailures = 2)
        val store = store(api, cache(materials, null))
        scope.launch { store.refreshSummary() }
        composeRule.waitUntil(5_000) { store.summary.state.value.failure != null }
        val gate = CompletableDeferred<Unit>()
        api.summaryGate = gate
        show(store)
        composeRule.waitUntil(5_000) { store.summary.state.value.isRefreshing }

        gate.complete(Unit)

        waitForTopHeader(sectionName(3, datedAsWeek = 3))
    }

    @Test
    fun `a section a refresh adds above the top one does not count as the reader scrolling`() {
        val gate = CompletableDeferred<Unit>()
        val store = store(
            Api(materials(), summaryGate = gate),
            cache(materials(from = 1), null, materialsFetchedAtMs = 0L),
        )
        show(store)
        composeRule.waitUntil(5_000) { store.materials.state.value.value?.sections?.firstOrNull()?.name == "General" }
        composeRule.waitForIdle()

        gate.complete(Unit)

        waitForTopHeader("Week 8")
    }

    @Test
    fun `a page that found no week stays put when the summary comes later`() {
        val materials = materials()
        val store = store(Api(materials, summaryFailures = 1), cache(materials, null))
        show(store)
        composeRule.waitUntil(5_000) { store.summary.state.value.failure != null }
        composeRule.waitForIdle()

        scope.launch { store.refreshSummary() }
        composeRule.waitUntil(5_000) { store.summary.state.value.value != null }
        composeRule.waitForIdle()

        assertTopHeader("General")
    }

    /** Shows the course, its summary on its way until [scroll] is done, then lets it in. */
    private fun scrollBeforeTheSummary(scroll: () -> Unit) {
        val gate = CompletableDeferred<Unit>()
        val materials = materials()
        val store = store(Api(materials, summaryGate = gate), cache(materials, null))
        scope.launch { store.refreshSummary() }
        composeRule.waitUntil(5_000) { store.summary.state.value.isRefreshing }
        show(store)
        composeRule.waitUntil(5_000) { shows("General") }
        scroll()
        gate.complete(Unit)
        composeRule.waitUntil(5_000) { !store.summary.state.value.isRefreshing }
        composeRule.waitForIdle()
    }

    private fun assertTopHeader(label: String) {
        val height = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val top = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        assertTrue("$label stays at the top", top < height / 3)
    }

    private val list get() = composeRule.onNode(hasScrollToIndexAction())

    @Test
    fun `a list the reader has already scrolled is left where it is`() {
        scrollBeforeTheSummary {
            list.performScrollToIndex(2)
            waitForTopHeader("Week 2")
        }
        assertTopHeader("Week 2")
    }

    @Test
    fun `a reader one section down is left there`() {
        scrollBeforeTheSummary {
            list.performScrollToIndex(1)
            waitForTopHeader("Week 1")
        }
        assertTopHeader("Week 1")
    }

    @Test
    fun `a reader who scrolled and came back to the top is left there`() {
        scrollBeforeTheSummary {
            list.performScrollToIndex(4)
            waitForTopHeader("Week 4")
            list.performScrollToIndex(0)
            waitForTopHeader("General")
        }
        assertTopHeader("General")
    }
}
