package org.ntust.app.tigerduck.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.util.TimeZone
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.shared.time.TaipeiClock

/**
 * Flip-to-library while no library account is signed in (tigerduck-app/tigerduck-app#495), and
 * over a page the user is in the middle of. A face-down flip used to jump to Library whatever was
 * open, dropping a half-typed rename, the colour sheet, a confirmation or a mail draft with the
 * screen that held it. The iPhone ignores the flip while a sheet or alert is up.
 */
class FlipToLibraryGateTest {

    @Test
    fun `the flip does not listen while signed out of the library`() {
        assertFalse(FlipToLibraryGate.isArmed(enabled = true, signedInToLibrary = false, offerUnanswered = false))
        assertFalse(FlipToLibraryGate.isArmed(enabled = false, signedInToLibrary = false, offerUnanswered = true))
    }

    @Test
    fun `the flip listens with the switch on and a library account signed in`() {
        assertTrue(FlipToLibraryGate.isArmed(enabled = true, signedInToLibrary = true, offerUnanswered = false))
    }

    /** The question is asked on a flip, so the flip listens for it with the switch still off. */
    @Test
    fun `with the switch off, the flip listens only while the question that offers it is still to ask`() {
        assertTrue(FlipToLibraryGate.isArmed(enabled = false, signedInToLibrary = true, offerUnanswered = true))
        assertFalse(FlipToLibraryGate.isArmed(enabled = false, signedInToLibrary = true, offerUnanswered = false))
    }

    @Test
    fun `signed out, the switch reads off and takes no input`() {
        assertFalse(FlipToLibraryGate.switchReadsOn(enabled = true, signedInToLibrary = false))
        assertFalse(FlipToLibraryGate.switchTakesInput(signedInToLibrary = false))
    }

    @Test
    fun `signed in, the switch shows the choice it holds`() {
        assertTrue(FlipToLibraryGate.switchReadsOn(enabled = true, signedInToLibrary = true))
        assertFalse(FlipToLibraryGate.switchReadsOn(enabled = false, signedInToLibrary = true))
        assertTrue(FlipToLibraryGate.switchTakesInput(signedInToLibrary = true))
    }

    @Test
    fun `a flip with the switch off and the question never answered asks it`() {
        assertTrue(FlipToLibraryGate.offersGesture(enabled = false, asked = false, onLibraryPage = false))
    }

    @Test
    fun `a flip does not ask with the switch on, or once the question is answered`() {
        assertFalse(FlipToLibraryGate.offersGesture(enabled = true, asked = false, onLibraryPage = false))
        assertFalse(FlipToLibraryGate.offersGesture(enabled = false, asked = true, onLibraryPage = false))
    }

    /** It would cover the code the Library page shows (tigerduck-app/tigerduck-app#529). */
    @Test
    fun `a flip on the Library page does not ask`() {
        assertFalse(FlipToLibraryGate.offersGesture(enabled = false, asked = false, onLibraryPage = true))
    }

    @Test
    fun `a flip on an ordinary page goes to Library`() {
        assertTrue(FlipToLibraryGate.mayNavigate(windowFocused = true, currentRoute = Screen.ClassTable.route))
        assertTrue(FlipToLibraryGate.mayNavigate(windowFocused = true, currentRoute = Screen.Settings.route))
    }

    @Test
    fun `a flip while a dialog, sheet or menu has the focus does nothing`() {
        // Each of those is a window of its own, which takes the focus from the app's.
        assertFalse(FlipToLibraryGate.mayNavigate(windowFocused = false, currentRoute = Screen.ClassTable.route))
    }

    @Test
    fun `a flip with a mail draft on screen does nothing`() {
        // The iPhone writes mail in a sheet, which the flip already leaves alone.
        assertFalse(FlipToLibraryGate.mayNavigate(windowFocused = true, currentRoute = MailRoutes.COMPOSE))
    }

    // 2026-11-02 is a Monday, 2026-11-07 a Saturday and 2026-11-08 a Sunday.
    private fun taipei(day: Int, hour: Int, minute: Int, second: Int = 0): Instant =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, TaipeiClock.zone).toInstant()

    @Test
    fun weekdaysOpenFrom0810ToBefore2330() {
        assertTrue(FlipToLibraryGate.opensLibrary(taipei(2, 8, 10)))
        assertFalse(FlipToLibraryGate.opensLibrary(taipei(2, 8, 9, 59)))
        assertTrue(FlipToLibraryGate.opensLibrary(taipei(2, 23, 29, 59)))
        assertFalse(FlipToLibraryGate.opensLibrary(taipei(2, 23, 30)))
    }

    @Test
    fun weekendsOpenFrom0830ToBefore2130() {
        for (day in listOf(7, 8)) {
            assertTrue(FlipToLibraryGate.opensLibrary(taipei(day, 8, 30)))
            assertFalse(FlipToLibraryGate.opensLibrary(taipei(day, 8, 29, 59)))
            assertTrue(FlipToLibraryGate.opensLibrary(taipei(day, 21, 29, 59)))
            assertFalse(FlipToLibraryGate.opensLibrary(taipei(day, 21, 30)))
        }
    }

    @Test
    fun hoursAreTaipeisWhateverThePhonesZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            // Monday 01:00 UTC is Monday 09:00 in Taipei.
            assertTrue(FlipToLibraryGate.opensLibrary(Instant.parse("2026-11-02T01:00:00Z")))
            // Monday 16:00 UTC is Tuesday 00:00 in Taipei.
            assertFalse(FlipToLibraryGate.opensLibrary(Instant.parse("2026-11-02T16:00:00Z")))
            // Saturday 14:00 UTC is Saturday 22:00 in Taipei, past the weekend hours.
            assertFalse(FlipToLibraryGate.opensLibrary(Instant.parse("2026-11-07T14:00:00Z")))
            // Friday 15:45 UTC is Friday 23:45 in Taipei, past the weekday hours.
            assertFalse(FlipToLibraryGate.opensLibrary(Instant.parse("2026-11-06T15:45:00Z")))
            // Saturday 00:15 UTC is Saturday 08:15 in Taipei: before the weekend start, which a weekday would already have passed.
            assertFalse(FlipToLibraryGate.opensLibrary(Instant.parse("2026-11-07T00:15:00Z")))
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
