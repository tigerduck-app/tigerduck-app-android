package org.ntust.app.tigerduck.shared

/**
 * Whether [a] and [b], student IDs or library user names, name the same
 * student: compared without case, as the school sign-in upper-cases the ID
 * while the library form keeps what was typed, and without the spaces around
 * them, which a user name stored before the form trimmed it can still carry.
 * Two nulls are the same; a null and an ID are not.
 */
fun sameStudentId(a: String?, b: String?): Boolean {
    if (a == null || b == null) return a == b
    return a.trim().equals(b.trim(), ignoreCase = true)
}
