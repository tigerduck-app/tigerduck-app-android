package org.ntust.app.tigerduck.shared

object Periods {
    /**
     * Rows the timetable shows before any course asks for more. Ends at 10
     * (17:30-18:20), which is an ordinary teaching slot — leaving it out meant
     * a 5th-period-free student saw their day stop at 17:20 and had to take on
     * faith that nothing followed. 5 stays out because it is the lunch break:
     * a course scheduled there widens the grid on its own, in
     * `ClassTableCellLayout.activePeriods`.
     */
    val defaultVisible = listOf("1", "2", "3", "4", "6", "7", "8", "9", "10")
    val extended = listOf("5", "A", "B", "C", "D")

    /**
     * The evening periods the Display toggle pins on. D is left out
     * deliberately: it ends at 22:00 and is rare enough that pinning it
     * would cost a row almost nobody needs.
     */
    val eveningOptional = listOf("A", "B", "C")
    val chronologicalOrder =
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "A", "B", "C", "D")
}

object PeriodTimes {
    val mapping: Map<String, Pair<String, String>> = mapOf(
        "1" to ("08:10" to "09:00"),
        "2" to ("09:10" to "10:00"),
        "3" to ("10:20" to "11:10"),
        "4" to ("11:20" to "12:10"),
        "5" to ("12:20" to "13:10"),
        "6" to ("13:20" to "14:10"),
        "7" to ("14:20" to "15:10"),
        "8" to ("15:30" to "16:20"),
        "9" to ("16:30" to "17:20"),
        "10" to ("17:30" to "18:20"),
        "A" to ("18:30" to "19:20"),
        "B" to ("19:25" to "20:10"),
        "C" to ("20:15" to "21:05"),
        "D" to ("21:10" to "22:00"),
    )
}
