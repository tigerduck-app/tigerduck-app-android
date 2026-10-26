package org.ntust.app.tigerduck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release builds keep Log.w and Log.e (ReleaseLogRulesTest), and logcat hands
 * them to adb and to every bug report. So a warning or an error names a
 * failure by its class, with `logSummary()` or `javaClass.simpleName`, and
 * carries no course number, id, token or server text: an exception's message,
 * and so its stack trace, can quote a server's reply.
 *
 * This reads every Log.w, Log.e, Log.wtf and Log.println call in the code the
 * phone and the watch ship and fails on the shapes that let those through,
 * and on any printStackTrace.
 */
class ReleaseLogLinesTest {

    /** Sources that reach a release build: the phone's flavors and the modules it is built from, :shared, and the watch. */
    private val shipped = listOf("src/main", "src/play", "src/fdroid", "../shared/src/main", "../wear/src/main") +
        listOf("../core", "../feature").flatMap { group ->
            File(group).listFiles().orEmpty().sorted().map { "$group/${it.name}/src/main" }
        }

    @Test
    fun `no warning or error in shipped code logs an exception's text or user data`() {
        val calls = shipped.map(::File).filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file -> LogCallScanner(file.readText()).calls().map { file.path to it } }
        // A scanner that stopped finding calls would pass everything.
        assertTrue("found only ${calls.size} calls", calls.size > 100)
        val violations = calls.flatMap { (path, call) -> call.violations().map { "$path:${call.line}: $it" } }
        assertEquals(emptyList<String>(), violations)
    }

    // --- the scanner itself, on the shapes release logs used to have ----------------------

    private fun violations(source: String) = LogCallScanner(source).calls().flatMap { it.violations() }

    @Test
    fun `a throwable passed after the message is caught`() {
        assertEquals(1, violations("""Log.w(TAG, "register failed", e)""").size)
        assertEquals(1, violations("""android.util.Log.e("Tag", "x: ${'$'}{e.logSummary()}", e)""").size)
    }

    @Test
    fun `a throwable passed as the message is caught`() {
        assertEquals(1, violations("""Log.w(TAG, e)""").size)
    }

    @Test
    fun `an exception's text in the message is caught`() {
        listOf(
            """Log.w(TAG, "failed: ${'$'}e")""",
            """Log.w(TAG, "failed: ${'$'}{e.message}")""",
            """Log.w(TAG, "failed: ${'$'}{it.localizedMessage}")""",
            """Log.w(TAG, "failed: ${'$'}{t.stackTraceToString()}")""",
            """Log.w(TAG, "failed: ${'$'}{error}")""",
            """Log.w(TAG, "failed: " + e.message)""",
            """Log.e(TAG, "failed: " + e)""",
        ).forEach { assertEquals(it, 1, violations(it).size) }
    }

    @Test
    fun `course numbers, ids and secrets in the message are caught`() {
        listOf(
            """Log.w(TAG, "color sync failed for ${'$'}courseNo")""",
            """Log.w(TAG, "PATCH ${'$'}{course.courseNo} failed")""",
            """Log.w(TAG, "PATCH ${'$'}courseKey failed")""",
            """Log.w(TAG, "wrote score_${'$'}studentId.json")""",
            """Log.w(TAG, "override ${'$'}assignmentId failed")""",
            """Log.w(TAG, "device ${'$'}deviceId")""",
            """Log.w(TAG, "moodle ${'$'}{c.moodleIdNumber}")""",
            """Log.w(TAG, "token ${'$'}{prefs.accessToken}")""",
            """Log.w(TAG, "reply ${'$'}{response.body}")""",
        ).forEach { assertEquals(it, 1, violations(it).size) }
    }

    @Test
    fun `an exception under a name of its own is caught`() {
        listOf(
            """try { x() } catch (ioe: IOException) { Log.w(TAG, "failed: ${'$'}ioe") }""",
            """runCatching { x() }.onFailure { err -> Log.w(TAG, "failed: ${'$'}{err.message}") }""",
            """task.addOnFailureListener { problem -> Log.e(TAG, "failed: ${'$'}problem") }""",
            """result.fold(onSuccess = {}, onFailure = { boom -> Log.w(TAG, "failed: " + boom) })""",
        ).forEach { assertEquals(it, 1, violations(it).size) }
        assertEquals(
            emptyList<String>(),
            violations("""try { x() } catch (ioe: IOException) { Log.w(TAG, "failed: ${'$'}{ioe.logSummary()}") }"""),
        )
    }

    @Test
    fun `a status the failure carries passes`() {
        assertEquals(emptyList<String>(), violations("""Log.w(TAG, "HTTP ${'$'}{e.code()}, ${'$'}{e.statusCode}")"""))
    }

    @Test
    fun `addresses, accounts, cookies and a Result's exception are caught`() {
        listOf(
            """Log.w(TAG, "GET ${'$'}url failed")""",
            """Log.w(TAG, "GET ${'$'}{request.url} failed")""",
            """Log.w(TAG, "mail to ${'$'}email")""",
            """Log.w(TAG, "user ${'$'}{prefs.libraryUsername}")""",
            """Log.w(TAG, "cookie ${'$'}{headers.cookie}")""",
            """Log.w(TAG, "auth ${'$'}authorization")""",
            """Log.w(TAG, "failed: ${'$'}{result.exceptionOrNull()}")""",
        ).forEach { assertEquals(it, 1, violations(it).size) }
    }

    @Test
    fun `token is caught as a word, not inside another one`() {
        assertEquals(1, violations("""Log.w(TAG, "kept ${'$'}accessToken")""").size)
        assertEquals(1, violations("""Log.w(TAG, "kept ${'$'}{store.token}")""").size)
        assertEquals(emptyList<String>(), violations("""Log.w(TAG, "kept ${'$'}{tokens.size} of ${'$'}tokenizerCount")"""))
    }

    @Test
    fun `Log println is read too, whatever its priority`() {
        assertEquals(1, violations("""Log.println(Log.WARN, TAG, "failed: ${'$'}{e.message}")""").size)
        assertEquals(1, violations("""android.util.Log.println(Log.DEBUG, TAG, "course ${'$'}courseNo")""").size)
        val fine = LogCallScanner("""Log.println(Log.ERROR, TAG, "failed: ${'$'}{e.logSummary()}")""").calls()
        assertEquals(1, fine.size)
        assertEquals(emptyList<String>(), fine.flatMap { it.violations() })
    }

    @Test
    fun `printStackTrace is caught, and not in a comment`() {
        assertEquals(1, violations("""try { x() } catch (e: Exception) { e.printStackTrace() }""").size)
        assertEquals(emptyList<String>(), violations("""// e.printStackTrace()"""))
    }

    @Test
    fun `what a format call fills in is read`() {
        listOf(
            """Log.w(TAG, "failed: %s".format(e))""",
            """Log.w(TAG, String.format("failed: %s", e.message))""",
            """Log.w(TAG, "course %s".format(courseNo))""",
        ).forEach { assertEquals(it, 1, violations(it).size) }
        assertEquals(emptyList<String>(), violations("""Log.w(TAG, "HTTP %d after %s".format(status, e.logSummary()))"""))
    }

    @Test
    fun `a failure named by its class and status passes`() {
        val source = """
            Log.w(TAG, "register failed: ${'$'}{e.logSummary()}")
            Log.w(TAG, "QR refresh failed: ${'$'}{e.javaClass.simpleName}")
            Log.e(TAG, "retry failed: ${'$'}{retry::class.java.simpleName}")
            Log.w(TAG, "refresh got HTTP ${'$'}{response.code}; keeping the tokens")
            android.util.Log.w(
                "CalendarViewModel", "holiday override upload failed: ${'$'}{e.logSummary()}",
            )
            Log.w(TAG, "${'$'}label '${'$'}name' exceeds ${'$'}maxChars chars")
            Log.w(TAG, message)
        """
        val calls = LogCallScanner(source).calls()
        assertEquals(7, calls.size)
        assertEquals(emptyList<String>(), calls.flatMap { it.violations() })
    }

    @Test
    fun `calls are found across lines, and never in comments or strings`() {
        val source = """
            // Log.w(TAG, "old", e)
            /* Log.e(TAG, "old", e) /* nested */ Log.e(TAG, e) */
            val doc = "Log.w(TAG, e)"
            val quote = '"'
            Log.w(
                TAG,
                "a ${'$'}{listOf(1, 2).joinToString(", ") { "(${'$'}it)" }} b",
                e,
            )
        """
        val calls = LogCallScanner(source).calls()
        assertEquals(1, calls.size)
        assertEquals(6, calls.single().line)
        assertEquals(1, calls.single().violations().size)
    }
}

