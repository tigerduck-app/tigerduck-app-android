package org.ntust.app.tigerduck.ui.screen.settings

/**
 * The re-enable conflict answers being applied in this process, which every
 * Settings screen's view model shares. A check compares this device with the
 * server, so one that runs while an answer is applied, or that an answer
 * overtakes, would read a half-sent state; and an answer that finished under
 * a waiting check would end the hold on assignment marks that check needs.
 */
internal class ReenableResolutions {

    private var running = 0
    private var started = 0
    private var waitingCheck: (() -> Unit)? = null

    @Synchronized
    fun start() {
        running++
        started++
    }

    /**
     * Null when [check] has to wait for the answers being applied: the last
     * check to wait runs when they have all finished, from the screen that
     * asked. Otherwise the token [isCurrent] takes when the check is done.
     * One that does not [waits] is dropped instead, and leaves the check
     * already waiting, whose screen shows the dialog, in place.
     */
    @Synchronized
    fun checkStarting(waits: Boolean = true, check: () -> Unit): Int? {
        if (running == 0) return started
        if (waits) waitingCheck = check
        return null
    }

    /** Whether no answer has started since the check holding [token] began. */
    @Synchronized
    fun isCurrent(token: Int): Boolean = started == token

    /**
     * Called as each answer finishes. True when it was the last one and no
     * check waited, so the hold can go; a check that waited runs instead and
     * settles the hold itself.
     */
    fun finish(): Boolean {
        val check = synchronized(this) {
            running--
            if (running > 0) return false
            waitingCheck.also { waitingCheck = null }
        } ?: return true
        check()
        return false
    }
}
