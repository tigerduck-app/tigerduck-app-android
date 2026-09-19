// The room preview the class table prints in the corner of a course cell.
//
// A cell is ~50dp wide and already spends its space on the course name, so
// only a short room *code* fits there: the building-and-number forms NTUST's
// portal reports ("TR-313", "IB-409-1"), the spaced form the classroom-display
// toggle produces, and the Chinese building-and-number form the co-listed
// NTU / NTNU rooms use ("共101", "人文B106"). Everything else the portal calls a
// classroom is prose — "Heping Cheng 101", "Gongguan Track and Field Ground",
// "綜合大講堂", "系上自行安排" — and would truncate past use, so no hint is drawn
// for those. The detail dialog still shows the full room.
//
// Mirrors iOS `CourseRoomHint`.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.shared.Course

internal object CourseRoomHint {

    /**
     * `AA-999`, `AA-999-9`, `AA 9 999`, or a Chinese building name followed
     * by a room number (`共101`, `博雅205`, `人文B106`, `農化二B10-1`).
     *
     * The Latin branches spell the class out rather than using `\w`, which in
     * a Unicode-aware engine also matches Han — "體育-游泳池" would then read
     * as `AA-999`. The Chinese branch insists on trailing digits, which keeps
     * placeholders and facility names ("系上自行安排", "林一") out.
     */
    private val shortRoomCode = Regex(
        "^([A-Za-z0-9]{2}-[A-Za-z0-9]{3}(-[A-Za-z0-9])?" +
            "|[A-Za-z0-9]{2} [A-Za-z0-9] [A-Za-z0-9]{3}" +
            "|\\p{IsHan}{1,3}[A-Za-z]?[0-9]{2,4}(-[0-9])?)$"
    )

    fun isShortCode(room: String): Boolean = shortRoomCode.matches(room)

    /**
     * The hint for the cell starting at ([weekday], [periodId]), or null when
     * that slot has no room or its room is not a short code.
     *
     * The whole-course room is only a fallback for a course with no per-slot
     * map at all. Once a course has one, a slot missing from it is a slot
     * whose room the portal did not give, and falling back would label the
     * block with the *other* block's room — worse than saying nothing.
     *
     * Not for a 衝堂 cluster: the cell is split between courses there and has
     * no free corner to print in.
     */
    fun room(course: Course, weekday: Int, periodId: String): String? {
        val map = course.classroomMap
        val room = if (map.isEmpty()) {
            course.classroom(weekday)
        } else {
            map["$weekday-$periodId"]?.trim().orEmpty()
        }
        return room.takeIf(::isShortCode)
    }
}
