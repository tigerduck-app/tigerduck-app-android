package org.ntust.app.tigerduck.emptyclassroom

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What a room is doing in one period. */
sealed interface RoomSlotUse {
    data object Free : RoomSlotUse

    /** Lent through the booking system. The booker is a person, so the name is never kept. */
    data object Booked : RoomSlotUse

    data class Course(val name: String, val teacher: String) : RoomSlotUse
}

/** One room's day: the use of each period by index, 0 being period 1 and 13 period D. */
data class RoomUsage(val room: String, val slots: List<RoomSlotUse>) {
    fun isFree(period: Int): Boolean = slots.getOrNull(period) == RoomSlotUse.Free

    /** The last period of the free run that starts at [period], or null when the room is in use then. */
    fun freeUntil(period: Int): Int? {
        if (!isFree(period)) return null
        var last = period
        while (isFree(last + 1)) last++
        return last
    }
}

/** The main campus's rooms on one date, with the building names the site gives each code. */
data class CampusUsage(val rooms: List<RoomUsage>, val buildingNames: Map<String, String>)

/**
 * Reads cour01's room-use page, an ASP.NET WebForms page: the form state each postback sends
 * back, the date calendar's links, the room grid and its pager.
 *
 * Parsed with HTML5 rules, which matters twice (tigerduck-app#456): the site writes a stray
 * `</br>` end tag between a course and its teacher, which an HTML5 parser reads as `<br>` and a
 * lenient one drops, running the two names together; and a booking is told by its 已借用 / 已預約
 * text, never by the cell colour, which the site puts on the wrong cells of a second page.
 */
object ClassroomUsageParser {
    const val PERIOD_COUNT = 14
    const val CAMPUS_FIELD = "DropdownCampusListID"
    const val CALENDAR_TARGET = "date_cal"
    private const val BUILDING_FIELD = "DropDownBuildingListID"
    private const val GRID_ID = "showinfo_grd"

    /**
     * Parts of the words for a booking: confirmed, pending, applied for, under review, approved or
     * held. A course whose name holds one shows as booked, which still keeps the room taken.
     */
    private val bookingMarks = listOf("借用", "預約", "申請", "審核", "核准", "保留")

    private val postbackPattern = Regex("""__doPostBack\('([^']*)','([^']*)'\)""")
    private val calendarEpoch: LocalDate = LocalDate.of(2000, 1, 1)

    data class Postback(val target: String, val argument: String)

    data class Grid(
        val rooms: List<RoomUsage>,
        /** The page shown, which the pager writes without a link. */
        val page: Int,
        val pageLinks: Map<Int, Postback>,
    )

    data class FormField(val name: String, val value: String)

    data class Page(
        val fields: List<FormField>,
        /** Day numbers the calendar links, and the day numbers of the months it steps to. */
        val calendarDays: Set<Int>,
        val monthSteps: List<Int>,
        /** Null when the page has no grid: a sign-in page, the bridge to one, or an error page. */
        val grid: Grid?,
        val buildingNames: Map<String, String>,
    ) {
        /** The campus the page's list has chosen. */
        val campus: String? get() = fields.firstOrNull { it.name == CAMPUS_FIELD }?.value

        /** The fields only, for a log line: the form state runs to tens of kilobytes. */
        override fun toString(): String =
            "Page(fields=${fields.size}, days=${calendarDays.size}, grid=${grid?.rooms?.size})"
    }

    fun isQueryPage(html: String): Boolean = html.contains(CAMPUS_FIELD)

    fun page(html: String): Page {
        val doc = Jsoup.parse(html)
        val (days, months) = calendarLinks(doc)
        return Page(
            fields = formFields(doc),
            calendarDays = days,
            monthSteps = months,
            grid = grid(doc),
            buildingNames = buildingNames(doc),
        )
    }

    /** The calendar's argument for a date: days since 2000-01-01, by the calendar date alone. */
    fun dayNumber(date: LocalDate): Int = ChronoUnit.DAYS.between(calendarEpoch, date).toInt()

    /**
     * The postback that brings [day] closer: the day itself once the calendar links it, else the
     * month step toward it. The site answers a day it does not link with an error page.
     */
    fun calendarStep(day: Int, days: Set<Int>, monthSteps: List<Int>): Postback? {
        if (day in days) return Postback(CALENDAR_TARGET, day.toString())
        val first = days.minOrNull()
        val last = days.maxOrNull()
        val step = when {
            first == null || last == null -> monthSteps.minByOrNull { kotlin.math.abs(it - day) }
            day > last -> monthSteps.maxOrNull()
            day < first -> monthSteps.minOrNull()
            else -> null
        }
        return step?.let { Postback(CALENDAR_TARGET, "V$it") }
    }

