package org.ntust.app.tigerduck.ui.screen.informationsystem

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.model.PortalCategory

@get:StringRes
val PortalCategory.displayNameRes: Int
    get() = when (this) {
        PortalCategory.CURRICULUM -> R.string.information_system_category_curriculum
        PortalCategory.PERSON_INFO -> R.string.information_system_category_person_info
        PortalCategory.CAMPUS_LIFE -> R.string.information_system_category_campus_life
        PortalCategory.FINANCIAL_SUPPORT -> R.string.information_system_category_financial_support
        PortalCategory.ACTIVITIES -> R.string.information_system_category_activities
        PortalCategory.RESOURCES -> R.string.information_system_category_resources
    }

@get:StringRes
val PortalCategory.descriptionRes: Int
    get() = when (this) {
        PortalCategory.CURRICULUM -> R.string.information_system_category_curriculum_description
        PortalCategory.PERSON_INFO -> R.string.information_system_category_person_info_description
        PortalCategory.CAMPUS_LIFE -> R.string.information_system_category_campus_life_description
        PortalCategory.FINANCIAL_SUPPORT -> R.string.information_system_category_financial_support_description
        PortalCategory.ACTIVITIES -> R.string.information_system_category_activities_description
        PortalCategory.RESOURCES -> R.string.information_system_category_resources_description
    }
