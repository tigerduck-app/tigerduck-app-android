package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** A member's initial stays inside its circle whatever the font size. */
class MoodleAvatarTest {

    @Test
    fun `the initial keeps to the circle at a large font size`() {
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            val density = Density(density = 2.625f, fontScale = fontScale)
            val letter = with(density) { avatarLetterSize(18.dp, density).toPx() }
            assertEquals("at $fontScale", with(density) { (18.dp * 0.45f).toPx() }, letter, 0.01f)
        }
    }
}