    // --- Grid ---

    private fun grid(doc: Document): Grid? {
        val table = doc.getElementById(GRID_ID) ?: return null
        val rows = directRows(table)
        val rooms = mutableListOf<RoomUsage>()
        // The first row heads the columns. A row of one cell is the pager or a no-data line; any
        // other count means the grid changed shape, which must not read as rooms all free or none.
        for (row in rows.drop(1)) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size <= 1) continue
            if (cells.size != PERIOD_COUNT + 1) return null
            val name = cells[0].text().trim()
            if (name.isEmpty()) continue
            rooms += RoomUsage(name, cells.drop(1).map(::slotUse))
        }
        val (page, links) = pager(rows.lastOrNull())
        return Grid(rooms, page, links)
    }

    /** Rows of the grid itself, not of the small table inside every cell. */
    private fun directRows(table: Element): List<Element> = table.children().flatMap { child ->
        when (child.tagName()) {
            "tr" -> listOf(child)
            "tbody", "thead", "tfoot" -> child.children().filter { it.tagName() == "tr" }
            else -> emptyList()
        }
    }

    /**
     * A cell's text, split where the site writes `</br>`, which HTML5 reads as `<br>`, and read
     * through every other element (`<font>`, `<span>` …) but the tables a cell holds, such as its
     * small leading one. A booking is told by its words before the text is split into lines, so
     * the booker's name goes no further than this function.
     */
    private fun slotUse(cell: Element): RoomSlotUse {
        val parts = mutableListOf(StringBuilder())
        fun read(node: Element) {
            for (child in node.childNodes()) {
                when {
                    child is TextNode -> parts.last().append(child.wholeText)
                    child is Element && child.tagName() == "br" -> parts += StringBuilder()
                    child is Element && child.tagName() == "table" -> Unit
                    child is Element -> read(child)
                }
            }
        }
        read(cell)
        if (parts.any { part -> bookingMarks.any { part.contains(it) } }) return RoomSlotUse.Booked
        val lines = parts.map { it.trim().toString() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return RoomSlotUse.Free
        return RoomSlotUse.Course(name = first, teacher = lines.drop(1).joinToString(" "))
    }

    private fun pager(row: Element?): Pair<Int, Map<Int, Postback>> {
        val cell = row?.children()?.singleOrNull() ?: return 1 to emptyMap()
        var page = 1
        val links = mutableMapOf<Int, Postback>()
        for (element in cell.children()) {
            val number = element.text().trim().toIntOrNull() ?: continue
            when (element.tagName()) {
                "a" -> postback(element)?.let { links[number] = it }
                "span" -> page = number
            }
        }
        return page to links
    }

    // --- Form ---

    private fun postback(link: Element): Postback? {
        val match = postbackPattern.find(link.attr("href")) ?: return null
        return Postback(match.groupValues[1], match.groupValues[2])
    }

    private fun calendarLinks(doc: Document): Pair<Set<Int>, List<Int>> {
        val days = mutableSetOf<Int>()
        val months = mutableListOf<Int>()
        for (link in doc.select("#$CALENDAR_TARGET a[href]")) {
            val postback = postback(link)?.takeIf { it.target == CALENDAR_TARGET } ?: continue
            val argument = postback.argument
            if (argument.startsWith("V")) {
                argument.drop(1).toIntOrNull()?.let(months::add)
            } else {
                argument.toIntOrNull()?.let(days::add)
            }
        }
        return days to months
    }

    /** What a browser would submit: every named input except buttons and unticked boxes, and each list's chosen option. */
    private fun formFields(doc: Document): List<FormField> {
        val form = doc.selectFirst("form") ?: return emptyList()
        val fields = mutableListOf<FormField>()
        for (input in form.select("input")) {
            val name = input.attr("name")
            if (name.isEmpty()) continue
            val type = input.attr("type").lowercase()
            if (type in setOf("submit", "button", "image", "reset")) continue
            if (type in setOf("checkbox", "radio") && !input.hasAttr("checked")) continue
            fields += FormField(name, input.attr("value"))
        }
        for (select in form.select("select")) {
            val name = select.attr("name")
            if (name.isEmpty()) continue
            val options = select.select("option")
            val chosen = options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull()
            fields += FormField(name, chosen?.attr("value").orEmpty())
        }
        return fields
    }

    private fun buildingNames(doc: Document): Map<String, String> =
        doc.select("select[name=$BUILDING_FIELD] option").mapNotNull { option ->
            val code = option.attr("value").trim()
            val name = option.text().trim()
            if (code.isEmpty() || name.isEmpty()) null else code to name
        }.toMap()
}
