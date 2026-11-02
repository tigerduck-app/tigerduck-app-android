// The backend's requests for a term's roster (tigerduck-app-android#595).
//
// The backend has no school password, so it cannot read course selection itself. It notes when
// each account's roster of a term last arrived, and from 3.10.1 `/v3/sync/full` names the
// current term in `roster_requests` once none arrived in a week. The app answers with course
// selection's list, asked afresh, as the term's roster.

package org.ntust.app.tigerduck.push

import org.json.JSONObject
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInBound

/** The terms `/v3/sync/full`'s `roster_requests` names, once each; none from a backend before 3.10.1. */
internal fun parseRosterRequests(json: JSONObject): List<String> {
    val terms = json.optJSONArray("roster_requests") ?: return emptyList()
    return (0 until terms.length()).mapNotNull { i -> terms.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" } }
        .distinct()
}

/**
 * When each term's roster request last went to course selection. A request stays until a roster
 * arrives, and full syncs come every few seconds while Home is open, so while course selection
 * does not answer a term is asked again only an hour on, or at once for another account.
 * In memory: a process that starts afresh asks once more, which costs one fetch.
 *
 * As iOS `RosterRequestLog` (tigerduck-app#914).
 */
internal class RosterRequestLog {
    // When each term was asked, for this sign-in only: the next account's requests owe it nothing.
    private val asked = SignInBound<Map<String, Long>>(emptyMap())

    /**
     * The terms of [requests] to ask course selection for now, each once, recorded as asked at
     * [nowMs] under [signIn], which a sign-out ends.
     */
    @Synchronized
    fun termsToAsk(requests: List<String>, signIn: SignIn, nowMs: Long): List<String> {
        val before = asked.value
        val due = requests.filter { it.isNotBlank() }.distinct().sorted().filter { term ->
            val askedAt = before[term] ?: return@filter true
            nowMs - askedAt >= RETRY_INTERVAL_MS || nowMs < askedAt
        }
        asked.set(before + due.associateWith { nowMs }, signIn)
        return due
    }

    companion object {
        const val RETRY_INTERVAL_MS = 60 * 60 * 1000L
    }
}
