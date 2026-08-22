package org.ntust.app.tigerduck.network

import org.ntust.app.tigerduck.AppConstants
import java.util.Calendar

/**
 * Semester-code arithmetic, split out of [CourseService] so [SemesterCatalog]
 * can reach it without a Hilt dependency cycle (CourseService already depends
 * on the catalogue's attribution).
 *
 * A code is `<ROC year><term>`, e.g. `1151` = 115 學年度第 1 學期. The
 * catalogue also publishes 暑期 terms as `114H`, which this arithmetic
 * deliberately cannot produce — see [previous].
 */
object SemesterCodes {

    /**
     * The month-based guess [CourseService.currentSemesterCode] used before it
     * was pinned to [AppConstants.CurrentTerm]. Kept as the last-resort
     * fallback for [SemesterCatalog] when the endpoint is unreachable on a
     * fresh install.
     *
     * Inherently lags: NTUST publishes a term weeks before the month rolls
     * over, which is the whole reason the pin and the catalogue exist.
     */
    fun heuristic(): String {
        // Pin to gregorian — Calendar.getInstance on a TW device can return
        // ROC-era years and would shift the rocYear math.
        val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val rocYear = year - 1911
        return when (month) {
            in 2..8 -> "${rocYear - 1}2"   // Spring semester
            in 9..12 -> "${rocYear}1"       // Fall semester
            else -> "${rocYear - 1}1"       // January: still in prior fall semester
        }
    }

    /**
     * The term before [code], or [code] itself when it isn't a regular term.
     *
     * Returning the input unchanged for 暑期 (`114H`) or malformed codes lets
     * [walkBack] dedupe the repeat away rather than emit garbage — the walk is
     * only ever a pre-catalogue fallback, so a short list beats a wrong one.
     */
    fun previous(code: String): String {
        val year = code.dropLast(1).toIntOrNull() ?: return code
        return when (code.last()) {
            '2' -> "${year}1"
            '1' -> "${year - 1}2"
            else -> code
        }
    }

    /** [count] terms ending at [from], newest first, with repeats dropped. */
    fun walkBack(from: String, count: Int): List<String> {
        val result = LinkedHashSet<String>()
        var code = from
        repeat(count) {
            if (!result.add(code)) return result.toList()
            code = previous(code)
        }
        return result.toList()
    }
}
