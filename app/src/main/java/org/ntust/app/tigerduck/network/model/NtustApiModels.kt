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
)

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
    @SerializedName("OnleyNTUST") val onlyNtust: Int,
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
