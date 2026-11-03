package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * Saves an in-place edit of [semester]'s list on the class table, [after] as the edit made it of
 * [before], the list on screen at the tap: under the courses' lock, onto the term as it stands
 * ([ClassTableCourseMerge.editOnto]). The edit was computed from the screen's copy before its
 * save waited, behind a pull's step or a fetch's commit, so a save of that whole copy would write
 * back what they wrote meanwhile (race 5). Every edit's save of the list goes through here.
 */
internal suspend fun DataCache.saveClassTableEdit(semester: String, signIn: SignIn, before: List<Course>, after: List<Course>) =
    updateCourses(semester, signIn) { stored, _ -> ClassTableCourseMerge.editOnto(stored, before, after) }
