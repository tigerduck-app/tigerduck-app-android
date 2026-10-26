package org.ntust.app.tigerduck.data.preferences

/**
 * What the Play Store rating request keeps across launches
 * (tigerduck-app-android#332). Implemented by [AppPreferences] over
 * SharedPreferences; an interface so the rule in
 * [org.ntust.app.tigerduck.review.ReviewPrompt] is unit-testable with an
 * in-memory fake.
 *
 * Plain primitives with "nothing yet" defaults: an install upgraded into
 * this starts counting from zero, with nothing to migrate.
 */
interface ReviewPromptStore {
    /** Calendar days on which the class table loaded, so far; 0 before the first. */
    var reviewPromptDaysUsed: Int

    /** The last day counted, as `yyyy-MM-dd` in the app's clock, or null before the first. */
    var reviewPromptLastDay: String?

    /** Whether the review flow has been launched. Set once and never cleared: the ask is a one-off. */
    var reviewPromptAsked: Boolean
}
