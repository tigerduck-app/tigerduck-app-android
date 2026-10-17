package org.ntust.app.tigerduck.data.preferences

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.model.WidgetItem

/**
 * [restoreHomeSections] is the read side of `AppPreferences.homeSections`,
 * pulled out so it runs on the plain JVM (the getter needs a real
 * `SharedPreferences`; see [AppPreferencesTest]).
 *
 * The JSON here is what earlier builds wrote, byte for byte where it
 * matters: a layout is saved once and read by every build after it, so
 * each of these shapes is on some phone today.
 */
class HomeSectionRestoreTest {

    private val gson = Gson()

    private fun restore(json: String?) = restoreHomeSections(json, gson)

    @Test
    fun `nothing stored yields the defaults`() {
        assertEquals(HomeSection.defaults(), restore(null))
    }

    @Test
    fun `an empty or unreadable layout yields the defaults`() {
        assertEquals(HomeSection.defaults(), restore("[]"))
        assertEquals(HomeSection.defaults(), restore("not json"))
        assertEquals(HomeSection.defaults(), restore("{\"id\":\"x\"}"))
    }

    @Test
    fun `a stored Quick widgets section is kept`() {
        // 2.4 and earlier filtered QUICK_WIDGETS out on read ("feature
        // temporarily disabled"); the section was left in the JSON, so once
        // the grid ships the layout the user saved comes back as saved.
        val json = """
            [
              {"id":"today-courses","type":"TODAY_COURSES","title":"今日課程","sortOrder":0,"isVisible":true,"widgets":[]},
              {"id":"quick-widgets","type":"QUICK_WIDGETS","title":"快速功能","sortOrder":1,"isVisible":true,
               "widgets":[{"id":"w2","feature":"EMPTY_CLASSROOM"}]},
              {"id":"upcoming-assignments","type":"UPCOMING_ASSIGNMENTS","title":"待辦作業","sortOrder":2,"isVisible":true,"widgets":[]}
            ]
        """
        val out = restore(json)
        assertEquals(listOf("today-courses", "quick-widgets", "upcoming-assignments"), out.map { it.id })
        assertEquals(HomeSection.HomeSectionType.QUICK_WIDGETS, out[1].type)
        assertEquals(listOf(WidgetItem("w2", AppFeature.EMPTY_CLASSROOM)), out[1].widgets)
        assertEquals(listOf(0, 1, 2), out.map { it.sortOrder })
    }

    @Test
    fun `the first default layout's GPA shortcut reads back as Scores`() {
        // The four-widget default of versionCode 5 and earlier saved
        // `"feature":"GPA"`; the constant has been SCORE since. Gson maps an
        // unknown enum name to null, which the grid would dereference.
        val json = """
            [{"id":"quick-widgets","type":"QUICK_WIDGETS","title":"快速功能","sortOrder":0,"isVisible":true,
              "widgets":[{"id":"w1","feature":"FREE_LUNCH"},{"id":"w4","feature":"GPA"}]}]
        """
        val out = restore(json)
        assertEquals(
            listOf(WidgetItem("w1", AppFeature.FREE_LUNCH), WidgetItem("w4", AppFeature.SCORE)),
            out.single().widgets,
        )
    }

    @Test
    fun `a shortcut whose feature this build does not know is dropped`() {
        // The one way a null lands behind WidgetItem.feature's non-null type;
        // a layout must not become unreadable because a feature was renamed.
        val json = """
            [{"id":"qw","type":"QUICK_WIDGETS","title":"Q","sortOrder":0,"isVisible":true,
              "widgets":[{"id":"w1","feature":"SCORE"},{"id":"w2","feature":"TIME_MACHINE"},{"id":"w3"}]}]
        """
        assertEquals(listOf(WidgetItem("w1", AppFeature.SCORE)), restore(json).single().widgets)
    }

    @Test
    fun `a section saved without widgets reads back with none`() {
        // Gson builds HomeSection without running its constructor, so the
        // `= emptyList()` default never applies to a missing key (CLAUDE.md,
        // "Upgrade-safe persistence"); the field would be null.
        val json = """[{"id":"qw","type":"CUSTOM","title":"Mine","sortOrder":0,"isVisible":true}]"""
        val out = restore(json).single()
        assertEquals(emptyList<WidgetItem>(), out.widgets)
        assertTrue(out.widgets.isEmpty())
    }

    @Test
    fun `a section of a type this build does not know is dropped`() {
        val json = """
            [{"id":"a","type":"TODAY_COURSES","title":"T","sortOrder":0,"isVisible":true,"widgets":[]},
             {"id":"b","type":"WEATHER","title":"W","sortOrder":1,"isVisible":true,"widgets":[]},
             {"id":"c","type":"CUSTOM","title":"C","sortOrder":2,"isVisible":true,"widgets":[]}]
        """
        val out = restore(json)
        assertEquals(listOf("a", "c"), out.map { it.id })
        assertEquals(listOf(0, 1), out.map { it.sortOrder })
    }

    @Test
    fun `a layout written by this build reads back equal`() {
        val layout = HomeSection.defaults() + HomeSection(
            id = "qw",
            type = HomeSection.HomeSectionType.QUICK_WIDGETS,
            title = "Quick widgets",
            sortOrder = 2,
            isVisible = true,
            widgets = listOf(WidgetItem("w1", AppFeature.ANNOUNCEMENTS), WidgetItem("w2", AppFeature.SCORE)),
        )
        assertEquals(layout, restore(gson.toJson(layout)))
    }
}
