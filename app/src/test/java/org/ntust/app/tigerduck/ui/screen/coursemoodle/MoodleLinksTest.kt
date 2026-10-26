package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleLinksTest {

    @Test
    fun `a page with two parameters keeps both inside the Moodle app's redirect`() {
        assertEquals(
            "moodlemobile://https://moodle2.ntust.edu.tw?redirect=/user/view.php?id%3D7%26course%3D5",
            MoodlePage.deepLink(MoodlePage.profile(userId = 7, courseId = 5)),
        )
        assertEquals(
            "https://moodle2.ntust.edu.tw/user/view.php?id=7&course=5",
            MoodlePage.webUrl(MoodlePage.profile(userId = 7, courseId = 5)),
        )
    }

    @Test
    fun `each screen opens its own page`() {
        assertEquals("/course/view.php?id=5", MoodlePage.course(5))
        assertEquals("/mod/forum/view.php?id=12", MoodlePage.forum(12))
        assertEquals("/mod/forum/discuss.php?d=77", MoodlePage.discussion(77))
        assertEquals("/grade/report/user/index.php?id=5", MoodlePage.gradeReport(5))
        assertEquals("/user/index.php?id=5", MoodlePage.participants(5))
    }

    @Test
    fun `a web-service file link opens on the website without its token`() {
        assertEquals(
            "https://moodle2.ntust.edu.tw/pluginfile.php/1/mod_forum/attachment/2/a.pdf?forcedownload=1",
            moodleWebLink(
                "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_forum/attachment/2/a.pdf?token=secret&forcedownload=1",
            ),
        )
        val page = "https://moodle2.ntust.edu.tw/mod/page/view.php?id=1"
        assertEquals("any other link is left alone", page, moodleWebLink(page))
    }
}
