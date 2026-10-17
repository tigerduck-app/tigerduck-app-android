package org.ntust.app.tigerduck.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleRoutes

class CourseDetailSheetRulesTest {

    // As the sheet's back stack lists them: its graph, which has no route, then its pages.
    private val detail = listOf(null, MoodleRoutes.COURSE_DETAIL)

    @Test
    fun `the course detail alone keeps the height the sheet has`() {
        assertFalse(CourseDetailSheetRules.expands(detail))
    }

    @Test
    fun `a page pushed over the course detail opens the sheet to full height`() {
        assertTrue(CourseDetailSheetRules.expands(detail + MoodleRoutes.MEMBERS))
    }

    @Test
    fun `the sheet closes over the course detail and its Moodle pages`() {
        assertTrue(CourseDetailSheetRules.mayClose(detail))
        assertTrue(CourseDetailSheetRules.mayClose(detail + MoodleRoutes.MEMBERS + MoodleRoutes.MEMBER))
    }

    @Test
    fun `a mail being written keeps the sheet open`() {
        assertFalse(CourseDetailSheetRules.mayClose(detail + MoodleRoutes.MEMBER + MailRoutes.COMPOSE))
    }

    @Test
    fun `an assignment reminder closes the sheet first, unless a mail is being written in it`() {
        val page = Screen.Assignment.route("900001")
        assertTrue(CourseDetailSheetRules.closesForLink(page, detail))
        assertTrue(CourseDetailSheetRules.closesForLink(page, detail + MoodleRoutes.MEMBERS + MoodleRoutes.MEMBER))
        assertFalse("the draft stays, and the page opens over it", CourseDetailSheetRules.closesForLink(page, detail + MoodleRoutes.MEMBER + MailRoutes.COMPOSE))
        assertFalse("another link leaves the sheet as it is", CourseDetailSheetRules.closesForLink(Screen.ClassTable.route, detail))
        assertFalse(CourseDetailSheetRules.closesForLink(null, detail))
    }
}

class CourseDetailSheetModelsTest {

    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private fun probeIn(models: CourseDetailSheetModels): Probe =
        ViewModelProvider.create(models, ViewModelProvider.NewInstanceFactory())[Probe::class]

    @Test
    fun `closing the sheet clears the view models of its pages`() {
        val models = CourseDetailSheetModels()
        val probe = probeIn(models)

        models.close()

        assertTrue(probe.cleared)
    }

    @Test
    fun `the sheet opened next gets view models of its own`() {
        val models = CourseDetailSheetModels()
        val first = probeIn(models)
        models.close()

        assertTrue(probeIn(models) !== first)
    }

    @Test
    fun `leaving the class table clears the view models of an open sheet`() {
        val page = ViewModelStore()
        val models = ViewModelProvider.create(page, ViewModelProvider.NewInstanceFactory())[CourseDetailSheetModels::class]
        val probe = probeIn(models)

        page.clear()

        assertTrue(probe.cleared)
    }
}