/**
 * Finds `Log.w(`, `Log.e(` and `Log.wtf(` calls in Kotlin source, skipping
 * comments, strings and character literals, and splits each call into its
 * top-level arguments.
 */
private class LogCallScanner(private val s: String) {

    class Arg(
        /** The argument with each string literal replaced by `S`. */
        val code: String,
        /** What the argument's string templates interpolate, `$x` and `${x}` alike. */
        val templates: List<String>,
    )

    /**
     * One call found: a Log.w, Log.e or Log.wtf ([kind] WARN), a Log.println
     * (PRINTLN), or a printStackTrace (STACK_TRACE). [throwableNames] are what
     * this file calls an exception.
     */
    class LogCall(val line: Int, val kind: Kind, val args: List<Arg>, private val throwableNames: Set<String>) {
        enum class Kind { WARN, PRINTLN, STACK_TRACE }

        fun violations(): List<String> {
            if (kind == Kind.STACK_TRACE) return listOf("prints a stack trace, which quotes the exception's message")
            val problems = mutableListOf<String>()
            // Log.println(priority, tag, msg) has no throwable overload; Log.w(tag, msg) does.
            val messageAt = if (kind == Kind.PRINTLN) 2 else 1
            if (args.size != messageAt + 1) {
                problems += "passes ${args.size} arguments; a throwable after the message prints its message and stack trace"
            }
            val message = args.getOrNull(messageAt) ?: return problems
            message.expressions().forEach { expr ->
                val root = expr.takeWhile { it.isLetterOrDigit() || it == '_' }
                when {
                    root in throwableNames && !NAMED_BY_CLASS.matches(expr) ->
                        problems += "logs `$expr`; name the failure with logSummary() or javaClass.simpleName"
                    isSensitive(expr) ->
                        problems += "logs `$expr`, which can carry user data or server text"
                }
            }
            return problems
        }

