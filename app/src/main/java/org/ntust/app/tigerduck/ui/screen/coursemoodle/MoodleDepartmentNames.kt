package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleDepartment

/**
 * The app-translation string naming the department [key] ([MoodleDepartment]): the school's
 * official name for a member's page, or the [short] one for the members list; null for a key it
 * has none for. Chinese names in Chinese, the English name everywhere else (#697).
 */
@StringRes
internal fun departmentNameRes(key: String, short: Boolean): Int? = when (key) {
    "ac" -> if (short) R.string.course_member_department_ac_short else R.string.course_member_department_ac
    "ad" -> if (short) R.string.course_member_department_ad_short else R.string.course_member_department_ad
    "ai" -> if (short) R.string.course_member_department_ai_short else R.string.course_member_department_ai
    "as" -> if (short) R.string.course_member_department_as_short else R.string.course_member_department_as
    "ba" -> if (short) R.string.course_member_department_ba_short else R.string.course_member_department_ba
    "be" -> if (short) R.string.course_member_department_be_short else R.string.course_member_department_be
    "ce" -> if (short) R.string.course_member_department_ce_short else R.string.course_member_department_ce
    "ch" -> if (short) R.string.course_member_department_ch_short else R.string.course_member_department_ch
    "ci" -> if (short) R.string.course_member_department_ci_short else R.string.course_member_department_ci
    "cs" -> if (short) R.string.course_member_department_cs_short else R.string.course_member_department_cs
    "ct" -> if (short) R.string.course_member_department_ct_short else R.string.course_member_department_ct
    "dt" -> if (short) R.string.course_member_department_dt_short else R.string.course_member_department_dt
    "ec" -> if (short) R.string.course_member_department_ec_short else R.string.course_member_department_ec
    "ee" -> if (short) R.string.course_member_department_ee_short else R.string.course_member_department_ee
    "en" -> if (short) R.string.course_member_department_en_short else R.string.course_member_department_en
    "eo" -> if (short) R.string.course_member_department_eo_short else R.string.course_member_department_eo
    "es" -> if (short) R.string.course_member_department_es_short else R.string.course_member_department_es
    "et" -> if (short) R.string.course_member_department_et_short else R.string.course_member_department_et
    "fl" -> if (short) R.string.course_member_department_fl_short else R.string.course_member_department_fl
    "fn" -> if (short) R.string.course_member_department_fn_short else R.string.course_member_department_fn
    "gd" -> if (short) R.string.course_member_department_gd_short else R.string.course_member_department_gd
    "hc" -> if (short) R.string.course_member_department_hc_short else R.string.course_member_department_hc
    "ic" -> if (short) R.string.course_member_department_ic_short else R.string.course_member_department_ic
    "im" -> if (short) R.string.course_member_department_im_short else R.string.course_member_department_im
    "ma" -> if (short) R.string.course_member_department_ma_short else R.string.course_member_department_ma
    "mb" -> if (short) R.string.course_member_department_mb_short else R.string.course_member_department_mb
    "me" -> if (short) R.string.course_member_department_me_short else R.string.course_member_department_me
    "mg" -> if (short) R.string.course_member_department_mg_short else R.string.course_member_department_mg
    "mi" -> if (short) R.string.course_member_department_mi_short else R.string.course_member_department_mi
    "pa" -> if (short) R.string.course_member_department_pa_short else R.string.course_member_department_pa
    "si" -> if (short) R.string.course_member_department_si_short else R.string.course_member_department_si
    "tm" -> if (short) R.string.course_member_department_tm_short else R.string.course_member_department_tm
    "tx" -> if (short) R.string.course_member_department_tx_short else R.string.course_member_department_tx
    "ve" -> if (short) R.string.course_member_department_ve_short else R.string.course_member_department_ve
    else -> null
}

/** The short names the list shows, by department key, so its search matches what its rows say. */
@Composable
internal fun departmentShortNames(): Map<String, String> {
    val names = mutableMapOf<String, String>()
    for (key in MoodleDepartment.keys) {
        val res = departmentNameRes(key, short = true) ?: continue
        names[key] = stringResource(res)
    }
    return names
}
