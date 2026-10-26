package org.ntust.app.tigerduck.wear.ui

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.wear.R

/**
 * The caption naming a later class's [weekday] (1 = Monday) on Now & Next, the
 * tile and the complication. Not `weekday_*_short`: in zh-Hant a short name is
 * the bare numeral the phone's timetables head their columns with, which alone
 * does not read as a day.
 */
@StringRes
internal fun weekdayCaptionRes(weekday: Int): Int = when (weekday) {
    1 -> R.string.weekday_mon_caption
    2 -> R.string.weekday_tue_caption
    3 -> R.string.weekday_wed_caption
    4 -> R.string.weekday_thu_caption
    5 -> R.string.weekday_fri_caption
    6 -> R.string.weekday_sat_caption
    else -> R.string.weekday_sun_caption
}
