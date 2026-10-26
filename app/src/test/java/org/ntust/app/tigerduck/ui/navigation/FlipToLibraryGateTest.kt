package org.ntust.app.tigerduck.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailRoutes

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
}
