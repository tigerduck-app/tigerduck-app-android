package org.ntust.app.tigerduck.ui.screen.more

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.FeatureCategory

class MoreSectionsTest {

    @Test
    fun `library is listed in its own section, with no switch to turn it on`() {
        val library = moreCategorySections().toMap()[FeatureCategory.LIBRARY]
        assertEquals(listOf(AppFeature.LIBRARY), library)
    }
}
