package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.BuildConfig

/**
 * Spec §12.5: until the computer center's written consent, School Mail is
 * only visible in debug builds, behind the developer toggle.
 */
object SchoolMailAvailability {
    fun isVisible(
        devToggle: Boolean,
        released: Boolean = BuildConfig.SCHOOL_MAIL_RELEASED,
        debug: Boolean = BuildConfig.DEBUG,
    ): Boolean = released || (debug && devToggle)
}
