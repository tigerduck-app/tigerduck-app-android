package org.ntust.app.tigerduck.util

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The rules of Android's SharedPreferences that the tests on [JvmContext] lean on, checked on
 * [JvmContext] and, in [AndroidPreferencesRulesTest], on Android's own implementation as
 * Robolectric runs it, so the two cannot drift apart.
 */
abstract class PreferencesRulesTest {

    abstract val context: Context

    private val prefs: SharedPreferences get() = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    @Test
    fun `a key read as another type than it was stored as throws`() {
        prefs.edit().putInt("count", 1).commit()

        assertThrows(ClassCastException::class.java) { prefs.getString("count", null) }
        assertThrows(ClassCastException::class.java) { prefs.getLong("count", 0L) }
        assertEquals(1, prefs.getInt("count", 0))
    }

    @Test
    fun `an editor's clear goes before its own puts, whatever their order`() {
        prefs.edit().putString("kept", "old").putString("gone", "x").commit()

        prefs.edit().putString("kept", "new").clear().commit()

        assertEquals(mapOf("kept" to "new"), prefs.all)
    }

    @Test
    fun `a null value removes the key`() {
        prefs.edit().putString("text", "a").putStringSet("set", setOf("x")).commit()

        prefs.edit().putString("text", null).putStringSet("set", null).commit()

        assertFalse(prefs.contains("text"))
        assertFalse(prefs.contains("set"))
    }

    @Test
    fun `within one editor the last change to a key wins`() {
        prefs.edit().putString("text", "a").commit()

        prefs.edit().remove("text").putString("text", "b").commit()
        assertEquals("b", prefs.getString("text", null))

        prefs.edit().putString("text", "c").remove("text").commit()
        assertFalse(prefs.contains("text"))
    }

    @Test
    fun `a stored set is a copy of the one put`() {
        val put = mutableSetOf("a")
        prefs.edit().putStringSet("set", put).commit()

        put += "b"

        assertEquals(setOf("a"), prefs.getStringSet("set", null))
    }

    @Test
    fun `everything read at once is a copy`() {
        prefs.edit().putInt("count", 1).commit()
        val all = prefs.all

        prefs.edit().putInt("count", 2).putBoolean("flag", true).commit()

        assertEquals(mapOf("count" to 1), all)
    }

    @Test
    fun `the same name is the same preferences, and what apply writes reads back at once`() {
        context.getSharedPreferences("rules", Context.MODE_PRIVATE).edit().putBoolean("flag", true).apply()

        assertTrue(prefs.getBoolean("flag", false))
        assertSame(prefs, context.getSharedPreferences("rules", Context.MODE_PRIVATE))
        assertFalse(context.getSharedPreferences("other", Context.MODE_PRIVATE).contains("flag"))
    }

    @Test
    fun `deleted preferences come back empty`() {
        prefs.edit().putInt("count", 1).commit()

        assertTrue(context.deleteSharedPreferences("rules"))

        assertFalse(prefs.contains("count"))
    }

    @Test
    fun `the files and cache folders are folders of their own, and keep what is written`() {
        assertTrue(context.filesDir.isDirectory)
        assertTrue(context.cacheDir.isDirectory)
        assertNotEquals(context.filesDir, context.cacheDir)

        File(context.filesDir, "note.txt").writeText("kept")

        assertEquals("kept", File(context.filesDir, "note.txt").readText())
    }
}

class JvmContextPreferencesRulesTest : PreferencesRulesTest() {

    override val context = JvmContext()

    @Test
    fun `two contexts keep their preferences and folders apart`() {
        val other = JvmContext()
        context.getSharedPreferences("rules", Context.MODE_PRIVATE).edit().putInt("count", 1).commit()

        assertFalse(other.getSharedPreferences("rules", Context.MODE_PRIVATE).contains("count"))
        assertNotEquals(context.filesDir, other.filesDir)
    }

    @Test
    fun `a listener is refused, as nothing would call it`() {
        val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

        assertThrows(UnsupportedOperationException::class.java) {
            prefs.registerOnSharedPreferenceChangeListener { _, _ -> }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AndroidPreferencesRulesTest : PreferencesRulesTest() {

    override val context: Context get() = RuntimeEnvironment.getApplication()
}
