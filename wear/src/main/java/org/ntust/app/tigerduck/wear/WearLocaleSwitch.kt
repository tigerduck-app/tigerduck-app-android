package org.ntust.app.tigerduck.wear

import java.util.Locale

/**
 * When the watch recreates its activity to show the phone's language.
 *
 * The tag is stored twice: in the snapshot, and in a start-up copy that
 * `MainActivity.attachBaseContext` applies. When the copy is behind (the
 * process died between the two writes), every new instance comes up in the old
 * language and asks to recreate again: an endless relaunch with a blank screen.
 * So the copy is brought up to date before recreating
 * ([org.ntust.app.tigerduck.wear.data.SchedulePersistence.syncLanguageTag]), and
 * a language is recreated for at most once until the screen shows what was wanted.
 */
internal class WearLocaleSwitch {

    enum class Step {
        /** The screen shows the wanted language, or the phone sent none. */
        Keep,

        /** Bring the start-up copy up to date, then recreate. */
        Recreate,

        /** Recreated for this language already and it didn't take: recreating again would loop. */
        Stay,
    }

    /** The language last recreated for, [normalized]; cleared once the screen shows what was wanted. */
    @Volatile
    private var triedFor: String? = null

    /** What to do when the phone wants [wanted] and the screen shows [showing]. */
    fun step(wanted: String?, showing: String?): Step {
        // No language yet, as in a new instance's snapshot before the stored one arrives:
        // that says nothing about the last recreate, so it stays recorded.
        val want = normalized(wanted) ?: return Step.Keep
        if (want == normalized(showing)) {
            triedFor = null
            return Step.Keep
        }
        return if (want == triedFor) Step.Stay else Step.Recreate
    }

    /** Records that the activity is being recreated for [tag]. */
    fun recreating(tag: String) {
        triedFor = normalized(tag)
    }

    companion object {
        /** The one this process keeps, across its activity's instances. */
        val process = WearLocaleSwitch()

        /** [tag] in BCP 47's own form, lower case, so "zh-hant-tw" and "zh-Hant-TW" are one; null for none. */
        fun normalized(tag: String?): String? =
            tag?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Locale.forLanguageTag(it).toLanguageTag().lowercase(Locale.ROOT) }
                ?.takeIf { it != "und" }
    }
}
