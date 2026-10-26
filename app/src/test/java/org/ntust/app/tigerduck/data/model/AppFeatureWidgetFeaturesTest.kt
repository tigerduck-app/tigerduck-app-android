package org.ntust.app.tigerduck.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The features Home's Add dialog offers as shortcut cards. */
class AppFeatureWidgetFeaturesTest {

    @Test
    fun `only shipped features are offered as shortcuts`() {
        assertTrue(AppFeature.widgetFeatures.isNotEmpty())
        assertTrue(AppFeature.widgetFeatures.all { it.isImplemented })
    }

    @Test
    fun `the shipped shortcut features match the iPhone's list and order`() {
        // iOS `AppFeature.widgetFeatures` filtered by `isImplemented`.
        assertEquals(
            listOf(AppFeature.ANNOUNCEMENTS, AppFeature.EMPTY_CLASSROOM, AppFeature.SCORE),
            AppFeature.widgetFeatures,
        )
    }

    @Test
    fun `pages the tab bar reaches are not offered as shortcuts`() {
        // A deliberate subset on both platforms: the core pages have a tab.
        val tabPages = setOf(
            AppFeature.HOME, AppFeature.CLASS_TABLE, AppFeature.CALENDAR,
            AppFeature.SCHOOL_MAIL, AppFeature.LIBRARY, AppFeature.MORE, AppFeature.SETTINGS,
        )
        assertTrue(AppFeature.widgetFeatures.none { it in tabPages })
    }

    @Test
    fun `quick widgets and custom sections hold shortcuts, the built-in two do not`() {
        assertTrue(HomeSection.HomeSectionType.QUICK_WIDGETS.holdsShortcuts)
        assertTrue(HomeSection.HomeSectionType.CUSTOM.holdsShortcuts)
        assertTrue(!HomeSection.HomeSectionType.TODAY_COURSES.holdsShortcuts)
        assertTrue(!HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS.holdsShortcuts)
    }
}
