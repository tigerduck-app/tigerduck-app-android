package org.ntust.app.tigerduck.util

import java.text.NumberFormat
import java.util.Locale

/**
 * A credit count written the way a student writes it: "3", "1.5", "0.5" —
 * never "3.0".
 *
 * NTUST issues half credits, so credits are a [Float] everywhere rather than
 * the [Int] they used to be (which silently turned a 0.5-credit course into a
 * 0-credit one). Nine courses in ten are still whole numbers, and a trailing
 * ".0" on all of them is noise.
 *
 * Locale-aware, so the half lands as "0,5" where that is how a number is
 * written. The formatter is not thread-safe, so a fresh one per call rather
 * than a shared instance — these are rendered a handful at a time.
 */
fun Float.formatCredits(locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(this)
