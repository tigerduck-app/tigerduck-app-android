package org.ntust.app.tigerduck.shared

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the upgrade-safe contract for [Course.classroomMapJson].
 *
 * Background — two production upgrade-crash incidents shared the same root cause:
 * Gson 2.x deserializes Kotlin data classes via `Unsafe.allocateInstance`, which
 * bypasses the primary constructor entirely.  Any Kotlin default value (e.g.
 * `classroomMapJson: String = "{}"`) is therefore silently dropped — the field
 * is `null` at runtime even though the source code declares a non-null default.
 *
 * Incident v1.3.x → v1.4.0: `Course` moved to `:shared`; R8 renamed its fields
 * so Gson could not match JSON keys → all fields null → NPE in
 * `WearScheduleBridge.toDto` from `TigerDuckApp.onCreate`.
 *
 * Incident v1.4.1 → v1.4.2 (caught pre-ship): `Course.classroomMapJson` was
 * added as `String = "{}"` (non-null).  v1.4.1 cache files lack the key → Gson
 * Unsafe path → field null → same NPE shape.  Fix: make it `String? = "{}"` and
 * null-coalesce at every call site.
 *
 * These tests pin the post-fix contract so a future refactor cannot silently
 * reintroduce the crash:
 *   - A JSON object lacking `classroomMapJson` must deserialize with null (not
 *     "{}") — proving the Kotlin default is NOT applied by Gson.
 *   - Consuming code (`classroomMap`, `classroom()`) must tolerate null gracefully.
 */
class CourseGsonCompatTest {

    private val gson = Gson()

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Minimal valid Course JSON with only the required fields present. */
    private fun minimalJson(extra: String = ""): String {
        val extraClause = if (extra.isBlank()) "" else ",$extra"
        return """{"courseNo":"T001","courseName":"Test Course"$extraClause}"""
    }

    // ──────────────────────────────────────────────────────────────────────────
    // (a) Missing classroomMapJson key — simulates v1.3.x / v1.4.1 cache files
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `classroomMapJson is null when key is absent in JSON`() {
        // The Kotlin default ("{}" ) must NOT be applied — Gson bypasses the
        // constructor via Unsafe, so if the field were non-null the runtime
        // value would be null and any direct dereference would NPE.
        val course = gson.fromJson(minimalJson(), Course::class.java)
        assertNull(
            "Expected null (Gson Unsafe path never runs Kotlin constructor defaults)",
            course.classroomMapJson
        )
    }

    @Test
    fun `classroomMap is emptyMap when classroomMapJson is absent`() {
        val course = gson.fromJson(minimalJson(), Course::class.java)
        assertEquals(emptyMap<String, String>(), course.classroomMap)
    }

    @Test
    fun `classroom(weekday) does not throw and falls back to flat classroom field`() {
        val json = minimalJson(""""classroom":"WT-101"""")
        val course = gson.fromJson(json, Course::class.java)
        // classroomMapJson absent → classroomMap empty → falls back to flat classroom.
        assertEquals("WT-101", course.classroom(1))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // (b) classroomMapJson present and valid
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `classroomMap parses correctly when classroomMapJson is populated`() {
        // The classroomMapJson value is itself a JSON string, so it must be
        // escaped inside the outer JSON object.
        val json = minimalJson(
            """"scheduleJson":"{\"1\":[\"3\"]}","classroomMapJson":"{\"1-3\":\"T3-101\"}""""
        )
        val course = gson.fromJson(json, Course::class.java)
        assertEquals(mapOf("1-3" to "T3-101"), course.classroomMap)
    }

    @Test
    fun `classroom(weekday, period) resolves from classroomMapJson`() {
        val json = minimalJson(
            """"scheduleJson":"{\"1\":[\"3\"]}","classroomMapJson":"{\"1-3\":\"T3-101\"}""""
        )
        val course = gson.fromJson(json, Course::class.java)
        assertEquals("T3-101", course.classroom(1, "3"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // (c) classroomMapJson present but malformed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `classroomMap is emptyMap when classroomMapJson is malformed`() {
        val json = minimalJson(""""classroomMapJson":"not-json"""")
        val course = gson.fromJson(json, Course::class.java)
        // Must not throw; malformed JSON falls back to emptyMap.
        assertEquals(emptyMap<String, String>(), course.classroomMap)
    }

    @Test
    fun `classroom(weekday) does not throw when classroomMapJson is malformed`() {
        val json = minimalJson(""""classroom":"WT-202","classroomMapJson":"not-json"""")
        val course = gson.fromJson(json, Course::class.java)
        // classroomMap empty → falls back to flat classroom.
        assertEquals("WT-202", course.classroom(1))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // (d) dedupRooms
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `dedupRooms collapses identical room names joined by comma`() {
        assertEquals("T3-101", Course.dedupRooms("T3-101, T3-101"))
    }

    @Test
    fun `dedupRooms dedupes across mixed separators`() {
        // "A,B、A" — comma and ideographic comma separators; A appears twice.
        val result = Course.dedupRooms("A,B、A")
        assertEquals("A, B", result)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // (e) Round-trip: fromSchedule → Gson toJson → fromJson
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `round-trip preserves schedule map`() {
        val scheduleIn = mapOf(1 to listOf("3", "4"), 3 to listOf("6", "7"))
        val original = Course.fromSchedule(
            courseNo = "RT001",
            courseName = "Round Trip",
            schedule = scheduleIn,
            classroomMap = mapOf("1-3" to "T3-101", "3-6" to "T4-101"),
        )
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, Course::class.java)
        assertEquals(scheduleIn, restored.schedule)
    }

    @Test
    fun `round-trip preserves classroomMap`() {
        val classroomMapIn = mapOf("1-3" to "T3-101", "3-6" to "T4-101")
        val original = Course.fromSchedule(
            courseNo = "RT002",
            courseName = "Round Trip 2",
            schedule = mapOf(1 to listOf("3"), 3 to listOf("6")),
            classroomMap = classroomMapIn,
        )
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, Course::class.java)
        assertEquals(classroomMapIn, restored.classroomMap)
    }
}
