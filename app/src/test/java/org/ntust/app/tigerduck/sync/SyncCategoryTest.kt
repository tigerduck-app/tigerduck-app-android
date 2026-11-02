package org.ntust.app.tigerduck.sync

import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.DevicePreferencesResponse
import org.ntust.app.tigerduck.push.UpdateDevicePreferencesRequest
import org.ntust.app.tigerduck.ui.screen.settings.REENABLE_CATEGORIES
import org.ntust.app.tigerduck.ui.screen.settings.categoriesSwitchedOn
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import kotlin.reflect.KClass

/**
 * [SyncCategory] names the four categories by the strings this phone has always stored them by,
 * in `syncedCategories` and `pendingConflictCategories`, and by the device row's flags the backend
 * keeps their switches in. Both are what earlier builds wrote and what the backend reads, so
 * neither may change.
 */
class SyncCategoryTest {

    private val context = JvmContext()
    private val file = context.getSharedPreferences("tigerduck_prefs", 0)
    private val prefs = AppPreferences(context)

    @Test
    fun `each category is today's stored string, and the string reads back as the category`() {
        assertEquals(
            listOf("courses", "course_colors", "course_names", "assignments"),
            SyncCategory.entries.map { it.stored },
        )
        for (category in SyncCategory.entries) assertEquals(category, SyncCategory.ofStored(category.stored))
    }

    @Test
    fun `the stored strings are the ones the re-enable check and the switches already use`() {
        assertEquals(REENABLE_CATEGORIES, SyncCategory.entries.map { it.stored }.toSet())
        assertEquals(REENABLE_CATEGORIES, categoriesSwitchedOn(prefs))
        assertEquals(SyncCategory.COURSE_COLORS.stored, PendingCourseOverrides.category(color = true))
        assertEquals(SyncCategory.COURSE_NAMES.stored, PendingCourseOverrides.category(color = false))
    }

    @Test
    fun `of reads the sets an earlier build stored`() {
        writeTheFixture()

        assertEquals(
            setOf(SyncCategory.ASSIGNMENTS, SyncCategory.COURSES),
            SyncCategory.of(prefs.school.syncedCategories.get()),
        )
        assertEquals(setOf(SyncCategory.COURSES), SyncCategory.of(prefs.school.pendingConflictCategories.get()))
    }

    @Test
    fun `a string this build does not know is left out, and stays stored`() {
        val stored = setOf("course_names", "bulletins", "", "COURSES")
        prefs.school.syncedCategories.set(stored, testSignIn())

        assertNull(SyncCategory.ofStored("bulletins"))
        assertNull(SyncCategory.ofStored("COURSES"))
        assertEquals(setOf(SyncCategory.COURSE_NAMES), SyncCategory.of(prefs.school.syncedCategories.get()))
        // Reading never writes: what a newer build stored is still there for it.
        assertEquals(stored, prefs.school.syncedCategories.get())
        assertEquals(emptySet<SyncCategory>(), SyncCategory.of(emptySet()))
    }

    @Test
    fun `the wire name is the device row's flag for the category's switch`() {
        for (model in listOf(UpdateDevicePreferencesRequest::class, DevicePreferencesResponse::class)) {
            for (category in SyncCategory.entries) {
                assertEquals("${model.simpleName} ${category.name}", flagOf(model, category), category.wireName)
            }
        }
        assertEquals("sync_course_colors", SyncCategory.COURSE_COLORS.wireName)
    }

    /** The JSON name of the model's field for the category's switch: `syncCourseColors` for `course_colors`. */
    private fun flagOf(model: KClass<*>, category: SyncCategory): String {
        val field = "sync" + category.stored.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return model.java.getDeclaredField(field).getAnnotation(SerializedName::class.java).value
    }

    /** `school-prefs.txt`, the keys as an earlier build wrote them, written straight to the file. */
    private fun writeTheFixture() {
        val lines = checkNotNull(javaClass.getResource("/account/school-prefs.txt")).readText().trim().lines()
        val editor = file.edit()
        lines.forEach { line ->
            val (name, type, value) = line.split(' ', limit = 3)
            when (type) {
                "Boolean" -> editor.putBoolean(name, value.toBoolean())
                "Long" -> editor.putLong(name, value.toLong())
                "String" -> editor.putString(name, value)
                else -> editor.putStringSet(name, value.removeSurrounding("[", "]").split(", ").toSet())
            }
        }
        editor.commit()
    }
}
