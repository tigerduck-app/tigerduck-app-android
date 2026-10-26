package org.ntust.app.tigerduck.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AppFeature.CALENDAR
import org.ntust.app.tigerduck.data.model.AppFeature.CLASS_TABLE
import org.ntust.app.tigerduck.data.model.AppFeature.HOME
import org.ntust.app.tigerduck.data.model.AppFeature.SCHOOL_MAIL
import org.ntust.app.tigerduck.data.model.AppFeature.SCORE
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [settleBottomBar], the whole startup sequence, against what each older
 * build leaves behind: which one-shot checks it ran, and a stored bar or none.
 */
class BottomBarStartupTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context = JvmContext()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    @Test
    fun `a 2_2_x install that never changed its bar keeps calendar, for What's New to ask about`() {
        prefs.hasCompletedOnboarding = true

        settleBottomBar(prefs)

        assertEquals(AppFeature.previousDefaultTabs, prefs.configuredTabs)
    }

    @Test
    fun `a 2_3_x install that never changed its bar keeps it, without library`() {
        prefs.hasCompletedOnboarding = true
        prefs.previousDefaultTabsChecked = true

        settleBottomBar(prefs)

        assertEquals(listOf(HOME, CLASS_TABLE, SCHOOL_MAIL), prefs.configuredTabs)
    }

    @Test
    fun `a new install follows the default, library and all, from its first launch on`() {
        settleBottomBar(prefs)
        prefs.hasCompletedOnboarding = true
        settleBottomBar(prefs)

        assertFalse(prefs.hasStoredConfiguredTabs)
        assertEquals(AppFeature.defaultTabs, prefs.configuredTabs)
    }

    @Test
    fun `a bar the user set up stays as it is`() {
        prefs.hasCompletedOnboarding = true
        prefs.configuredTabs = listOf(HOME, SCORE, CALENDAR)

        settleBottomBar(prefs)

        assertEquals(listOf(HOME, SCORE, CALENDAR), prefs.configuredTabs)
    }

    @Test
    fun `a full reset's fresh start keeps the new default after onboarding again`() {
        // FullReset marks the one-shot checks done.
        prefs.previousDefaultTabsChecked = true
        prefs.libraryTabKeepChecked = true
        prefs.hasCompletedOnboarding = true

        settleBottomBar(prefs)

        assertFalse(prefs.hasStoredConfiguredTabs)
        assertEquals(AppFeature.defaultTabs, prefs.configuredTabs)
    }

    @Test
    fun `an unfinished feature leaves the bar, and nothing else changes`() {
        val unfinished = AppFeature.unfinishedFeatures.firstOrNull() ?: return
        prefs.hasCompletedOnboarding = true
        prefs.configuredTabs = listOf(HOME, unfinished, SCORE)

        settleBottomBar(prefs)

        assertEquals(listOf(HOME, SCORE), prefs.configuredTabs)
    }
}
