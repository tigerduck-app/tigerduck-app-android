package org.ntust.app.tigerduck.data.model

import java.util.Calendar
import java.util.Date

object MockData {
    val courses: List<Course> = listOf(
        Course.fromSchedule(
            courseNo = "EC1013701",
            courseName = "網際網路概論",
            instructor = "王大明",
            credits = 3,
            classroom = "RB-504",
            enrolledCount = 45,
            maxCount = 60,
            schedule = mapOf(1 to listOf("3", "4"), 3 to listOf("6", "7")),
            moodleIdNumber = "1142EC1013701"
        ),
        Course.fromSchedule(
            courseNo = "CS2023301",
            courseName = "計算機組織",
            instructor = "李小華",
            credits = 3,
            classroom = "TR-313",
            enrolledCount = 50,
            maxCount = 55,
            schedule = mapOf(2 to listOf("6", "7"), 4 to listOf("6", "7")),
            moodleIdNumber = "1142CS2023301"
        ),
        Course.fromSchedule(
            courseNo = "CS3034501",
            courseName = "人工智慧導論",
            instructor = "張教授",
            credits = 3,
            classroom = "RB-201",
            enrolledCount = 40,
            maxCount = 50,
            schedule = mapOf(1 to listOf("6", "7", "8")),
            moodleIdNumber = "1142CS3034501"
        ),
        Course.fromSchedule(
            courseNo = "MA1012001",
            courseName = "線性代數",
            instructor = "陳教授",
            credits = 3,
            classroom = "AU-101",
            enrolledCount = 55,
            maxCount = 60,
            schedule = mapOf(3 to listOf("1", "2"), 5 to listOf("1", "2")),
            moodleIdNumber = "1142MA1012001"
        ),
        Course.fromSchedule(
            courseNo = "EE2045601",
            courseName = "電子學實驗",
            instructor = "林教授",
            credits = 1,
            classroom = "EE-302",
            enrolledCount = 30,
            maxCount = 30,
            schedule = mapOf(5 to listOf("6", "7", "8")),
            moodleIdNumber = "1142EE2045601"
        ),
    )

    val assignments: List<Assignment> = listOf(
        Assignment(
            assignmentId = "324494",
            courseNo = "CS3034501",
            courseName = "人工智慧導論",
            title = "HW3 搜尋演算法",
            dueDate = daysFromNow(3)
        ),
        Assignment(
            assignmentId = "322841",
            courseNo = "CS2023301",
            courseName = "計算機組織",
            title = "Project01 MIPS Pipeline",
            dueDate = daysFromNow(4)
        ),
        Assignment(
            assignmentId = "325100",
            courseNo = "EC1013701",
            courseName = "網際網路概論",
            title = "Lab5 TCP Socket",
            dueDate = daysFromNow(7)
        ),
    )

    val announcements: List<Announcement> = listOf(
        Announcement(
            announcementId = "n1",
            title = "113-2學期獎學金申請公告",
            summary = "各類獎學金即日起至4月30日止受理申請，請同學把握時間。",
            department = "學務處",
            publishDate = daysFromNow(-1),
            detailUrl = "https://lc.ntust.edu.tw/p/406-1070-143898,r1828.php"
        ),
        Announcement(
            announcementId = "n2",
            title = "選課異動通知",
            summary = "第二階段選課結果已公布，請同學至校務系統確認。",
            department = "教務處",
            publishDate = daysFromNow(-2),
            detailUrl = "https://lc.ntust.edu.tw/p/406-1070-143800,r1828.php"
        ),
        Announcement(
            announcementId = "n3",
            title = "圖書館暑假開放時間調整",
            summary = "暑假期間圖書館開放時間調整為 09:00-17:00。",
            department = "圖書館",
            publishDate = daysFromNow(-3),
            detailUrl = "https://lc.ntust.edu.tw/p/406-1070-143700,r1828.php"
        ),
        Announcement(
            announcementId = "n4",
            title = "校園防疫措施更新",
            summary = "依最新防疫指引，進入室內空間建議配戴口罩。",
            department = "總務處",
            publishDate = daysFromNow(-5),
            detailUrl = "https://lc.ntust.edu.tw/p/406-1070-143600,r1828.php"
        ),
        Announcement(
            announcementId = "n5",
            title = "國際交流獎學金計畫",
            summary = "113-2學期國際交流獎學金即日起開放申請，名額有限。",
            department = "國際處",
            publishDate = daysFromNow(-6),
            detailUrl = "https://lc.ntust.edu.tw/p/406-1070-143500,r1828.php"
        ),
    )

    val calendarEvents: List<CalendarEvent> = listOf(
        CalendarEvent("e1", "網際網路概論", Date(), EventSource.MOODLE.raw),
        CalendarEvent("e2", "校務會議", Date(), EventSource.SCHOOL.raw),
        CalendarEvent("e3", "期中考", Date(), EventSource.EXAM.raw),
        CalendarEvent("e4", "HW3 截止", daysFromNow(3), EventSource.MOODLE.raw),
        CalendarEvent("e5", "畢業典禮", daysFromNow(10), EventSource.SCHOOL.raw),
    )

    private fun daysFromNow(days: Int): Date {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.time
    }
}
