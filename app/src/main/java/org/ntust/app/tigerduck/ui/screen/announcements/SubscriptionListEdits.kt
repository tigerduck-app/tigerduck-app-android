package org.ntust.app.tigerduck.ui.screen.announcements

import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.network.model.TaxonomyResponse

// The subscription page's edits of its list of rules, as lists: what each edit leaves.

/** [rule] in place of the rule at [at], or at the end when [at] is no place in the list. */
internal fun List<SubscriptionRule>.upserting(rule: SubscriptionRule, at: Int?): List<SubscriptionRule> =
    if (at != null && at in indices) toMutableList().also { it[at] = rule } else this + rule

/** The list without the rule at [at]. */
internal fun List<SubscriptionRule>.removing(at: Int): List<SubscriptionRule> = filterIndexed { index, _ -> index != at }

/** The list with the rule at [at] switched on or off. */
internal fun List<SubscriptionRule>.toggling(at: Int): List<SubscriptionRule> =
    mapIndexed { index, rule -> if (index == at) rule.copy(enabled = !rule.enabled) else rule }

/** [rule] in place of the rule with the backend's [id]; the list as it was when none has it. */
internal fun List<SubscriptionRule>.replacingId(id: Int, rule: SubscriptionRule): List<SubscriptionRule> {
    val at = indexOfFirst { it.id == id }
    if (at < 0) return this
    return toMutableList().also { it[at] = rule }
}

/**
 * The rule that follows the taxonomy's default tags, which the page offers to a student with none;
 * null while the taxonomy names none. Matches iOS `seedDefault(from:)`.
 */
internal fun TaxonomyResponse.defaultRule(): SubscriptionRule? {
    if (defaultTags.isEmpty()) return null
    return SubscriptionRule(name = null, orgs = emptyList(), tags = defaultTags, mode = "OR", enabled = true)
}
