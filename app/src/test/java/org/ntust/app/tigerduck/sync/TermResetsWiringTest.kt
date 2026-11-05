package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every reset of a term reaches [SchoolUploads.forgetTerm] through [TermResets], read from the
 * source. A term upload left waiting for the first pull across a reset would undo it. Codex
 * found that twice on PR R: in round 4 for the class table's reset, and in round 6 for Settings'
 * Use Local. What a reset drops is SchoolUploadsTest's to drive.
 *
 * A term reset is a term's DELETE on the backend (`deleteAllCourses`/`deleteAllCoursesAnswered`,
 * which `push/` defines) or a reset stamp (`DataCache.saveSemesterResetAt`). Each found in the
 * app's own code must be the class table's or Use Local's, and its DELETE must be sent inside
 * `termResets.run`. A new one fails here until it is.
 */
class TermResetsWiringTest {

    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, MAIN).let { if (it.isDirectory) return it }
            File(dir, "app/$MAIN").let { if (it.isDirectory) return it }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $MAIN from ${File("").absolutePath}")
    }

    /** Each main Kotlin file outside `push/`, by its path under the package root. */
    private fun sources(): Map<String, String> {
        val root = root()
        return root.walk().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
            .filterKeys { !it.startsWith("push/") }
    }

    /** Where [call] is made in [sources], as `path:line`, declarations and comments left out. */
    private fun callsOf(call: Regex, sources: Map<String, String>): List<Pair<String, Int>> =
        sources.flatMap { (path, text) ->
            text.lines().withIndex()
                .filter { (_, line) -> call.containsMatchIn(line) && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && "fun " !in line }
                .map { path to it.index }
        }

    @Test
    fun `TermResets lets go of the term's waiting uploads inside the adds' send lock once the reset lands`() {
        val text = sources().getValue("sync/TermResets.kt")
        assertTrue(
            "TermResets.run no longer resets under the adds' lock and lets go of the term's uploads in that hold",
            "adds.removing { reset()?.also { uploads.forgetTerm(semester) } }" in text,
        )
    }

    @Test
    fun `nothing but TermResets lets go of a term's waiting school uploads`() {
        // SchoolUploads.forgetTerm takes the term alone; PendingCourseDeletes.forgetTerm takes its sign-in too.
        val calls = callsOf(Regex("""\bforgetTerm\([^,()]*\)"""), sources())
        assertEquals("callers of SchoolUploads.forgetTerm", listOf("sync/TermResets.kt"), calls.map { it.first }.distinct())
    }

    @Test
    fun `every term reset on the backend is sent inside TermResets`() {
        val sources = sources()
        val deletes = callsOf(Regex("""\.deleteAllCourses(?:Answered)?\("""), sources)
        assertEquals(
            "the term resets this phone sends: the class table's and Use Local's",
            listOf("ui/screen/classtable/ClassTableViewModel.kt", "ui/screen/settings/KeepLocalCourses.kt"),
            deletes.map { it.first }.distinct().sorted(),
        )
        for ((path, index) in deletes) {
            val lines = sources.getValue(path).lines()
            val opened = (index downTo 0).firstOrNull { "termResets.run(" in lines[it] }
            assertTrue("$path:${index + 1} sends a term reset outside termResets.run", opened != null)
            val between = lines.subList(opened!!, index + 1)
            assertTrue("$path:${index + 1}: termResets.run is in another function", between.drop(1).none { "fun " in it })
            // From termResets.run to the call itself: its block is still open there.
            val upTo = between.dropLast(1) + lines[index].substring(0, Regex("""\.deleteAllCourses""").find(lines[index])!!.range.first)
            val text = upTo.joinToString("\n").substringAfter("termResets.run(")
            assertTrue("$path:${index + 1} sends a term reset after termResets.run's block closed", text.count { it == '{' } > text.count { it == '}' })
        }
    }

    @Test
    fun `every reset stamp belongs to a reset whose DELETE goes through TermResets`() {
        val stamps = callsOf(Regex("""\.saveSemesterResetAt\("""), sources())
        assertEquals(
            "who stamps a term reset: the class table's reset and Use Local's stampReset; a new one sends its DELETE through TermResets",
            listOf("ui/screen/classtable/ClassTableTermReset.kt", "ui/screen/settings/SettingsViewModel.kt"),
            stamps.map { it.first }.distinct().sorted(),
        )
        val settings = sources().getValue("ui/screen/settings/SettingsViewModel.kt")
        assertTrue("Use Local's requests no longer reset through TermResets", "PushCourseSyncBackend(" in settings && "dataCache, termResets)" in settings)
        val keepLocal = sources().getValue("ui/screen/settings/KeepLocalCourses.kt")
        assertTrue("Use Local's resetTerm no longer goes through TermResets", "termResets.run(semester) { api.deleteAllCoursesAnswered(" in keepLocal)
    }

    private companion object {
        const val MAIN = "src/main/java/org/ntust/app/tigerduck"
    }
}
