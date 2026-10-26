package org.ntust.app.tigerduck.ui.component

import android.app.Application
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The student ID field flips to a numeric layout after its leading letter, which a hardware
 * keyboard types no letters into. While one is in use, the field takes the standard keyboard,
 * unless the user chose otherwise with the toggle; back on the touch keyboard, it flips again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountIdFieldKeyboardTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the standard keyboard follows a hardware keyboard until the user chooses`() {
        assertTrue(usesStandardKeyboard(chosen = null, hardwareKeyboard = true))
        assertFalse(usesStandardKeyboard(chosen = null, hardwareKeyboard = false))
    }

    @Test
    fun `the user's choice stands whatever keyboard is in use`() {
        assertTrue(usesStandardKeyboard(chosen = true, hardwareKeyboard = false))
        assertFalse(usesStandardKeyboard(chosen = false, hardwareKeyboard = true))
    }

    /**
     * A keyboard attached or taken away recreates the activity, as the manifest does not handle
     * the change, and the user's choice has to come through it.
     */
    @Test
    fun `the user's choice comes through the activity being recreated`() {
        val restoration = StateRestorationTester(composeRule)
        var choice: MutableState<Boolean?>? = null
        restoration.setContent { choice = rememberStandardKeyboardChoice() }
        composeRule.runOnIdle { choice!!.value = false }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle { assertEquals(false, choice!!.value) }
    }

    @Test
    @Config(qualifiers = "keysexposed-qwerty")
    fun `with a hardware keyboard the field keeps a text layout past the leading letter`() {
        composeRule.setContent { OutlinedAccountIdField(value = "B1", onValueChange = {}, label = "Student ID") }
        composeRule.waitForIdle()

        assertEquals(InputType.TYPE_CLASS_TEXT, editText().inputType and InputType.TYPE_MASK_CLASS)
    }

    @Test
    @Config(qualifiers = "nokeys")
    fun `on the touch keyboard the field flips to numbers past the leading letter`() {
        composeRule.setContent { OutlinedAccountIdField(value = "B1", onValueChange = {}, label = "Student ID") }
        composeRule.waitForIdle()

        assertEquals(InputType.TYPE_CLASS_NUMBER, editText().inputType and InputType.TYPE_MASK_CLASS)
    }

    private fun editText(): EditText =
        find(composeRule.activity.window.decorView) ?: throw AssertionError("the field has no EditText")

    private fun find(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
