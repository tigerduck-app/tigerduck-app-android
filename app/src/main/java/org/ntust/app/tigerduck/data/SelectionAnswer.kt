package org.ntust.app.tigerduck.data

/**
 * A term's latest course selection answer: the course numbers it listed, and when it arrived
 * (epoch millis, this phone's clock), null for one kept before this phone kept the time.
 */
data class SelectionAnswer(val courseNos: Set<String>, val atMs: Long?) {
    /**
     * Whether this answer lists [courseNo] since a roster dropped it on the server, at
     * [droppedAtMs]: then this phone's roster enrolls it again, and the drop does not take it.
     * One older than the drop says nothing of it, or an answer from before another device's
     * roster would hold the drop off for good (the class reminders with it). An answer of no
     * known time, kept before this phone kept them, or a drop of none, is not newer.
     *
     * Newer by more than [CLOCK_MARGIN_MS]: the drop's time is the server's clock, the answer's
     * this phone's, and an answer shared between callers (`CourseService`'s minute) is dated
     * when it is kept rather than when it landed. As the iPhone's `droppedOnServer`, against
     * the row's `updated_at` with `CloudSyncCoordinator.clockSkewMargin` (tigerduck-app#914).
     */
    fun listsSince(courseNo: String, droppedAtMs: Long?): Boolean =
        atMs != null && droppedAtMs != null && courseNo in courseNos && atMs > droppedAtMs + CLOCK_MARGIN_MS

    companion object {
        /** See [listsSince]; the iPhone's two minutes. */
        const val CLOCK_MARGIN_MS = 2 * 60 * 1000L
    }
}
