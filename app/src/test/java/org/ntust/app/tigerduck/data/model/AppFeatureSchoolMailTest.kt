package org.ntust.app.tigerduck.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFeatureSchoolMailTest {
    @Test
    fun `school mail is a shipped academic feature addressable by its stored id`() {
        assertEquals(AppFeature.SCHOOL_MAIL, AppFeature.fromId("schoolMail"))
        assertEquals(FeatureCategory.ACADEMIC, AppFeature.SCHOOL_MAIL.category)
        assertTrue(AppFeature.SCHOOL_MAIL.isImplemented)
        assertTrue(AppFeature.SCHOOL_MAIL.isSchoolMail)
        assertTrue(AppFeature.SCHOOL_MAIL in AppFeature.pinnableFeatures)
        assertTrue(AppFeature.SCHOOL_MAIL in AppFeature.moreFeatures)
        assertFalse(AppFeature.SCHOOL_MAIL in AppFeature.unfinishedFeatures)
        assertFalse("default tabs are unchanged", AppFeature.SCHOOL_MAIL in AppFeature.defaultTabs)
    }
}
