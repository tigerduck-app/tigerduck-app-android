package org.ntust.app.tigerduck.network

import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import android.util.Log
import org.ntust.app.tigerduck.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarService @Inject constructor() {

    private val calendarPageUrl = "https://r.xinshou.tw/ntust-calender"

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("TigerDuck-HTTP", message)
    }.apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                else HttpLoggingInterceptor.Level.NONE
    }

    private val browserClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
            )
        }
        .addInterceptor(loggingInterceptor)
        .build()

    suspend fun fetchCalendarUrls(): Map<Int, String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(calendarPageUrl).get().build()
            val (html, baseUrl) = browserClient.newCall(request).execute().use { response ->
                (response.body?.string() ?: "") to response.request.url.toString()
            }

            val linkRegex = Regex(
                "<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val yearRegex = Regex("""\b(1\d{2})\b""")
            val result = mutableMapOf<Int, String>()

            for (match in linkRegex.findAll(html)) {
                val href = match.groupValues[1]
                val rawText = match.groupValues[2]
                val text = rawText.replace(Regex("<[^>]+>"), "")

                if (!href.lowercase().endsWith(".ics")) continue

                val yearMatch = yearRegex.find(text) ?: continue
                val year = yearMatch.groupValues[1].toIntOrNull() ?: continue

                val absoluteUrl = if (href.startsWith("http")) {
                    href
                } else if (href.startsWith("/")) {
                    val parsed = java.net.URL(baseUrl)
                    "${parsed.protocol}://${parsed.host}$href"
                } else {
                    val base = baseUrl.substringBeforeLast("/") + "/"
                    base + href
                }
                result[year] = absoluteUrl
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private val currentRocYear: Int
        get() {
            val cal = Calendar.getInstance()
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
                response.body?.string() ?: return@withContext emptyList()
            }
            parseICS(icsString)
        } catch (e: Exception) {
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
        } catch (e: Exception) { null }
            ?: try {
            SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }.parse(value)
        } catch (e: Exception) { null }
            ?: try {
            SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }.parse(value)
        } catch (e: Exception) { null }
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
                        val cal = Calendar.getInstance()

                        if (end != null) {
                            // Check if multi-day
                            val startCal = Calendar.getInstance().apply { time = start }
                            val endCal = Calendar.getInstance().apply { time = Date(end.time - 1) }
                            val isMultiDay = startCal.get(Calendar.DAY_OF_YEAR) != endCal.get(Calendar.DAY_OF_YEAR) ||
                                            startCal.get(Calendar.YEAR) != endCal.get(Calendar.YEAR)

                            if (isMultiDay) {
                                var current: Date = start
                                val lastDay = Date(end.time - 1)
                                var daysAdded = 0
                                while (!current.after(lastDay) && daysAdded < 365) {
                                    val dayId = "$eventId-${current.time}"
                                    events.add(CalendarEvent(dayId, title, current, EventSource.SCHOOL.raw))
                                    val next = Calendar.getInstance().apply {
                                        time = current
                                        add(Calendar.DAY_OF_YEAR, 1)
                                    }
                                    current = next.time
                                    daysAdded++
                                }
                            } else {
                                events.add(CalendarEvent(eventId, title, start, EventSource.SCHOOL.raw))
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
}
