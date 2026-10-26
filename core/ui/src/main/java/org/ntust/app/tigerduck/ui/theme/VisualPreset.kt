package org.ntust.app.tigerduck.ui.theme

/**
 * The interface style picked in Settings > Display.
 *
 * A presentation concern only: it picks how the UI draws, not which features
 * exist or which data flows, and it is separate from the accent colour.
 * Views read it through [VisualStylePolicy] rather than branching on the enum.
 *
 * [rawValue] is what is stored, and the same string the iPhone app stores for
 * the same style. The iPhone's third style, "Apple", follows its own system
 * look and has no counterpart here.
 */
enum class VisualPreset(val rawValue: String) {
    /** TigerDuck's own look: one block per course run on the class table. */
    TigerDuck("default"),

    /**
     * TigerDuck's look with the class table as a classic grid: one pastel tile
     * per period, the period without its times, alternating row bands.
     */
    Tat("tat");

    /** The name in the picker. Not localized: it reads the same in every language. */
    val displayName: String
        get() = when (this) {
            TigerDuck -> "TigerDuck"
            Tat -> "TAT"
        }

    companion object {
        val Default = TigerDuck

        /**
         * The style stored as [raw]. Nothing stored, or a style this build does
         * not know, as a later build can leave behind before a downgrade, reads
         * as [Default] instead of failing.
         */
        fun fromRawValue(raw: String?): VisualPreset =
            entries.firstOrNull { it.rawValue == raw } ?: Default
    }
}
