package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `viewModelScope` dispatches on `Dispatchers.Main.immediate`, so a
 * `launch {}` inside `init` starts running **synchronously on the calling
 * thread**, before the constructor returns. One of those launches collects
 * `authService.authState` — a `StateFlow`, which replays its current value
 * to a new collector immediately — and for a logged-in user that lands in
 * `fetchData()` while the object is still being constructed.
 *
 * Kotlin initialises properties in declaration order, so every stored
 * property declared *below* `init` is still null on that path. Reading one
 * throws NPE before the app can draw a frame.
 *
 * That shipped once: `availableSemesters` was converted from a computed
 * getter (position-independent) into a backing `MutableStateFlow` without
 * being moved above `init`, and `fetchData` NPE'd on it at every warm launch
 * of a logged-in user.
 *
 * Computed properties (`val x: T get() = ...`) are exempt — they hold no
 * field and so have no initialisation order.
 */
class ClassTableViewModelInitOrderTest {

    private fun viewModelSource(): File {
        val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/" +
            "classtable/ClassTableViewModel.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it }
            File(dir, "app/$rel").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate ClassTableViewModel.kt from ${File("").absolutePath}")
    }

    /** Matches a class-level (4-space indented) property that owns a backing field. */
    private val storedProperty = Regex("""^ {4}(?:private |internal )?va[lr] (\w+)[^=]*=""")

    @Test
    fun `no stored property is declared after the init block`() {
        val lines = viewModelSource().readLines()

        val initLine = lines.indexOfFirst { it.trimEnd() == "    init {" }
        assertEquals(
            "Expected exactly one class-level `init {` block in ClassTableViewModel",
            1,
            lines.count { it.trimEnd() == "    init {" },
        )

        val offenders = lines.withIndex()
            .drop(initLine + 1)
            .filterNot { (_, line) -> line.contains("get()") }
            .mapNotNull { (i, line) ->
                storedProperty.find(line)?.groupValues?.get(1)?.let { "${i + 1}: $it" }
            }

        assertEquals(
            "These stored properties are declared after `init` and are therefore null " +
                "while init's Main.immediate collectors run during construction. " +
                "Move them above `init`:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
