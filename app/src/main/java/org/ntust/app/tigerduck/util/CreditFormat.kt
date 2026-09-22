package org.ntust.app.tigerduck.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
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
 */
fun Float.formatCredits(locale: Locale): String = creditFormat(locale).format(this)

/**
 * The form to use inside a composable.
 *
 * It formats against the *configuration* locale — the same one the
 * `stringResource` calls around it resolve their templates against — rather
 * than `Locale.getDefault()`. Below API 33 `AppCompatDelegate.setApplicationLocales`
 * reaches Activity configurations only, so `Locale.getDefault()` keeps
 * reporting the *device* language; see `AppLanguageManager.localizedContext`.
 * Reading the default there would print "۳ credits" on a Persian-locale phone
 * running the app in English, where the old `%d` template printed "3".
 */
@Composable
fun Float.formatCredits(): String {
    val configuration = LocalConfiguration.current
    // NumberFormat is not thread-safe; remembering it is safe because
    // composition runs on the main thread, and it saves an ICU lookup per row.
    val format = remember(configuration) {
        creditFormat(configuration.locales[0] ?: Locale.getDefault())
    }
    return format.format(this)
}

/**
 * The portal's `CreditPoint` string as a credit count, or 0 for anything that
 * is not a real, non-negative number.
 *
 * The finiteness check is load-bearing: `toFloatOrNull` accepts "NaN" and
 * "Infinity", which the old `toIntOrNull` rejected, and `Gson.toJson` throws
 * `IllegalArgumentException` on either — from `DataCache.saveCourses` and
 * `WearScheduleBridge.publish`, neither of which wraps the encode.
 */
fun String?.toCreditsOrZero(): Float =
    this?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: 0f

private fun creditFormat(locale: Locale): NumberFormat =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
