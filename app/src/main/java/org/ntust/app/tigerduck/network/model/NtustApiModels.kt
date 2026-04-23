package org.ntust.app.tigerduck.network.model

import com.google.gson.annotations.SerializedName

data class CourseSearchResult(
    @SerializedName("Semester") val semester: String,
    @SerializedName("CourseNo") val courseNo: String,
    @SerializedName("CourseName") val courseName: String,
    @SerializedName("CourseTeacher") val courseTeacher: String,
    @SerializedName("Dimension") val dimension: String?,
    @SerializedName("CreditPoint") val creditPoint: String,
    @SerializedName("RequireOption") val requireOption: String?,
    @SerializedName("AllYear") val allYear: String?,
    @SerializedName("ChooseStudent") val chooseStudent: Int?,
    @SerializedName("Restrict1") val restrict1: String?,
    @SerializedName("Restrict2") val restrict2: String?,
    @SerializedName("CourseTimes") val courseTimes: String?,
    @SerializedName("PracticalTimes") val practicalTimes: String?,
    @SerializedName("ClassRoomNo") val classRoomNo: String?,
    @SerializedName("Node") val node: String?,
    @SerializedName("Contents") val contents: String?
) {
    // Restrict1 is NTUST's per-category quota (9999 = "no category cap");
    // Restrict2 is the real class size. They're usually equal, but some
    // courses (e.g. 微積分 BA1601301) report Restrict1=9999 / Restrict2=55
    // and we'd show "37/9999" if we read Restrict1 blindly.
    val maxEnrollment: Int
        get() {
            val v1 = restrict1?.toIntOrNull()
            val v2 = restrict2?.toIntOrNull()
            val real = listOfNotNull(v1, v2).filter { it > 0 && it != 9999 }
            return real.minOrNull() ?: v2 ?: v1 ?: 0
        }
}

data class CourseSearchRequest(
    @SerializedName("Semester") val semester: String,
    @SerializedName("CourseNo") val courseNo: String,
    @SerializedName("CourseName") val courseName: String,
    @SerializedName("CourseTeacher") val courseTeacher: String,
    @SerializedName("Dimension") val dimension: String,
    @SerializedName("CourseNotes") val courseNotes: String,
    @SerializedName("CampusNotes") val campusNotes: String,
    @SerializedName("ForeignLanguage") val foreignLanguage: Int,
    @SerializedName("OnlyGeneral") val onlyGeneral: Int,
    @SerializedName("OnlyNTUST") val onlyNtust: Int,
    @SerializedName("OnlyMaster") val onlyMaster: Int,
    @SerializedName("OnlyUnderGraduate") val onlyUnderGraduate: Int,
    @SerializedName("OnlyNode") val onlyNode: Int,
    @SerializedName("Language") val language: String
) {
    companion object {
        fun forCourseNo(courseNo: String, semester: String) = CourseSearchRequest(
            semester = semester,
            courseNo = courseNo,
            courseName = "",
            courseTeacher = "",
            dimension = "",
            courseNotes = "",
            campusNotes = "",
            foreignLanguage = 0,
            onlyGeneral = 0,
            onlyNtust = 0,
            onlyMaster = 0,
            onlyUnderGraduate = 0,
            onlyNode = 0,
            language = "zh"
        )

        fun forCourseName(courseName: String, semester: String) = CourseSearchRequest(
            semester = semester,
            courseNo = "",
            courseName = courseName,
            courseTeacher = "",
            dimension = "",
            courseNotes = "",
            campusNotes = "",
            foreignLanguage = 0,
            onlyGeneral = 0,
            onlyNtust = 0,
            onlyMaster = 0,
            onlyUnderGraduate = 0,
            onlyNode = 0,
            language = "zh"
        )
    }
}