        /** Templates, what the message adds to its literals with `+`, and what a format() call fills in. */
        private fun Arg.expressions(): List<String> {
            val pieces = splitTopLevel(code, '+').filter { it.isNotEmpty() && it != "S" }
            val (formats, others) = pieces.partition { FORMAT_CALL.containsMatchIn(it) }
            val formatArgs = formats.flatMap { piece ->
                FORMAT_CALL.findAll(piece).flatMap { m ->
                    val open = m.range.last
                    val close = matchingParen(piece, open)
                    splitTopLevel(piece.substring(open + 1, close), ',')
                }
            }.filter { it.isNotEmpty() && it != "S" }
            return templates + others + formatArgs
        }
    }

    private var i = 0

    fun calls(): List<LogCall> {
        val names = THROWABLE_NAMES + CAUGHT_AS.findAll(s).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }
        val calls = mutableListOf<LogCall>()
        while (i < s.length) {
            val match = CALLS.entries.firstOrNull { s.startsWith(it.key, i) }
            when {
                skipCommentOrLiteral() -> Unit
                match != null && (i == 0 || !s[i - 1].isJavaIdentifierPart()) -> {
                    val line = s.substring(0, i).count { it == '\n' } + 1
                    i += match.key.length
                    calls += LogCall(line, match.value, arguments(), names)
                }
                else -> i++
            }
        }
        return calls
    }

    /** Skips a comment, string, character literal or backticked name at [i]; false if there is none. */
    private fun skipCommentOrLiteral(): Boolean {
        when {
            s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
            s.startsWith("/*", i) -> {
                var depth = 0
                do {
                    when {
                        s.startsWith("/*", i) -> { depth++; i += 2 }
                        s.startsWith("*/", i) -> { depth--; i += 2 }
                        else -> i++
                    }
                } while (depth > 0 && i < s.length)
            }
            s[i] == '"' -> string()
            s[i] == '\'' -> {
                i++
                while (i < s.length && s[i] != '\'') i += if (s[i] == '\\') 2 else 1
                i++
            }
            s[i] == '`' -> i = s.indexOf('`', i + 1).let { if (it < 0) s.length else it + 1 }
            else -> return false
        }
        return true
    }

    /** Reads a string literal starting at its opening quote; returns its template expressions. */
    private fun string(): List<String> {
        val raw = s.startsWith("\"\"\"", i)
        i += if (raw) 3 else 1
        val templates = mutableListOf<String>()
        while (i < s.length) {
            when {
                raw && s.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < s.length && s[i] == '"') i++
                    return templates
                }
                !raw && s[i] == '"' -> { i++; return templates }
                !raw && s[i] == '\\' -> i += 2
                s.startsWith("\${", i) -> {
                    val start = i + 2
                    i = start
                    code(until = '}')
                    templates += s.substring(start, i - 1).trim()
                }
                s[i] == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_') -> {
                    val start = ++i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    templates += s.substring(start, i)
                }
                else -> i++
            }
        }
        return templates
    }

    /** Skips code up to and past the [until] that closes it, minding nesting and literals. */
    private fun code(until: Char) {
        var depth = 0
        while (i < s.length) {
            if (skipCommentOrLiteral()) continue
            val c = s[i++]
            when {
                c in "([{" -> depth++
                c in ")]}" && depth > 0 -> depth--
                c == until -> return
            }
        }
    }

    /** Reads a call's arguments, [i] just past its opening parenthesis, and steps past the closing one. */
    private fun arguments(): List<Arg> {
        val args = mutableListOf<Arg>()
        var code = StringBuilder()
        var templates = mutableListOf<String>()
        var depth = 0
        fun endArg() {
            if (code.isNotBlank()) args += Arg(code.toString().trim(), templates)
            code = StringBuilder()
            templates = mutableListOf()
        }
        while (i < s.length) {
            when {
                s[i] == '"' -> { templates += string(); code.append('S') }
                skipCommentOrLiteral() -> Unit
                s[i] in "([{" -> { depth++; code.append(s[i++]) }
                s[i] in ")]}" && depth == 0 -> { i++; endArg(); return args }
                s[i] in ")]}" -> { depth--; code.append(s[i++]) }
                s[i] == ',' && depth == 0 -> { i++; endArg() }
                else -> code.append(s[i++])
            }
        }
        return args
    }

    companion object {
        val CALLS = linkedMapOf(
            "Log.wtf(" to LogCall.Kind.WARN,
            "Log.w(" to LogCall.Kind.WARN,
            "Log.e(" to LogCall.Kind.WARN,
            // Never stripped: the R8 rules name v, d and i only.
            "Log.println(" to LogCall.Kind.PRINTLN,
            "printStackTrace(" to LogCall.Kind.STACK_TRACE,
        )

        /** What a catch block or a failure lambda calls the exception in this codebase. */
        val THROWABLE_NAMES = setOf("e", "t", "it", "ex", "error", "exception", "throwable", "cause", "retry", "failure")

        /** Any other name a file gives one: `catch (x: ...)`, `onFailure { x ->`, `addOnFailureListener { x ->`. */
        val CAUGHT_AS = Regex("""catch\s*\(\s*(\w+)\s*:|(?:onFailure|addOnFailureListener)\s*(?:=\s*)?\{\s*(\w+)\s*->""")

        /** A failure named by its class, or by a status it carries. */
        val NAMED_BY_CLASS = Regex(
            """\w+(\.logSummary\(\)|\.javaClass\.simpleName|::class\.java\.simpleName|\.code\(\)|\.code|\.statusCode|\.errorCode)""",
        )

        val FORMAT_CALL = Regex("""\bformat\s*\(""")

        /** Identifier words that are user data or secrets on their own. */
        private val SENSITIVE_WORDS = setOf(
            "token", "password", "url", "uri", "email", "username", "cookie", "cookies", "authorization", "body",
        )

        /** Pairs of words, within one identifier, that are: courseNo, studentId, stackTrace and the like. */
        private val SENSITIVE_PAIRS = mapOf(
            "course" to setOf("no", "nos", "key", "keys", "number", "numbers", "id", "ids"),
            "student" to setOf("id", "ids", "no", "number"),
            "assignment" to setOf("id", "ids"),
            "device" to setOf("id", "ids"),
            "moodle" to setOf("id", "ids"),
            "user" to setOf("id", "ids", "name"),
            "stack" to setOf("trace"),
            "localized" to setOf("message"),
        )

        private val IDENTIFIER = Regex("""(\.?)\b([A-Za-z_][A-Za-z0-9_]*)""")
        private val WORD = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z0-9]+""")

        /** The words of an identifier: `moodleIdNumber` is moodle, id, number; `PUSH_BASE_URL` is push, base, url. */
        fun words(identifier: String): List<String> =
            identifier.split('_').flatMap { part -> WORD.findAll(part).map { it.value.lowercase() } }

        fun isSensitive(expr: String): Boolean = IDENTIFIER.findAll(expr).any { m ->
            val (dot, name) = m.destructured
            val words = words(name)
            (dot == "." && name == "message") ||
                words.any { it in SENSITIVE_WORDS } ||
                words.zipWithNext().any { (a, b) -> SENSITIVE_PAIRS[a]?.contains(b) == true } ||
                words.windowed(3).any { it == listOf("exception", "or", "null") }
        }

        /** Splits [code] on [separator] outside parentheses, brackets and braces. */
        fun splitTopLevel(code: String, separator: Char): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            code.forEachIndexed { index, c ->
                when {
                    c in "([{" -> depth++
                    c in ")]}" -> depth--
                    c == separator && depth == 0 -> { parts += code.substring(start, index).trim(); start = index + 1 }
                }
            }
            parts += code.substring(start).trim()
            return parts
        }

        /** The index of the parenthesis that closes the one at [open], or the end of [code]. */
        fun matchingParen(code: String, open: Int): Int {
            var depth = 0
            for (index in open until code.length) {
                when (code[index]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return index
                }
            }
            return code.length
        }
    }
}
