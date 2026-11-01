package org.ntust.app.tigerduck.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.util.logSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A three-digit ROC academic year in a link's text on the calendar page, as the school writes
 * it: "115學年度行事曆(ics檔案)". Digits on either side rule a match out, not word boundaries:
 * Android's regex follows Unicode, where 學 is a word character, so `\b` between the year and
 * 學年度 never matched and the page gave no years at all.
 */
internal const val CALENDAR_LINK_YEAR = """(?<!\d)(1\d{2})(?!\d)"""

@Singleton
class CalendarService internal constructor(
    baseClient: OkHttpClient,
    /** The page listing the academic calendar's ICS files, or a redirect to it. */
    private val calendarPageUrl: String,
) {

    @Inject constructor(baseClient: OkHttpClient) : this(baseClient, CALENDAR_PAGE_URL)

    private val browserClient = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
            )
        }
        .build()

    suspend fun fetchCalendarUrls(): Map<Int, String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(calendarPageUrl).get().build()
            // Links on the page resolve against where the redirect led, not the address asked for.
            val (html, pageUrl) = browserClient.newCall(request).execute().use { response ->
                response.body.string() to response.request.url
            }

            val linkRegex = Regex(
                "<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val yearRegex = Regex(CALENDAR_LINK_YEAR)
            val result = mutableMapOf<Int, String>()

            for (match in linkRegex.findAll(html)) {
                val href = match.groupValues[1]
                val rawText = match.groupValues[2]
                val text = rawText.replace(Regex("<[^>]+>"), "")

                if (!href.lowercase().endsWith(".ics")) continue

                val yearMatch = yearRegex.find(text) ?: continue
                val year = yearMatch.groupValues[1].toIntOrNull() ?: continue

                val absoluteUrl = pageUrl.resolve(href) ?: continue
                result[year] = absoluteUrl.toString()
            }
            result
        } catch (e: Exception) {
            // Degrading to "no calendar" is the right UX, but log it —
            // otherwise a format drift on the calendar page is invisible.
            android.util.Log.w("CalendarService", "Failed to fetch calendar URLs: ${e.logSummary()}")
            emptyMap()
        }
    }

    private val currentRocYear: Int
        get() {
            val cal = Calendar.getInstance(TaipeiClock.timeZone)
            val year = cal.get(Calendar.YEAR) - 1911
            val month = cal.get(Calendar.MONTH) + 1
            return if (month >= 8) year else year - 1
        }

    suspend fun fetchAndParseICS(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        try {
            val urls = fetchCalendarUrls()
            val icsUrl = urls[currentRocYear] ?: return@withContext emptyList()

            val request = Request.Builder().url(icsUrl).get().build()
            val icsString = browserClient.newCall(request).execute().use { response ->
                response.body.string()
            }
            parseICS(icsString)
        } catch (e: Exception) {
            android.util.Log.w("CalendarService", "Failed to fetch/parse ICS: ${e.logSummary()}")
            emptyList()
        }
    }

    private fun parseICSDate(line: String): Date? {
        val colonIdx = line.lastIndexOf(':')
        if (colonIdx < 0) return null
        val value = line.substring(colonIdx + 1)
        // Create new instances each call — SimpleDateFormat is not thread-safe
        return try {
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)
        } catch (e: Exception) {
            null
        }
            ?: try {
                SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                    timeZone = TaipeiClock.timeZone
                }.parse(value)
            } catch (e: Exception) {
                null
            }
            ?: try {
                SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                    timeZone = TaipeiClock.timeZone
                }.parse(value)
            } catch (e: Exception) {
                null
            }
    }

    private fun parseICS(ics: String): List<CalendarEvent> {
        val unfolded = ics
            .replace("\r\n ", "")
            .replace("\r\n\t", "")
            .replace("\n ", "")
            .replace("\n\t", "")

        val events = mutableListOf<CalendarEvent>()
        var inEvent = false
        var summary: String? = null
        var dtStart: Date? = null
        var dtEnd: Date? = null
        var uid: String? = null

        for (line in unfolded.lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "BEGIN:VEVENT" -> {
                    inEvent = true
                    summary = null; dtStart = null; dtEnd = null; uid = null
                }

                trimmed == "END:VEVENT" -> {
                    val title = summary
                    val start = dtStart
                    if (title != null && start != null) {
                        val eventId = uid ?: "school-$title-${start.time}"
                        val end = dtEnd
                        val taipeiTz = TaipeiClock.timeZone

                        if (end != null) {
                            // Check if multi-day
                            val startCal = Calendar.getInstance(taipeiTz).apply { time = start }
                            val endCal =
                                Calendar.getInstance(taipeiTz).apply { time = Date(end.time - 1) }
                            val isMultiDay =
                                startCal.get(Calendar.DAY_OF_YEAR) != endCal.get(Calendar.DAY_OF_YEAR) ||
                                        startCal.get(Calendar.YEAR) != endCal.get(Calendar.YEAR)

                            if (isMultiDay) {
                                var current: Date = start
                                val lastDay = Date(end.time - 1)
                                var daysAdded = 0
                                while (!current.after(lastDay) && daysAdded < 365) {
                                    val dayId = "$eventId-${current.time}"
                                    events.add(
                                        CalendarEvent(
                                            dayId,
                                            title,
                                            current,
                                            EventSource.SCHOOL.raw
                                        )
                                    )
                                    val next = Calendar.getInstance(taipeiTz).apply {
                                        time = current
                                        add(Calendar.DAY_OF_YEAR, 1)
                                    }
                                    current = next.time
                                    daysAdded++
                                }
                            } else {
                                events.add(
                                    CalendarEvent(
                                        eventId,
                                        title,
                                        start,
                                        EventSource.SCHOOL.raw
                                    )
                                )
                            }
                        } else {
                            events.add(CalendarEvent(eventId, title, start, EventSource.SCHOOL.raw))
                        }
                    }
                    inEvent = false
                }

                inEvent -> {
                    when {
                        trimmed.startsWith("SUMMARY") -> {
                            val colonIdx = trimmed.indexOf(':')
                            if (colonIdx >= 0) summary = trimmed.substring(colonIdx + 1)
                        }

                        trimmed.startsWith("DTSTART") -> dtStart = parseICSDate(trimmed)
                        trimmed.startsWith("DTEND") -> dtEnd = parseICSDate(trimmed)
                        trimmed.startsWith("UID:") -> uid = trimmed.removePrefix("UID:")
                    }
                }
            }
        }
        return events
    }

    companion object {
        /**
         * TigerDuck's own 307 to the school's academic calendar page. The backend keeps the
         * target, so the page can move without a release.
         */
        const val CALENDAR_PAGE_URL = "https://api.tigerduck.app/links/ntust-calendar"
    }
}
