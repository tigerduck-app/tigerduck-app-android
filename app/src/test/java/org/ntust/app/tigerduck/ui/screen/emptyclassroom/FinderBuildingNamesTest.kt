package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.core.strings.R

/**
 * Every building the finder places rooms in is named from app-translation (#610), in the user's
 * language; a code with no string falls back to the booking site's name.
 */
class FinderBuildingNamesTest {

    @Test
    fun `every building on the campus map has its own string`() {
        val expected = mapOf(
            "AD" to R.string.empty_classroom_building_ad, "AU" to R.string.empty_classroom_building_au,
            "E1" to R.string.empty_classroom_building_e1, "E2" to R.string.empty_classroom_building_e2,
            "EE" to R.string.empty_classroom_building_ee, "IA" to R.string.empty_classroom_building_ia,
            "IB" to R.string.empty_classroom_building_ib, "LB" to R.string.empty_classroom_building_lb,
            "MA" to R.string.empty_classroom_building_ma, "RB" to R.string.empty_classroom_building_rb,
            "T1" to R.string.empty_classroom_building_t1, "T2" to R.string.empty_classroom_building_t2,
            "T3" to R.string.empty_classroom_building_t3, "T4" to R.string.empty_classroom_building_t4,
            "TR" to R.string.empty_classroom_building_tr,
        )
        assertEquals(expected, expected.keys.associateWith(::buildingNameRes))
    }

    @Test
    fun `a code with no string has none, so the site's name shows`() {
        assertNull(buildingNameRes("ZZ"))
    }
}
