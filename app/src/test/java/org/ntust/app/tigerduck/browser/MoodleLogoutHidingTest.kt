package org.ntust.app.tigerduck.browser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [BrowserPageScripts.HIDE_LOGOUT]'s rules hide on hand-made copies of the places Moodle 4
 * puts its logout, read with jsoup's selectors: the logout, and whatever it leaves standing on
 * its own, and nothing else.
 */
class MoodleLogoutHidingTest {

    private fun hidden(doc: Document): Set<Element> =
        (doc.select(BrowserPageScripts.LOGOUT_SELECTOR) + doc.select(BrowserPageScripts.LEFT_OVER_SELECTOR)).toSet()

    @Test
    fun `the user menu loses its logout and the divider above it, and keeps its other dividers`() {
        val doc = Jsoup.parse(USER_MENU)
        val items = doc.select("#user-action-menu .d-flex > *")
        val dividers = items.filter { it.hasClass("dropdown-divider") }

        val gone = hidden(doc)

        assertEquals(setOf(items.last(), dividers.last()), gone)
        assertTrue("the other dividers stay", dividers.dropLast(1).none { it in gone })
    }

    @Test
    fun `the footer's logged-in line goes with its logout, leaving no empty brackets`() {
        val doc = Jsoup.parse(FOOTER)

        assertTrue(doc.selectFirst(".logininfo") in hidden(doc))
        assertTrue("the rest of the footer stays", doc.selectFirst(".homelink") !in hidden(doc))
    }

    @Test
    fun `the sign-in page's logged-in box loses its Log out button and the wrapper around it`() {
        val doc = Jsoup.parse(LOGGED_IN_BOX)
        val buttons = doc.select(".singlebutton")

        val gone = hidden(doc)

        assertTrue(buttons[0] in gone)
        assertTrue("Cancel stays", buttons[1] !in gone)
    }

    @Test
    fun `a divider before anything else stays`() {
        val doc = Jsoup.parse(
            """<div><div class="dropdown-divider"></div><a href="https://moodle2.ntust.edu.tw/user/preferences.php" class="dropdown-item">Preferences</a></div>""",
        )

        assertTrue(hidden(doc).isEmpty())
    }

    private companion object {
        const val SITE = "https://moodle2.ntust.edu.tw"

        /** Moodle 4.x's user menu as the emulator showed it: Accessibility, profile items, preferences, then Log out. */
        val USER_MENU = """
            <div id="user-action-menu" class="dropdown-menu dropdown-menu-right">
              <div id="usermenu-carousel" class="carousel slide">
                <div class="carousel-inner h-100">
                  <div class="carousel-item active" role="menu">
                    <div class="d-flex flex-column h-100">
                      <a href="$SITE/admin/tool/accessibility" class="dropdown-item" role="menuitem">Accessibility</a>
                      <div class="dropdown-divider" role="presentation"></div>
                      <a href="$SITE/user/profile.php" class="dropdown-item" role="menuitem">Profile</a>
                      <a href="$SITE/grade/report/overview/index.php" class="dropdown-item" role="menuitem">Grades</a>
                      <a href="$SITE/calendar/view.php?view=month" class="dropdown-item" role="menuitem">Calendar</a>
                      <a href="$SITE/user/files.php" class="dropdown-item" role="menuitem">Private files</a>
                      <div class="dropdown-divider" role="presentation"></div>
                      <a href="$SITE/user/preferences.php" class="dropdown-item" role="menuitem">Preferences</a>
                      <a href="#" class="carousel-navigation-link dropdown-item" role="menuitem">Language</a>
                      <div class="dropdown-divider" role="presentation"></div>
                      <a href="$SITE/login/logout.php?sesskey=made-up" class="dropdown-item" role="menuitem">Log out</a>
                    </div>
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()

        val FOOTER = """
            <div class="footer-content-popover">
              <div class="logininfo">You are logged in as <a href="$SITE/user/profile.php?id=0" title="View profile">Test Student</a> (<a href="$SITE/login/logout.php?sesskey=made-up">Log out</a>)</div>
              <div class="homelink"><a href="$SITE/">Home</a></div>
            </div>
        """.trimIndent()

        val LOGGED_IN_BOX = """
            <div class="loginbox">
              <p>You are already logged in as Test Student, you need to log out before logging in as different user.</p>
              <div class="buttons">
                <div class="singlebutton">
                  <form method="post" action="$SITE/login/logout.php">
                    <input type="hidden" name="sesskey" value="made-up">
                    <button type="submit" class="btn btn-primary">Log out</button>
                  </form>
                </div>
                <div class="singlebutton">
                  <form method="get" action="$SITE"><button type="submit" class="btn btn-secondary">Cancel</button></form>
                </div>
              </div>
            </div>
        """.trimIndent()
    }
}
