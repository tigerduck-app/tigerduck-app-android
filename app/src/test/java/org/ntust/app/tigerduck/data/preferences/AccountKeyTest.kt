package org.ntust.app.tigerduck.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The school's preference keys write only with a sign-in, and the move changed none of what an
 * installed build keeps: each reads the file an earlier build wrote, under the same name.
 */
class AccountKeyTest {

    private val context = JvmContext()
    private val file = context.getSharedPreferences("tigerduck_prefs", 0)
    private val prefs = AppPreferences(context)
    private val series = SignInSeries()

    /** One moved key, with a value for a [set][AccountKey.set], read back as its file holds it. */
    private inner class Moved<T>(val name: String, val key: AccountKey<T>, val value: T)

    private fun moved() = with(prefs.school) {
        listOf(
            Moved("assignmentMarksSyncedOnce", assignmentMarksSyncedOnce, true),
            Moved("assignmentsUploadFingerprint", assignmentsUploadFingerprint, "fingerprint-1"),
            Moved("assignmentsUploadedAtMs", assignmentsUploadedAtMs, 1_700_000_000_000L),
            Moved("backgroundCoursesSyncedAtMs", backgroundCoursesSyncedAtMs, 1_700_000_001_000L),
            Moved("backgroundCoursesTerm", backgroundCoursesTerm, "1141"),
            Moved("backgroundOverridesSyncedAtMs", backgroundOverridesSyncedAtMs, 1_700_000_002_000L),
            Moved("classTableSelectedSemester", classTableSelectedSemester, "1141"),
            Moved("emptyClassroomBuilding", emptyClassroomBuilding, "TR"),
            Moved("lastSeenCoursesResetAt", lastSeenCoursesResetAt, "2026-09-01T00:00:00Z"),
            Moved("pendingConflictCategories", pendingConflictCategories, setOf("courses")),
            Moved("schoolDataSyncedAtMs", schoolDataSyncedAtMs, 1_700_000_003_000L),
            Moved("ssoLoginTimestamp", ssoLoginTimestamp, 1_700_000_004_000L),
            Moved("syncedCategories", syncedCategories, setOf("assignments", "courses")),
        )
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

    @Test
    fun `the fixture's preferences read the same through every moved key`() {
        writeTheFixture()

        moved().forEach { assertEquals(it.name, it.value, it.key.get()) }
        assertEquals(1_700_000_003_000L, AppPreferences(context).schoolDataSyncedAtMs.value)
    }

    @Test
    fun `a set writes the key an earlier build read, under its own name`() {
        val signIn = series.current

        moved().forEach { write(it, signIn) }

        moved().forEach { key ->
            assertTrue("${key.name} is not on file under that name", key.name in file.all)
            assertEquals(key.name, key.value, key.key.get())
        }
    }

    @Test
    fun `a set from an ended sign-in writes nothing`() {
        val signIn = series.current
        writeTheFixture()
        val before = file.all
        series.end()

        moved().forEach { key ->
            assertFalse("${key.name} was set", replace(key, signIn))
        }

        assertEquals(before, file.all)
    }

    @Test
    fun `a set from a sign-in followed by the next leaves the next one's`() {
        val first = series.current
        series.end()
        series.open()
        val next = series.current
        moved().forEach { write(it, next) }
        val before = file.all

        moved().forEach { assertFalse(it.name, replace(it, first)) }

        assertEquals(before, file.all)
    }

    @Test
    fun `the flow the last-synced row shows follows the key, and a dropped set leaves it`() {
        val signIn = series.current
        prefs.school.schoolDataSyncedAtMs.set(5L, signIn)
        assertEquals(5L, prefs.schoolDataSyncedAtMs.value)

        series.end()
        prefs.school.schoolDataSyncedAtMs.set(9L, signIn)

        assertEquals(5L, prefs.schoolDataSyncedAtMs.value)
    }

    @Test
    fun `a sign-out's clear takes no sign-in, and leaves a key as a new install has it`() {
        writeTheFixture()
        series.end()

        prefs.school.classTableSelectedSemester.clear()
        prefs.school.emptyClassroomBuilding.clear()
        prefs.school.pendingConflictCategories.clear()

        assertFalse("removed, not set empty", "classTableSelectedSemester" in file.all)
        assertFalse("removed, not set empty", "emptyClassroomBuilding" in file.all)
        assertNull(prefs.school.classTableSelectedSemester.get())
        assertNull(prefs.school.emptyClassroomBuilding.get())
        assertEquals(emptySet<String>(), prefs.school.pendingConflictCategories.get())
    }

    /** A sign-out either waits for a set under way or comes first: nothing falls between the check and the write. */
    @Test
    fun `a set is atomic with the end of its sign-in`() {
        val signIn = series.current
        val writing = CountDownLatch(1)
        val mayWrite = CountDownLatch(1)
        var stored: String? = null
        val key = AccountKey<String?>(
            read = { stored },
            write = {
                writing.countDown()
                // Bounded, so a failed test cannot hang on it.
                mayWrite.await(10, TimeUnit.SECONDS)
                stored = it
            },
            empty = null,
        )
        val setting = thread { key.set("kept", signIn) }
        assertTrue(writing.await(10, TimeUnit.SECONDS))

        val ending = thread { series.end() }
        ending.join(200)
        assertTrue("the sign-out waits for the set under way", ending.isAlive)
        mayWrite.countDown()
        setting.join(10_000)
        ending.join(10_000)

        assertEquals("kept", stored)
        assertSame(signIn, series.current)
        assertFalse(signIn.isCurrent)
        assertFalse("and the next set finds it ended", key.set("late", signIn))
        assertEquals("kept", stored)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(moved: Moved<T>, signIn: SignIn) = assertTrue(moved.name, moved.key.set(moved.value, signIn))

    // A value none of the keys holds, of its own type.
    @Suppress("UNCHECKED_CAST")
    private fun <T> replace(moved: Moved<T>, signIn: SignIn): Boolean = moved.key.set(
        when (val value = moved.value) {
            is Boolean -> !value
            is Long -> value + 1
            is String -> "$value+"
            else -> setOf("other")
        } as T,
        signIn,
    )

    @Test
    fun `a key written while signed out stays, as the next sign-in's`() {
        // The signed-out time shares the next sign-in: a choice made then is not dropped.
        assertTrue(prefs.school.emptyClassroomBuilding.set("TR", testSignIn()))
        assertEquals("TR", prefs.school.emptyClassroomBuilding.get())
    }
}
