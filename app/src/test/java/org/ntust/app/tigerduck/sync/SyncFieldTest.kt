package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.PendingCourseAdds

/** Each synced field's name, laid out as the snapshot lays the field out, one name per field. */
class SyncFieldTest {

    private val key = SyncField.courseKey("1141", "CS1001")

    @Test
    fun `a course's key is the one the backend and both apps build`() {
        assertEquals("client:1141:CS1001", key)
        assertEquals(PendingCourseAdds.forceKey("1141", "CS1001"), key)
    }

    @Test
    fun `each field is named by the snapshot's list, its row's key and the field`() {
        assertEquals("assignment_overrides/4021/local_status", SyncField.mark("4021"))
        assertEquals("course_overrides/client:1141:CS1001/color_hex", SyncField.color(key))
        assertEquals("course_overrides/client:1141:CS1001/custom_names/zh-Hant", SyncField.name(key, "zh-Hant"))
        assertEquals("course_overrides/client:1141:CS1001/ignore_assignments", SyncField.ignore(key))
        assertEquals("holiday_overrides/12/notify", SyncField.holiday(12))
        assertEquals("courses/client:1141:CS1001", SyncField.membership(key))
    }

    @Test
    fun `no two fields share a name`() {
        val other = SyncField.courseKey("1141", "CS1002")
        val names = listOf(
            SyncField.mark("12"), SyncField.mark("13"),
            SyncField.color(key), SyncField.color(other),
            SyncField.name(key, "en"), SyncField.name(key, "zh-Hant"), SyncField.name(other, "en"),
            SyncField.ignore(key), SyncField.ignore(other),
            SyncField.holiday(12), SyncField.holiday(13),
            SyncField.membership(key), SyncField.membership(other),
            SyncField.courseKey("114", "1CS1001"),
        )

        assertEquals(names, names.distinct())
    }
}
