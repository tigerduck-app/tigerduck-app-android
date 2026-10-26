package org.ntust.app.tigerduck.auth

import java.io.File

/**
 * `docs/account-state.md`, and the stores the phone app's sources declare: each SharedPreferences
 * and DataStore file with its keys, DataCache's files and folders, and the databases.
 * [AccountStateInventoryTest] checks each against the other, and `AccountSwitchTest` fills what
 * the inventory gives the school account.
 *
 * Read from the sources with regular expressions, as [SignOutClearingTest] reads `logout()`,
 * over the code alone: comments are blanked, and brackets are matched with strings' contents
 * blanked too. A store opened under a name this cannot work out, or a key it cannot read, is a
 * problem of its own rather than a store gone unseen. A key handed through a local is read at the
 * local's value, and one handed through a parameter at what each call passes; a source of one
 * file names the constants of its keys `KEY_…`, `K_…` or `…_KEY`, which count as its keys too.
 */
internal class AccountStateInventory(
    /** The phone app's Kotlin sources, by path under `app/src`. */
    val sources: Map<String, String>,
    /** The inventory's text. */
    val text: String,
) {

    /** One table row: the store, its name, its scope and what clears it. */
    data class Row(val store: String, val name: String, val scope: String, val clearer: Clearer?)

    /** The function that clears a row's store, by its file's name, and what it must call in its place. */
    data class Clearer(val file: String, val function: String, val via: String?)

    /** A key a source reads or writes: its file, its name (`prefix*` for a family), its type and default. */
    data class Key(val file: String, val name: String, val type: String?, val default: String?, val source: String)

    private val rowPattern = Regex("""^\|\s*`?([\w.-]+)`?\s*\|\s*`([^`]+)`\s*\|\s*(\w+)\s*\|(.*)\|\s*$""")
    private val clearerPattern = Regex("""`([\w.]+\.kt)`\s+`(\w+)`(?:\s+via\s+`([^`]+)`)?""")

    /** The inventory's rows. */
    val rows: List<Row> = text.lines().mapNotNull { line ->
        val match = rowPattern.matchEntire(line.trim()) ?: return@mapNotNull null
        val (store, name, scope, rest) = match.destructured
        val clearer = clearerPattern.find(rest)?.destructured?.let { (file, function, via) ->
            Clearer(file, function, via.ifEmpty { null })
        }
        Row(store, name, scope, clearer)
    }

    /** What the sources hold that this cannot read: a store's name or a key. */
    private val unreadable = mutableListOf<String>()

    // ---- the code ----

    /** Each source with its comments blanked, positions kept. */
    private val code: Map<String, String>

    /** Each source with its comments and its strings' and characters' contents blanked, positions kept. */
    private val bare: Map<String, String>

    init {
        val lexed = sources.mapValues { (_, text) -> lex(text) }
        code = lexed.mapValues { it.value.first }
        bare = lexed.mapValues { it.value.second }
    }

    /**
     * [text] with its comments blanked, and the same with its strings' and characters' literal
     * text blanked too. A string template's expression stays code, its own strings and comments
     * read as such.
     */
    private fun lex(text: String): Pair<String, String> =
        Lexer(text).lexCode(0, template = false).let { it.code.toString() to it.bare.toString() }

    private class Lexer(val text: String) {
        val code = StringBuilder(text)
        val bare = StringBuilder(text)

        private fun blank(into: StringBuilder, from: Int, to: Int) {
            for (index in from until minOf(to, text.length)) if (into[index] != '\n') into.setCharAt(index, ' ')
        }

        /** Reads code from [start] to the end, or in a template to the `}` closing it; returns this. */
        fun lexCode(start: Int, template: Boolean): Lexer {
            code(start, template)
            return this
        }

        /** Code from [start] to the end, or in a template to the `}` closing it; the index after. */
        private fun code(start: Int, template: Boolean): Int {
            var index = start
            var depth = 0
            while (index < text.length) {
                when {
                    text.startsWith("//", index) -> {
                        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                        blank(code, index, end)
                        blank(bare, index, end)
                        index = end
                    }
                    text.startsWith("/*", index) -> {
                        var nested = 0
                        var end = index
                        while (end < text.length) {
                            when {
                                text.startsWith("/*", end) -> {
                                    nested++
                                    end += 2
                                }
                                text.startsWith("*/", end) -> {
                                    end += 2
                                    if (--nested == 0) break
                                }
                                else -> end++
                            }
                        }
                        blank(code, index, end)
                        blank(bare, index, end)
                        index = end
                    }
                    text.startsWith("\"\"\"", index) -> index = string(index + 3, raw = true)
                    text[index] == '"' -> index = string(index + 1, raw = false)
                    text[index] == '\'' -> {
                        var end = index + 1
                        while (end < text.length && text[end] != '\'' && text[end] != '\n') {
                            if (text[end] == '\\') end++
                            end++
                        }
                        blank(bare, index + 1, end)
                        index = end + 1
                    }
                    template && text[index] == '{' -> {
                        depth++
                        index++
                    }
                    template && text[index] == '}' -> {
                        if (depth-- == 0) return index + 1
                        index++
                    }
                    else -> index++
                }
            }
            return index
        }

        /** A string's text from [start], its templates' expressions read as code; the index after it. */
        private fun string(start: Int, raw: Boolean): Int {
            var index = start
            var literal = start
            while (index < text.length) {
                when {
                    raw && text.startsWith("\"\"\"", index) -> {
                        blank(bare, literal, index)
                        var end = index + 3
                        while (end < text.length && text[end] == '"') end++
                        return end
                    }
                    !raw && text[index] == '"' -> {
                        blank(bare, literal, index)
                        return index + 1
                    }
                    !raw && text[index] == '\n' -> {
                        blank(bare, literal, index)
                        return index
                    }
                    !raw && text[index] == '\\' -> index += 2
                    text.startsWith("\${", index) -> {
                        blank(bare, literal, index)
                        index = code(index + 2, template = true)
                        literal = index
                    }
                    else -> index++
                }
            }
            blank(bare, literal, index)
            return index
        }
    }

    private val constantPattern = Regex("""\bconst\s+val\s+(\w+)\s*=\s*"((?:[^"\\$]|\\.)*)"""")

    /** Each source's string constants, by name. */
    private val constants: Map<String, Map<String, String>> by lazy {
        code.mapValues { (_, text) -> constantPattern.findAll(text).associate { it.groupValues[1] to it.groupValues[2] } }
    }

    /** The source that declares the class or object [name], if one does. */
    private fun declaring(name: String): String? =
        code.entries.firstOrNull { Regex("""\b(?:class|object|interface)\s+$name\b""").containsMatchIn(it.value) }?.key

    /** The index of the bracket closing the one at [open] in [path]'s code, strings and comments aside. */
    private fun closing(path: String, open: Int): Int? {
        val text = bare.getValue(path)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    /** The arguments of the call whose `(` is at [open] in [path], split at its own commas. */
    private fun arguments(path: String, open: Int): List<String>? {
        val close = closing(path, open) ?: return null
        val text = bare.getValue(path)
        val found = mutableListOf<String>()
        var depth = 0
        var start = open + 1
        for (index in open + 1 until close) {
            when (text[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    found += code.getValue(path).substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        found += code.getValue(path).substring(start, close).trim()
        return found.filter { it.isNotEmpty() }
    }

    /**
     * What the expression [expr] in [source] names: a string, `prefix*` for a template or for
     * anything joined on after a `+`, a constant of [source]'s or of the class it is qualified
     * by, a DataStore key of [source]'s, or what a function of [source]'s returns.
     */
    fun resolve(expr: String, source: String): String? {
        val text = expr.trim().removePrefix("name =").trim()
        val head = text.substringBefore(" + ").trim()
        val joined = head != text
        val own = code[source].orEmpty()
        val value = when {
            head.startsWith("\"") && head.endsWith("\"") && head.length >= 2 -> head.substring(1, head.length - 1)
            Regex("""^(?:\w+\.)?[A-Z][A-Z0-9_]*$""").matches(head) -> {
                val owner = head.substringBefore('.', "")
                val name = head.substringAfterLast('.')
                owner.takeIf { it.isNotEmpty() }?.let(::declaring)?.let { constants[it]?.get(name) }
                    ?: constants[source]?.get(name)
                    ?: Regex("""\bval\s+$name\s*=\s*\w+PreferencesKey\(\s*"([^"]+)"\s*\)""").find(own)?.groupValues?.get(1)
            }
            Regex("""^\w+\(.*\)$""").matches(head) -> {
                val function = head.substringBefore('(')
                val returned = Regex("""\bfun\s+$function\([^)]*\)(?:\s*:\s*String)?\s*=\s*([^\n]+)""").find(own)?.groupValues?.get(1)
                return returned?.takeIf { function !in it }?.let { resolve(it, source) }
                    ?.let { name -> if (joined && '*' !in name) "$name*" else name }
            }
            else -> null
        } ?: return null
        return when {
            '$' in value -> value.substringBefore('$') + "*"
            joined -> "$value*"
            else -> value
        }
    }

    private val openings = Regex("""\b(getSharedPreferences|EncryptedSharedPreferences\.create|preferencesDataStore)\(""")

    /** Each opening of a store in the sources: its source, where, and its name. */
    private data class Opening(val source: String, val at: Int, val name: String)

    private val allOpenings: List<Opening> by lazy {
        code.flatMap { (path, text) ->
            openings.findAll(text).mapNotNull { match ->
                val args = arguments(path, match.range.last) ?: return@mapNotNull null
                val expr = (if (match.groupValues[1] == "EncryptedSharedPreferences.create") args.getOrNull(1) else args.firstOrNull())
                    ?: return@mapNotNull null
                val name = resolve(expr, path)
                if (name == null) {
                    unreadable += "$path opens a store whose name this cannot read: `$expr`"
                    null
                } else {
                    Opening(path, match.range.first, name)
                }
            }.toList()
        }
    }

    /**
     * Sources handed a file another source opens, under no constant of their own: the token stores
     * and the owed unregister share the credential store CredentialManager opens.
     */
    private val sharedFiles = mapOf(
        "V3TokenStore.kt" to "tigerduck_credentials",
        "PendingUnregisterPrefs.kt" to "tigerduck_credentials",
    )

    /**
     * Each SharedPreferences and DataStore file the sources open, with the sources that read and
     * write it: those that open it, and the source of a class handed it under that class's own
     * constant, `PinAcceptanceStore(getSharedPreferences(PinAcceptanceStore.FILE, …))`.
     */
    val prefsFiles: Map<String, Set<String>> by lazy {
        val found = mutableMapOf<String, MutableSet<String>>()
        allOpenings.forEach { opening ->
            found.getOrPut(opening.name) { mutableSetOf() } += opening.source
            val text = code.getValue(opening.source)
            Regex("""^\s*(\w+)\.[A-Z][A-Z0-9_]*\b""").find(text.substring(opening.at).substringAfter('('))
                ?.groupValues?.get(1)?.let(::declaring)?.let { found.getValue(opening.name) += it }
        }
        sharedFiles.forEach { (file, store) ->
            code.keys.filter { it.endsWith("/$file") }.forEach { found.getOrPut(store) { mutableSetOf() } += it }
        }
        found
    }

    /** What the sources pass a key as that names none itself, and where the keys are read instead. */
    private val namedElsewhere = mapOf(
        // DisplayScale's entries; see [keys].
        "scale.key" to "DisplayScale.kt",
        // The inbox's K_FETCHED, or K_FETCHED_PREFIX and another folder's name.
        "fetchedKey(folder)" to "MailStateStore.kt",
    )

    private val keyCall = Regex(
        """(\.?)\b(get(?:Boolean|Int|Long|Float|String|StringSet)|put(?:Boolean|Int|Long|Float|String|StringSet)|remove|contains)\(""",
    )

    /** [receivers] of each source. */
    private val receiverMaps: Map<String, Map<String, String>> by lazy { code.keys.associateWith(::receivers) }

    /** The file each SharedPreferences variable, editor or function of [path] is, by its name. */
    private fun receivers(path: String): Map<String, String> {
        val text = code.getValue(path)
        val found = mutableMapOf<String, String>()
        val declaration = Regex(
            """(?:(?:val|var)\s+(\w+)(?:\s*:\s*[\w.<>?]+)?\s*(?:=|by\s+lazy\s*\{)|fun\s+(\w+)\([^)]*\)(?:\s*:\s*[\w.]+)?\s*=)\s*(?:[\w.]+\.)?$""",
        )
        allOpenings.filter { it.source == path }.forEach { opening ->
            val before = text.substring(maxOf(0, opening.at - 200), opening.at)
            declaration.find(before)?.let { found[it.groupValues[1].ifEmpty { it.groupValues[2] }] = opening.name }
        }
        Regex("""(?:val|var)\s+(\w+)\s*=\s*(\w+)\.edit\(\)""").findAll(text).forEach { match ->
            found[match.groupValues[2]]?.let { found[match.groupValues[1]] = it }
        }
        return found
    }

    /**
     * The first name of the call chain whose next link is the `.` at [dot] in [path]: `prefs` in
     * `prefs.edit().remove`. A link may start its own line, as a chain broken over lines does.
     */
    private fun chainStart(path: String, dot: Int): String? {
        val text = bare.getValue(path)
        var index = dot - 1
        while (index >= 0) {
            while (index >= 0 && text[index].isWhitespace()) index--
            if (index >= 0 && text[index] == ')') {
                var depth = 0
                while (index >= 0) {
                    if (text[index] == ')') depth++
                    if (text[index] == '(' && --depth == 0) break
                    index--
                }
                index--
            }
            if (index < 0 || !text[index].isJavaIdentifierPart()) return null
            val stop = index
            while (index >= 0 && text[index].isJavaIdentifierPart()) index--
            val name = text.substring(index + 1, stop + 1)
            if (index >= 0 && text[index] == '?') index--
            if (index < 0 || text[index] != '.') return name
            index--
        }
        return null
    }

    /**
     * For a call with no receiver at [at] in [path], inside an editor's block however deep,
     * `prefs.edit().apply { … }` or `prefs.edit(commit = true) { … }`, the first name of the
     * editor's chain; null anywhere else.
     */
    private fun editorBlockStart(path: String, at: Int): String? {
        val named = receiverMaps.getValue(path)
        val fallback = prefsFiles.filterValues { path in it }.keys.singleOrNull()
        val text = bare.getValue(path)
        var depth = 0
        var index = at - 1
        while (index >= 0) {
            when (text[index]) {
                '}' -> depth++
                '{' -> if (depth > 0) {
                    depth--
                } else {
                    var end = index - 1
                    while (end >= 0 && text[end].isWhitespace()) end--
                    if (end >= 0 && text[end] == ')') {
                        var parens = 0
                        while (end >= 0) {
                            if (text[end] == ')') parens++
                            if (text[end] == '(' && --parens == 0) break
                            end--
                        }
                        end--
                    }
                    val stop = end
                    while (end >= 0 && text[end].isJavaIdentifierPart()) end--
                    if (end >= 0 && text[end] == '.' && text.substring(end + 1, stop + 1) in setOf("apply", "edit", "run", "also")) {
                        // Another scope function's block inside the editor's goes on outward.
                        val receiver = chainStart(path, end)
                        if (receiver != null && (receiver in named || fallback != null && isPrefsName(receiver))) return receiver
                    }
                }
            }
            index--
        }
        return null
    }

    /** Whether [name] reads as a SharedPreferences, an editor of one, or a DataStore's preferences. */
    private fun isPrefsName(name: String) = Regex("""(?i)(prefs|preferences|editor|edit)$""").containsMatchIn(name)

    /**
     * A function of a source: its name, its parameters' names, where its body is, and whether it
     * extends a type, whose own functions its body then calls with no receiver.
     */
    private data class Function(val name: String, val parameters: List<String>, val body: IntRange, val extension: Boolean)

    private val functions: Map<String, List<Function>> by lazy {
        code.mapValues { (path, text) ->
            Regex("""\bfun\s+(?:<[^>]*>\s*)?([\w.]+\.)?(\w+)\s*\(""").findAll(text).mapNotNull { match ->
                val parameters = arguments(path, match.range.last).orEmpty()
                    .map { it.substringBefore(':').trim().removePrefix("vararg ").trim() }
                bodyRange(path, match)?.let { Function(match.groupValues[2], parameters, it, match.groupValues[1].isNotEmpty()) }
            }.toList()
        }
    }

    /** The innermost function of [path] whose body holds [at]. */
    private fun enclosing(path: String, at: Int): Function? =
        functions.getValue(path).filter { at in it.body }.minByOrNull { it.body.last - it.body.first }

    /**
     * What a key handed in as [name] at [at] in [path] can be, from where it comes: a local's
     * value, or what each call of the function it is a parameter of passes for it. Null when
     * this cannot read one of them; empty for a parameter no call passes.
     */
    private fun handedIn(name: String, path: String, at: Int, depth: Int = 0): List<String>? {
        if (depth > 3) return null
        val function = enclosing(path, at) ?: return null
        val text = code.getValue(path)
        Regex("""\b(?:val|var)\s+$name\s*=\s*([^\n]+)""").findAll(text.substring(function.body.first, at)).lastOrNull()?.let { local ->
            val expr = local.groupValues[1].trim()
            return resolve(expr, path)?.let(::listOf)
                ?: if (Regex("""^[a-z]\w*$""").matches(expr)) handedIn(expr, path, at, depth + 1) else null
        }
        val position = function.parameters.indexOf(name)
        if (position < 0) return null
        // A call of it from another source names its class, and makes it on an instance of it: on
        // `this`, or on a name the caller declares of that class, whatever the name.
        val owner = path.substringAfterLast('/').removeSuffix(".kt")
        val found = mutableListOf<String>()
        code.forEach { (caller, callerText) ->
            if (caller != path && !Regex("""\b$owner\b""").containsMatchIn(callerText)) return@forEach
            Regex("""(\b\w+\.)?\b${function.name}\(""").findAll(callerText).forEach { call ->
                val before = callerText.substring(maxOf(0, call.range.first - 60), call.range.first)
                if (Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?$""").containsMatchIn(before)) return@forEach
                val receiver = call.groupValues[1].removeSuffix(".")
                // A link of a chain, on another call's result: a SharedPreferences editor's among them.
                if (bare.getValue(caller).substring(0, call.range.first).trimEnd().endsWith(".")) return@forEach
                if (receiver.isNotEmpty()) {
                    if (receiver != "this" && !Regex("""\b$receiver\s*:\s*$owner\b""").containsMatchIn(callerText)) return@forEach
                } else if (caller != path || enclosing(caller, call.range.first)?.extension == true ||
                    editorBlockStart(caller, call.range.first) != null
                ) {
                    // Another source's, or a call to the type an extension or an editor's block is on.
                    return@forEach
                }
                val args = arguments(caller, call.range.last) ?: return@forEach
                if (args.size > function.parameters.size) return@forEach
                val arg = args.firstOrNull { it.startsWith("$name =") }?.substringAfter('=')?.trim()
                    ?: args.getOrNull(position)
                    ?: return@forEach
                val names = resolve(arg, caller)?.let(::listOf)
                    ?: if (Regex("""^[a-z]\w*$""").matches(arg)) handedIn(arg, caller, call.range.first, depth + 1) else null
                found += names ?: return null
            }
        }
        return found
    }

    /**
     * The keys the sources read and write, by file. A call on a SharedPreferences, an editor of
     * one, a function handing one out or inside an editor's block is placed in the file the
     * receiver is ([receivers]); a call on another `prefs` or editor, in the file its source reads
     * and writes when there is one. Also: the constants of keys in a source of one file; the keys
     * of a DataStore's `…PreferencesKey`; and DisplayScale's entries, which name the keys
     * AppPreferences reads them under.
     */
    val keys: Map<String, List<Key>> by lazy {
        val found = mutableListOf<Key>()
        code.forEach { (path, text) ->
            val named = receiverMaps.getValue(path)
            val fallback = prefsFiles.filterValues { path in it }.keys.singleOrNull()
            keyCall.findAll(text).forEach { match ->
                val call = match.groupValues[2]
                val args = arguments(path, match.range.last) ?: return@forEach
                val wanted = if (call.startsWith("get") || call.startsWith("put")) 2 else 1
                if (args.size != wanted) return@forEach
                val receiver = if (match.groupValues[1] == ".") chainStart(path, match.range.first) else editorBlockStart(path, match.range.first)
                val file = named[receiver]
                    ?: fallback?.takeIf { receiver != null && isPrefsName(receiver) }
                    ?: return@forEach
                val expr = args[0]
                val type = Regex("""^(?:get|put)(\w+)$""").find(call)?.groupValues?.get(1)
                val default = if (call.startsWith("get")) args[1] else null
                val names = resolve(expr, path)?.let(::listOf) ?: when {
                    expr in namedElsewhere -> emptyList()
                    // Each key read back from the file itself, removed.
                    expr == "it" && call == "remove" -> emptyList()
                    Regex("""^[a-z]\w*$""").matches(expr) -> handedIn(expr, path, match.range.first)
                    else -> null
                }
                if (names == null) {
                    unreadable += "$path reads or writes a key of $file this cannot read: `$expr`"
                } else {
                    names.forEach { found += Key(file, it, type, default, path) }
                }
            }
            if (fallback != null) {
                constants[path].orEmpty().forEach { (name, value) ->
                    if (Regex("""^(?:KEY|K)_\w+$|^\w+_KEY$""").matches(name) && !name.startsWith("EXTRA_")) {
                        found += Key(fallback, if (name.endsWith("_PREFIX")) "$value*" else value, null, null, path)
                    }
                }
                Regex("""\b(\w+)PreferencesKey\(""").findAll(text).forEach { match ->
                    val expr = arguments(path, match.range.last)?.firstOrNull() ?: return@forEach
                    val name = resolve(expr, path)
                    if (name == null) {
                        unreadable += "$path keeps a DataStore key this cannot read: `$expr`"
                    } else {
                        found += Key(fallback, name, null, null, path)
                    }
                }
            }
            if (path.endsWith("/DisplayScale.kt")) {
                Regex("""^\s*[A-Z]\w*\(\s*"(\w+)"\s*,""", RegexOption.MULTILINE).findAll(text).forEach {
                    found += Key("tigerduck_prefs", it.groupValues[1], "Float", null, path)
                }
            }
        }
        // A key's constant says only its name, and a write no default: a read says both.
        found.groupBy { it.file to it.name }.values
            .map { same -> same.firstOrNull { it.default != null } ?: same.firstOrNull { it.type != null } ?: same.first() }
            .groupBy { it.file }
    }

    private val dataCache: String get() = code.keys.single { it.endsWith("/data/cache/DataCache.kt") }

    /**
     * The names of the files and folders DataCache keeps: `prefix_*.json` for a family, `name/`
     * for a folder, and `*.tmp` for what a write a process death cut short leaves beside its file.
     */
    val cacheFiles: Set<String> by lazy {
        val text = code.getValue(dataCache)
        Regex(""""([\w-]+\.json)"""").findAll(text).map { it.groupValues[1] }.toSet() +
            Regex(""""([\w-]+_)\$\{?\w+}?\.json"""").findAll(text).map { it.groupValues[1] + "*.json" } +
            Regex("""const\s+val\s+\w+_DIR\s*=\s*"([\w-]+)"""").findAll(text).map { it.groupValues[1] + "/" } +
            listOfNotNull("*.tmp".takeIf { "\".tmp\"" in text })
    }

    /**
     * Where DataCache keeps [name]: `cache` (`cacheDir/TigerDuckCache`), `data`
     * (`filesDir/TigerDuckData`) or `files` (`filesDir`), as the first line naming it says.
     */
    fun cacheRoot(name: String): String? {
        val text = code.getValue(dataCache)
        val names = aliases(name, listOf(dataCache))
        text.lines().filter { line -> quoted(name) in line || names.any { Regex("""\b$it\b""").containsMatchIn(line) } }
            .forEach { line ->
                when {
                    Regex("""\b(?:saveToUserData\w*|loadFromUserData|userDataDir)\b""").containsMatchIn(line) -> return "data"
                    "context.filesDir" in line -> return "files"
                    Regex("""\b(?:save|load)\s*[(<]|\bcacheDir\b""").containsMatchIn(line) -> return "cache"
                }
            }
        return null
    }

    /** The databases the sources name. */
    val databases: Set<String> by lazy {
        code.values.flatMap { text -> Regex(""""([\w-]+\.db)"""").findAll(text).map { it.groupValues[1] }.toList() }.toSet()
    }

    // ---- what a clearer does ----

    /** The source named [file]: one of them, or null with none or several. */
    fun sourceNamed(file: String): String? = code.keys.filter { it.substringAfterLast('/') == file }.singleOrNull()

    /**
     * Where the body of the function [declaration] declares is in [path]: its block, or its
     * expression with the lines indented under it. Null for a declaration with neither, as in an
     * interface.
     */
    private fun bodyRange(path: String, declaration: MatchResult): IntRange? {
        val text = code.getValue(path)
        val close = closing(path, declaration.range.last) ?: return null
        val lineEnd = text.indexOf('\n', close).let { if (it < 0) text.length else it }
        val rest = bare.getValue(path).substring(close + 1, lineEnd)
        val brace = rest.indexOf('{')
        val equals = rest.indexOf('=')
        return when {
            equals >= 0 && (brace < 0 || equals < brace) ->
                (close + 1 + equals) until indentedEnd(text, declaration.range.first, close + 1 + equals)
            brace >= 0 -> closing(path, close + 1 + brace)?.let { (close + 1 + brace)..it }
            else -> null
        }
    }

    /**
     * Where the body of the function [name] declares in [path] is: the first with one, or the
     * block of an object handed over as `name = object : … {`.
     */
    fun body(path: String, name: String): IntRange? {
        val text = code.getValue(path)
        Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?$name\s*\(""").findAll(text).forEach { match ->
            bodyRange(path, match)?.let { return it }
        }
        return Regex("""\b$name\s*=\s*object\b[^{]*\{""").find(text)?.let { match ->
            closing(path, match.range.last)?.let { match.range.last..it }
        }
    }

    private fun indent(text: String, at: Int): Int {
        val start = text.lastIndexOf('\n', at) + 1
        return text.substring(start).takeWhile { it == ' ' }.length
    }

    /** The end of [from]'s line and of every line after it indented deeper than [declaration]'s. */
    private fun indentedEnd(text: String, declaration: Int, from: Int): Int {
        val own = indent(text, declaration)
        var end = text.indexOf('\n', from).let { if (it < 0) text.length else it }
        while (end < text.length) {
            val next = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
            val line = text.substring(end + 1, next)
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= own) break
            end = next
        }
        return end
    }

    /** [name] as a source quotes it: `"name"`, `"prefix` for a family, `suffix"` for `*suffix`. */
    private fun quoted(name: String): String {
        val literal = name.removeSuffix("/")
        return when {
            literal.startsWith("*") -> literal.removePrefix("*") + "\""
            '*' in literal -> "\"" + literal.substringBefore('*')
            else -> "\"$literal\""
        }
    }

    /**
     * The names that stand for [value] in [paths]: the constants holding it (its fixed part for
     * a family), and each function or property whose declaration, with the lines indented under
     * it, quotes it or names one of those constants.
     */
    fun aliases(value: String, paths: Collection<String>): Set<String> {
        val literal = value.removeSuffix("/").replace("*", "")
        val found = mutableSetOf<String>()
        paths.forEach { path ->
            constants[path].orEmpty().forEach { (name, held) -> if (held == literal) found += name }
        }
        paths.forEach { path ->
            val text = code[path] ?: return@forEach
            Regex("""\b(?:fun|val|var)\s+(?:<[^>]*>\s*)?(\w+)""").findAll(text).forEach { match ->
                val chunk = text.substring(match.range.first, indentedEnd(text, match.range.first, match.range.first))
                if (quoted(value) in chunk || found.any { Regex("""\b$it\b""").containsMatchIn(chunk) }) found += match.groupValues[1]
            }
        }
        return found
    }

    /**
     * Whether [clearer] removes the key [name]: `remove(…)` of it, an alias of it set to an empty
     * value, or for a family `startsWith(…)` of it beside a removal.
     */
    private fun removes(clearer: String, name: String, aliases: Set<String>): Boolean {
        val names = (aliases.map { Regex.escape(it) + "\\b" } + Regex.escape(quoted(name))).joinToString("|")
        val emptied = """(?:null|false|0L?|0f|""|emptySet\(\)|emptyList\(\)|emptyMap\(\))"""
        val assigned = aliases.joinToString("|") { Regex.escape(it) }.ifEmpty { "(?!)" }
        return Regex("""remove\(\s*(?:$names)""").containsMatchIn(clearer) ||
            Regex("""\b(?:$assigned)\s*=\s*$emptied""").containsMatchIn(clearer) ||
            ('*' in name && Regex("""startsWith\(\s*(?:$names)""").containsMatchIn(clearer) && "remove" in clearer)
    }

    /**
     * Whether a statement of the body at [range] in [path] that names [name] or one of [aliases]
     * deletes: the statement of the innermost block around the name that is no lambda taking
     * parameters, a chain broken over lines included. It deletes when it calls `delete()` or
     * `deleteRecursively()`, or a function of [path]'s among [aliases] that does.
     */
    private fun deletes(path: String, range: IntRange, name: String, aliases: Set<String>): Boolean {
        val text = code.getValue(path)
        val structure = bare.getValue(path)
        fun deleting(statement: String) = "delete()" in statement || "deleteRecursively()" in statement
        val pattern = Regex((aliases.map { "\\b" + Regex.escape(it) + "\\b" } + Regex.escape(quoted(name))).joinToString("|"))
        return pattern.findAll(text.substring(range.first, range.last + 1)).any { found ->
            val mention = range.first + found.range.first
            var open = mention
            var depth = 0
            while (open > range.first) {
                open--
                when (structure[open]) {
                    '}' -> depth++
                    '{' -> if (depth > 0) depth-- else if (!Regex("""^\{[^{}]*->""").containsMatchIn(structure.substring(open))) break
                }
            }
            val close = closing(path, open)?.takeIf { structure[open] == '{' } ?: range.last
            val statement = statement(path, open + 1, close, mention)
            deleting(statement) || aliases.any { alias ->
                Regex("""\b$alias\(""").containsMatchIn(statement) &&
                    body(path, alias)?.let { deleting(text.substring(it.first, it.last + 1)) } == true
            }
        }
    }

    /** The statement of the block from [start] to [end] in [path] that holds [at]. */
    private fun statement(path: String, start: Int, end: Int, at: Int): String {
        val structure = bare.getValue(path)
        val breaks = mutableListOf(start)
        var depth = 0
        for (index in start until end) {
            when (structure[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '\n' -> if (depth == 0) {
                    val next = structure.substring(index + 1, end).trimStart()
                    if (!next.startsWith(".") && !next.startsWith("?.")) breaks += index + 1
                }
            }
        }
        val from = breaks.last { it <= at }
        val to = breaks.firstOrNull { it > at } ?: end
        return code.getValue(path).substring(from, to)
    }

    // ---- the check ----

    /** Every store the sources declare, as its row would name it: a file an account clears whole, without its keys. */
    val declared: Set<Pair<String, String>> by lazy {
        val whole = rows.filter { it.store == "prefs" && it.scope in ACCOUNT_SCOPES }.map { it.name }.toSet()
        prefsFiles.keys.map { "prefs" to it }.toSet() +
            cacheFiles.map { "cache" to it } +
            databases.map { "database" to it } +
            keys.filterKeys { it !in whole }.flatMap { (file, keys) -> keys.map { file to it.name } }
    }

    /**
     * What is wrong with the inventory: a store or key this cannot read, a store with no row, a
     * row with nothing declared, a scope it does not know, a file outliving the accounts whose keys
     * are not listed, and an account's row that names nothing that clears it, or a function that
     * no longer does.
     */
    fun problems(): List<String> {
        val errors = mutableListOf<String>()
        // Read first: each reading notes what it cannot read.
        val declared = declared
        errors += unreadable.distinct()
        rows.groupBy { it.store to it.name }.filterValues { it.size > 1 }.keys.forEach { (store, name) ->
            errors += "$store `$name` has more than one row"
        }
        val listed = rows.map { it.store to it.name }.toSet()
        (declared - listed).sortedBy { it.toString() }.forEach { (store, name) ->
            errors += "$store `$name` has no row in docs/account-state.md"
        }
        (listed - declared).sortedBy { it.toString() }.forEach { (store, name) ->
            errors += "$store `$name` is listed but no longer declared"
        }
        rows.forEach { row ->
            val label = "${row.store} `${row.name}`"
            when {
                row.scope !in SCOPES -> errors += "$label has unknown scope ${row.scope}"
                row.scope == "keys" && row.store != "prefs" -> errors += "$label: only a file's keys are listed apart"
                row.scope == "device" && row.store == "prefs" -> errors += "$label: a file outliving the accounts lists its keys (scope keys)"
                row.scope in ACCOUNT_SCOPES -> clearingProblem(row)?.let { errors += "$label$it" }
            }
        }
        return errors
    }

    /**
     * Whether the body at [range] in [path] clears [file] whole: `clear()` on an editor of it,
     * `prefs.edit().clear()`, not on anything else that has a `clear()`.
     */
    private fun clearsEditor(path: String, range: IntRange, file: String): Boolean {
        val named = receiverMaps.getValue(path)
        val fallback = prefsFiles.filterValues { path in it }.keys.singleOrNull()
        return Regex("""\.clear\(\)""").findAll(code.getValue(path).substring(range.first, range.last + 1)).any { match ->
            val dot = range.first + match.range.first
            val receiver = chainStart(path, dot) ?: return@any false
            val chain = bare.getValue(path).substring(0, dot).substringAfterLast(receiver)
            val store = named[receiver] ?: fallback?.takeIf { isPrefsName(receiver) }
            store == file && (Regex("""^\s*\.edit\(\)\s*$""").matches(chain) || Regex("""(?i)edit(or)?$""").containsMatchIn(receiver))
        }
    }

    private fun clearingProblem(row: Row): String? {
        val clearer = row.clearer ?: return " belongs to an account but names nothing that clears it"
        val path = sourceNamed(clearer.file) ?: return ": no single source is named ${clearer.file}"
        val range = body(path, clearer.function) ?: return ": no `${clearer.function}` in ${clearer.file}"
        val body = code.getValue(path).substring(range.first, range.last + 1)
        val clears = when {
            clearer.via != null -> clearer.via in body
            row.store == "prefs" -> path in prefsFiles[row.name].orEmpty() && (
                clearsEditor(path, range, row.name) ||
                    keys[row.name].orEmpty().all { removes(body, it.name, aliases(it.name, prefsFiles.getValue(row.name))) }
                )
            row.store == "cache" -> path == dataCache && deletes(path, range, row.name, aliases(row.name, listOf(dataCache)))
            row.store == "database" -> false
            else -> removes(body, row.name, aliases(row.name, prefsFiles[row.store].orEmpty()))
        }
        return if (clears) null else ": `${clearer.function}` in ${clearer.file} does not clear it"
    }

    companion object {
        /** The scopes a row may give; see the inventory's opening. */
        val SCOPES = setOf("school", "mail", "library", "owed", "device", "keys")

        /** The scopes a sign-out clears, whose rows name what clears them. */
        val ACCOUNT_SCOPES = setOf("school", "mail", "library", "owed")

        val appDir: File by lazy { locateApp() }

        val inventoryFile: File get() = File(appDir.parentFile, "docs/account-state.md")

        /** The phone app's Kotlin sources, main, both flavors and debug, by path under `app/src`. */
        fun appSources(): Map<String, String> = listOf("main", "play", "fdroid", "debug").flatMap { set ->
            File(appDir, "src/$set/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(File(appDir, "src")).invariantSeparatorsPath to it.readText() }
                .toList()
        }.toMap()

        /** The app's sources and the inventory as they are in the repository. */
        val app: AccountStateInventory by lazy { AccountStateInventory(appSources(), inventoryFile.readText()) }

        private fun locateApp(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val here = dir
                if (File(here, "app/src/main/java").isDirectory) return File(here, "app")
                if (File(here, "src/main/java").isDirectory) return here
                dir = here.parentFile
            }
            throw AssertionError("Could not locate the :app module from ${File("").absolutePath}")
        }
    }
}
