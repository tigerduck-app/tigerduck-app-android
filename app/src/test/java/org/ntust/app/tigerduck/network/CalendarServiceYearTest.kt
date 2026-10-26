package org.ntust.app.tigerduck.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.EventSource
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/**
 * The academic calendar page names its ICS files the way the school writes them, the year
 * right against 學年度: "115學年度行事曆(ics檔案)". 學 is a word character to the regex engine, so
 * a `\b` after the year never matched and the page gave no years at all.
 *
 * A hand-made copy of the page's links stands in for the school. The years are this academic
 * year and the ones before it, as the page lists them, so the test keeps working as years pass.
 */
class CalendarServiceYearTest {

    /** The academic year as `CalendarService` counts it: ROC years, starting in August, in Taipei. */
    private val currentYear: Int
        get() {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
            val year = cal.get(Calendar.YEAR) - 1911
            return if (cal.get(Calendar.MONTH) + 1 >= 8) year else year - 1
        }

    private val served = CopyOnWriteArrayList<String>()

    private val page: String
        get() {
            val y = currentYear
            return """
                <ul>
                <li><a href="/var/file/48/1048/img/$y.xlsx" title="${y}學年度行事曆">${y}學年度行事曆</a></li>
                <li><a href="/var/file/48/1048/img/${y - 1}.xlsx">${y - 1}學年度行事曆</a></li>
                <li><a href="/var/file/48/1048/img/$y.ics"><span style="color:#3300ff;">${y}學年度行事曆(ics檔案)</span></a></li>
                <li><a href="/var/file/48/1048/img/${y - 1}.ics"><span style="color:#ff0000;">${y - 1}學年度行事曆(ics檔案)</span></a></li>
                <li><a href="/var/file/48/1048/img/NTUST${y - 2}.ics"><span style="color:#000000;">${y - 2}學年度行事曆(ics檔案)</span></a></li>
                <li><a href="/var/file/48/1048/img/${y - 3}_old.ics">${y - 3}學年度行事曆 (ics檔案)</a></li>
                <li><a href="/var/file/48/1048/img/draft.ics">${y}0901修訂草稿(ics檔案)</a></li>
                </ul>
            """.trimIndent()
        }

    private fun ics(year: Int) = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:fake-$year\r\n" +
        "SUMMARY:${year}學年度測試日\r\nDTSTART;VALUE=DATE:20261010\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    /** Answers every request itself: the page, or the ICS a link names. */
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        served += url.encodedPath
        val body = Regex("""/(\d{3})\.ics$""").find(url.encodedPath)?.let { ics(it.groupValues[1].toInt()) } ?: page
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }.build()

    /**
     * Android's regex engine follows Unicode, so 學 is a word character there; the JVM's `\b`
     * has been ASCII-only since JDK 19 and finds a boundary that Android doesn't. This reads the
     * page's own link texts with Unicode word characters, as Android does.
     */
    @Test
    fun `the year is found in the page's real link texts with Unicode word characters, as on Android`() {
        val year = Pattern.compile(CALENDAR_LINK_YEAR, Pattern.UNICODE_CHARACTER_CLASS)
        val texts = mapOf(
            "115學年度行事曆(ics檔案)" to "115",
            "114學年度行事曆(ics檔案)" to "114",
            "113學年度行事曆(ics檔案)" to "113",
            "110學年度行事曆 (ics檔案)" to "110",
            "115 學年度行事曆" to "115",
        )
        texts.forEach { (text, expected) ->
            val matcher = year.matcher(text)
            assertTrue(text, matcher.find())
            assertEquals(text, expected, matcher.group(1))
        }
        // Part of a longer number is no year.
        assertTrue(!year.matcher("1150901修訂草稿").find())
    }

    @Test
    fun `years are read from link texts written right against 學年度`() = runBlocking {
        val y = currentYear

        val urls = CalendarService(client).fetchCalendarUrls()

        assertEquals(setOf(y, y - 1, y - 2, y - 3), urls.keys)
        assertTrue(urls.toString(), urls.getValue(y).endsWith("/var/file/48/1048/img/$y.ics"))
        assertTrue(urls.toString(), urls.getValue(y - 2).endsWith("/var/file/48/1048/img/NTUST${y - 2}.ics"))
        // A longer number that starts like a year names no year; the draft link isn't this year's.
        assertTrue(urls.toString(), urls.values.none { it.endsWith("/draft.ics") })
    }

    @Test
    fun `this academic year's ICS is the one loaded, and its events are school events`() = runBlocking {
        val y = currentYear

        val events = CalendarService(client).fetchAndParseICS()

        assertEquals(listOf("${y}學年度測試日"), events.map { it.title })
        assertTrue(events.all { it.sourceRaw == EventSource.SCHOOL.raw })
        assertTrue(served.toString(), "/var/file/48/1048/img/$y.ics" in served)
        assertTrue(served.toString(), served.none { it.endsWith("/${y - 1}.ics") })
    }
}
