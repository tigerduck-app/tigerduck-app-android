package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager

/**
 * The two languages What's New is written in, like `assets/whatsnew.json`:
 * Traditional Chinese for every Chinese-family UI language, English for the
 * rest. Page copy deliberately stays out of `app-translation` — release copy
 * is written once in these two languages, not machine-spread to every locale.
 */
enum class WhatsNewLanguage {
    ZhHant, En;

    companion object {
        /**
         * Same rule the summary lookup uses
         * ([AppLanguageManager.isChineseLanguageTag]): Mandarin in either
         * script, Cantonese, Min Nan, Hakka, Wu and Classical Chinese read
         * the Traditional copy; everyone else reads English.
         */
        fun of(languageTag: String): WhatsNewLanguage =
            if (AppLanguageManager.isChineseLanguageTag(languageTag)) ZhHant else En
    }
}

/** Copy on a What's New page, written in both [WhatsNewLanguage]s. */
data class WhatsNewText(val en: String, val zhHant: String) {
    fun resolve(language: WhatsNewLanguage): String = when (language) {
        WhatsNewLanguage.ZhHant -> zhHant
        WhatsNewLanguage.En -> en
    }
}

/** The language [WhatsNewSheet] resolved for the flow on screen. */
val LocalWhatsNewLanguage = staticCompositionLocalOf { WhatsNewLanguage.En }

@Composable
fun WhatsNewText.resolve(): String = resolve(LocalWhatsNewLanguage.current)

/**
 * The small looping animation an [WhatsNewVisual.Icon] plays. Dropped
 * entirely when the system "Remove animations" setting is on.
 */
enum class WhatsNewEffect { None, Bounce, Pulse, Wiggle, Breathe, Rotate }

/** The demo at the top of a feature page. */
sealed interface WhatsNewVisual {
    /** A large tinted icon with an optional looping [effect]. */
    data class Icon(
        val image: ImageVector,
        val effect: WhatsNewEffect = WhatsNewEffect.None,
    ) : WhatsNewVisual

    /**
     * A hand-built demo — typically a small mock of the real screen that
     * animates the new behaviour. [content] receives whether animations are
     * allowed, so it can show a still frame when they aren't.
     */
    class Custom(val content: @Composable (animate: Boolean) -> Unit) : WhatsNewVisual
}

/** What a [WhatsNewPage.Custom] page can do to the flow it sits in. */
class WhatsNewPageContext(
    /** Moves to the next page, or closes the sheet on the last one. */
    val advance: () -> Unit,
    /** Whether the system allows animations right now. */
    val animate: Boolean,
    /** The language the rest of the flow is shown in; pick copy with it. */
    val language: WhatsNewLanguage,
)

/**
 * One feature page shown before the summary. Pages are written in
 * [WhatsNewCatalog] under the versionCode that introduced them; a user who
 * skipped versions sees every skipped version's pages, oldest first.
 *
 * Every page has a stable [id] (kept across a config-change recreation so
 * the flow doesn't reshuffle) and an [isApplicable] check — return false to
 * leave the page out for this user, e.g. a "switch to the new layout?" page
 * for someone already on it. Replays from Settings check it too.
 */
sealed class WhatsNewPage {
    abstract val id: String
    abstract val isApplicable: () -> Boolean

    /** Demo + title + body. The primary button advances. */
    class Feature(
        override val id: String,
        val visual: WhatsNewVisual,
        val title: WhatsNewText,
        val body: WhatsNewText,
        override val isApplicable: () -> Boolean = { true },
    ) : WhatsNewPage()

    /**
     * Offers a change: [confirmLabel] runs [apply] and advances,
     * [declineLabel] just advances. Skipping the page or closing the sheet
     * leaves the setting as it was.
     */
    class OptIn(
        override val id: String,
        val visual: WhatsNewVisual,
        val title: WhatsNewText,
        val body: WhatsNewText,
        val confirmLabel: WhatsNewText,
        val declineLabel: WhatsNewText,
        val apply: () -> Unit,
        override val isApplicable: () -> Boolean = { true },
    ) : WhatsNewPage()

    /**
     * Like [OptIn], but [confirmLabel] asks the system for [permissions].
     * The flow advances once the system dialog closes, whatever the answer.
     * Below [minSdk] — where the permission doesn't exist or is granted at
     * install — or when everything is already granted, confirm just advances.
     * [onResult] receives whether every permission ended up granted.
     */
    class Permission(
        override val id: String,
        val visual: WhatsNewVisual,
        val title: WhatsNewText,
        val body: WhatsNewText,
        val confirmLabel: WhatsNewText,
        val declineLabel: WhatsNewText,
        val permissions: List<String>,
        val minSdk: Int = 1,
        val onResult: (granted: Boolean) -> Unit = {},
        override val isApplicable: () -> Boolean = { true },
    ) : WhatsNewPage()

    /**
     * Pick between looks: cards side by side, each with an optional mini
     * [Option.preview]. Selecting a card calls [select] straight away, so a
     * page skipped mid-way keeps whatever is on screen. [current] seeds the
     * selection from the real setting.
     */
    class Choice(
        override val id: String,
        val visual: WhatsNewVisual?,
        val title: WhatsNewText,
        val body: WhatsNewText,
        val options: List<Option>,
        val current: () -> String,
        val select: (String) -> Unit,
        override val isApplicable: () -> Boolean = { true },
    ) : WhatsNewPage() {
        class Option(
            val id: String,
            val label: WhatsNewText,
            val preview: (@Composable () -> Unit)? = null,
        )
    }

    /** A switch under the demo, applied as it's flipped. */
    class Toggle(
        override val id: String,
        val visual: WhatsNewVisual,
        val title: WhatsNewText,
        val body: WhatsNewText,
        val label: WhatsNewText,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
        override val isApplicable: () -> Boolean = { true },
    ) : WhatsNewPage()

    /**
     * Anything the templates above don't cover. [content] draws the whole
     * page body; the standard Next button sits under it unless
     * [showsNextButton] is false, in which case the page must call
     * [WhatsNewPageContext.advance] itself.
     */
    class Custom(
        override val id: String,
        val showsNextButton: Boolean = true,
        val content: @Composable (WhatsNewPageContext) -> Unit,
        override val isApplicable: () -> Boolean = { true },
    ) : WhatsNewPage()
}
