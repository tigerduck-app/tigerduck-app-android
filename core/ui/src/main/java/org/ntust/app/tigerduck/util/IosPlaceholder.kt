package org.ntust.app.tigerduck.util

/**
 * Substitute the iOS-style positional placeholder `%1$@` (or `%2$@`, etc.) with
 * an Android-side value. The localization submodule's grouped-source schema is
 * iOS-first and emits `%@` placeholders verbatim into the Android strings; Java
 * formatting wants `%s`, so a runtime `replace` is the lightest cross-platform
 * shim until the submodule grows an Android-specific formatter.
 *
 * Centralized here (instead of inlined at the call site) so a third surface
 * that hits the same shape can reuse this rather than re-implementing the
 * escape — and so the right fix (submodule emits `%s` directly) has exactly
 * one chase point to delete.
 */
fun String.replaceIosArg(arg: Int, value: String): String =
    replace("%$arg\$@", value)

/** [replaceIosArg] for a string's one unnumbered `%@`. */
fun String.replaceIosArg(value: String): String = replace("%@", value)

/**
 * The integer form of [replaceIosArg]: iOS writes a count as `%1$lld` (or `%1$ld`, `%1$d`),
 * and Java's formatter rejects the `ll` length modifier outright.
 */
fun String.replaceIosArg(arg: Int, value: Long): String =
    replace(Regex("%$arg\\$(?:ll|l)?d"), value.toString())
