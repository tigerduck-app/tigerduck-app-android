package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The school's sign-out as its sources spell it, for the tests that read them: AuthService needs
 * the Android Keystore, which no JVM test opens. Where the sign-out runs a list of SchoolStores,
 * [expanded] writes the list out as the calls its stores' `forgetAccount` make, in the list's
 * order, each on the name SchoolStores holds the store by: `stores.atOnce.forEach { … }` reads on
 * as `bulletinReadStateStore.clear()`, `prefs.clearSyncStamps()` and so on, the steps a reader of
 * the sign-out follows.
 */
internal object SignOutSource {

    val appDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = dir
            if (File(here, "app/src/main/java").isDirectory) return@lazy File(here, "app")
            if (File(here, "src/main/java").isDirectory) return@lazy here
            dir = here.parentFile
        }
        throw AssertionError("Could not locate the :app module from ${File("").absolutePath}")
    }

    private val main: File get() = File(appDir, "src/main/java/org/ntust/app/tigerduck")

    /** The source at [path] under the app's package. */
    fun source(path: String): String = File(main, path).readText()

    /** The file that holds the school's plan, and its clearing. */
    const val PLAN = "auth/AuthService.kt"

    /**
     * The body of the function [declaration] starts in [source]: its block up to the matching
     * brace, or its expression with the lines indented under it.
     */
    fun body(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        if (start < 0) throw AssertionError("no `$declaration`")
        val afterParameters = source.indexOf(')', start + declaration.length - 1)
        val rest = source.substring(afterParameters + 1).substringBefore('\n')
        if (rest.contains('=') && !rest.trimEnd().endsWith("{")) {
            val indent = source.substring(source.lastIndexOf('\n', start) + 1).takeWhile { it == ' ' }.length
            return source.substring(afterParameters + 1).lineSequence()
                .withIndex()
                .takeWhile { (index, line) -> index == 0 || line.isBlank() || line.takeWhile { it == ' ' }.length > indent }
                .joinToString("\n") { it.value }
        }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return source.substring(open + 1, index).also {
                        assertFalse("`$declaration` ran into the next function", Regex("""\n {4}\S""").containsMatchIn(it))
                    }
                }
            }
        }
        throw AssertionError("`$declaration` has no closing brace")
    }

    private val stores: String get() = source("auth/session/SchoolStores.kt")

    /** The stores SchoolStores lists as [list], `atOnce` or `late`, in order: the name it holds each by, and its type. */
    fun listed(list: String): List<Pair<String, String>> {
        val held = Regex("""\b(\w+)\s*:\s*dagger\.Lazy<(\w+)>""").findAll(stores).associate { it.groupValues[1] to it.groupValues[2] }
        val getter = stores.substringAfter("val $list:").substringBefore("\n\n")
        return Regex("""\b(\w+)\.get\(\)""").findAll(getter).map { it.groupValues[1] }
            .map { name -> name to (held[name] ?: throw AssertionError("SchoolStores holds no `$name`")) }.toList()
            .also { assertTrue("SchoolStores lists nothing as $list", it.isNotEmpty()) }
    }

    /** The source that declares [type]. */
    private fun declaring(type: String): String = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .map { it.readText() }
        .single { Regex("""\b(?:class|interface)\s+$type\b""").containsMatchIn(it) }

    /**
     * What each store of [list] forgets, in order: the functions of its own its `forgetAccount`
     * calls or names, each written as a call on the name SchoolStores holds the store by.
     */
    fun forgetting(list: String): String = listed(list).joinToString("\n") { (name, type) ->
        val source = declaring(type)
        val forget = body(source, "override fun forgetAccount()".let { if (it in source) it else "override suspend fun forgetAccount()" })
        Regex("""(?<![.\w])(?:::)?([a-z]\w*)\b""").findAll(forget).map { it.groupValues[1] }
            .filter { function -> Regex("""\bfun\s+$function\(""").containsMatchIn(source) }
            .joinToString("\n") { "$name.$it()" }
    }

    /** [text] with each run of a list of SchoolStores followed by what [forgetting] says it does. */
    fun expanded(text: String): String = Regex("""\bstores\.(atOnce|late)\.forEach\b""").replace(text) { match ->
        match.value + "\n" + forgetting(match.groupValues[1]) + "\n"
    }
}
