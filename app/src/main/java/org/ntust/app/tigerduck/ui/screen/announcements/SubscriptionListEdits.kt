package org.ntust.app.tigerduck.ui.screen.announcements

import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.network.model.TaxonomyResponse

// The subscription page's edits of its list of rules, as lists: what each edit leaves.

/** The list without the rule at [at]. */
internal fun List<SubscriptionRule>.removing(at: Int): List<SubscriptionRule> = filterIndexed { index, _ -> index != at }

/** The list with the rule at [at] switched on or off. */
internal fun List<SubscriptionRule>.toggling(at: Int): List<SubscriptionRule> =
    mapIndexed { index, rule -> if (index == at) rule.copy(enabled = !rule.enabled) else rule }

/**
 * [rule] in place of the rule it names ([isSameRuleAs]), wherever that stands now: the rule
 * editor's Done, whose rule a refusal may have moved, or taken away since the editor opened. One
 * gone goes back as the student left it, at [at] or at the end: the student's latest intent.
 */
internal fun List<SubscriptionRule>.replacingRule(rule: SubscriptionRule, at: Int?): List<SubscriptionRule> {
    val now = indexOfFirst { it.isSameRuleAs(rule) }
    if (now >= 0) return toMutableList().also { it[now] = rule }
    return toMutableList().also { it.add((at ?: size).coerceIn(0, size), rule) }
}

/** Whether this and [other] are one rule: by `client_id` when either has one (`/v4`), else by the backend's id. */
internal fun SubscriptionRule.isSameRuleAs(other: SubscriptionRule): Boolean =
    if (clientId != null || other.clientId != null) clientId == other.clientId else id != null && id == other.id

/**
 * The rule that follows the taxonomy's default tags, which the page offers to a student with none;
 * null while the taxonomy names none. Matches iOS `seedDefault(from:)`.
 */
internal fun TaxonomyResponse.defaultRule(): SubscriptionRule? {
    if (defaultTags.isEmpty()) return null
    return SubscriptionRule(name = null, orgs = emptyList(), tags = defaultTags, mode = "OR", enabled = true)
}
