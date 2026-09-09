package org.ntust.app.tigerduck.data.model

/**
 * Outcome of a manual "Check for updates" tap from Settings → About.
 * `Offered` is intentionally absent: when Play reports an available update
 * the regular [PendingUpdate] flow surfaces the three-button dialog and the
 * result alert stays quiet — stacking both would double-prompt the user.
 *
 * Lives in `main/` so the fdroid stub (which never publishes anything but
 * null here) can reference the same type as the play-flavor checker.
 */
enum class ManualCheckResult { UpToDate, Failed }
