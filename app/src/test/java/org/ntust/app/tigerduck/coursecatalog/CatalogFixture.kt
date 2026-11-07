package org.ntust.app.tigerduck.coursecatalog

import org.json.JSONArray
import org.json.JSONObject
import org.ntust.app.tigerduck.clubs.ETaggedBody

/**
 * A term's catalog as `GET course-catalog/1151` sends it, in course-number order as the backend
 * sorts it. Courses differ in what one filter or one search looks at. As the iPhone's
 * `CourseSearchTests` catalog.
 */
object CatalogFixture {
    data class Row(
        val courseNo: String,
        val name: String,
        val teacher: String = "",
        val node: String = "M3",
        val dimension: String = "",
        val intensive: Boolean = false,
        val alliance: Boolean = false,
        val englishTaught: Boolean = false,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("Semester", "1151").put("CourseNo", courseNo).put("CourseName", name).put("CourseTeacher", teacher)
            .put("Dimension", dimension).put("CreditPoint", "3").put("RequireOption", "R").put("AllYear", "H")
            .put("ChooseStudent", 50).put("Restrict1", "0").put("Restrict2", "60").put("CourseTimes", "3")
            .put("PracticalTimes", "0").put("ClassRoomNo", "TR-313").put("Node", node).put("Contents", "")
            .put("IsIntensive", intensive).put("IsAlliance", alliance).put("IsEnglishTaught", englishTaught)
    }

    val zhRows = listOf(
        Row("3N1346703", "國文文法", "郭維茹", intensive = true, alliance = true),
        Row("CS1001301", "計算機概論", "王大明"),
        Row("CS1002301", "資料結構", "李小華"),
        Row("CS5101701", "深度學習", "陳明", englishTaught = true),
        Row("EE1001301", "微積分(上)", "黃意婷"),
        Row("GE1408301", "台灣文學", "林文", dimension = "A"),
        Row("MA160A001", "微積分", "周志隆", node = "R6"),
        Row("MA160A001", "微積分", "周志隆", node = "F2,F3"),
        Row("TCG051301", "藝術欣賞", "張美", dimension = "C"),
    )

    private val enNames = mapOf(
        "3N1346703" to ("Chinese Grammar" to "Wei-Ju Kuo"),
        "CS1001301" to ("Introduction to Computers" to "Da-Ming Wang"),
        "CS1002301" to ("Data Structures" to "Hsiao-Hua Li"),
        "CS5101701" to ("Deep Learning" to "Ming Chen"),
        "EE1001301" to ("Calculus (I)" to "Yi-Ting Huang"),
        "GE1408301" to ("Taiwanese Literature" to "Wen Lin"),
        "MA160A001" to ("Calculus" to "Chih-Lung Chou"),
        "TCG051301" to ("Art Appreciation" to "Mei Chang"),
    )

    fun rows(language: String): List<Row> =
        if (language != "en") zhRows
        else zhRows.map { row -> enNames[row.courseNo]?.let { (name, teacher) -> row.copy(name = name, teacher = teacher) } ?: row }

    fun body(language: String, rows: List<Row> = rows(language)): String {
        val english = language == "en"
        return JSONObject()
            .put("fetched_at", "2026-10-26T05:29:37Z").put("semester", "1151").put("language", language)
            .put("courses", JSONArray(rows.map { it.json() }))
            .put(
                "colleges",
                JSONArray().put(
                    JSONObject().put("no", "2").put("name", if (english) "College of EECS" else "電資學院").put(
                        "departments",
                        JSONArray()
                            .put(JSONObject().put("no", "CS").put("name", if (english) "Computer Science" else "資訊工程系"))
                            .put(JSONObject().put("no", "EE").put("name", if (english) "Electrical Engineering" else "電機工程系")),
                    ),
                ),
            )
            .put(
                "dimensions",
                JSONArray().put(JSONObject().put("no", "A").put("name", if (english) "Humanities and Thinking(A)" else "人文素養(A)")),
            )
            .toString()
    }

    fun catalog(language: String): CourseCatalog = CourseCatalog.decode(body(language))

    fun stored(language: String, etag: String, rows: List<Row> = rows(language)) = ETaggedBody(body(language, rows), etag)
}
