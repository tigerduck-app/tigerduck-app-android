package org.ntust.app.tigerduck.emptyclassroom

/**
 * Pages in the shape of cour01's room-use page, built to order for the client tests: the ASP.NET
 * form, the date calendar, the room grid with a small table in every cell, and the pager. The
 * courses, teachers and the booker are made up. The parser tests read the saved samples in
 * `src/test/resources/cour01/` instead.
 */
object ClassroomPageFixtures {
    private val header = "<tr align=\"center\"><td>教室</td>" +
        (1..14).joinToString("") { "<td style=\"width:2.2cm;\">第${it}節</td>" } + "</tr>"

    /** One period's cell: [text] follows the cell's small table, `</br>` included. */
    fun cell(text: String, column: Int = 2) = """
        <td nowrap="nowrap" style="font-size:10pt;">
            <table id="showinfo_grd__ctl3_Table$column" border="0" style="height:16px;width:80px;">
                <tr><td></td></tr>
            </table>
            $text
        </td>
    """.trimIndent()

    /** A room's row from 14 marks: `.` free, `b` booked, `c` the room's class. */
    fun row(room: String, marks: String): String {
        val cells = marks.mapIndexed { index, mark ->
            when (mark) {
                'b' -> cell("林小美</br> 已借用", index + 2)
                'c' -> cell("程式設計</br>王大明", index + 2)
                else -> cell("</br>", index + 2)
            }
        }
        return "<tr nowrap=\"nowrap\"><td nowrap=\"nowrap\">$room                    </td>${cells.joinToString("")}</tr>"
    }

    fun postback(target: String, argument: String) =
        "javascript:__doPostBack(&#39;$target&#39;,&#39;$argument&#39;)"

    val firstOfTwoPages = "<tr align=\"left\"><td colspan=\"15\"><span>1</span>&nbsp;" +
        "<a href=\"${postback("showinfo_grd\$_ctl54\$_ctl1", "")}\">2</a></td></tr>"
    val secondOfTwoPages = "<tr align=\"left\"><td colspan=\"15\">" +
        "<a href=\"${postback("showinfo_grd\$_ctl42\$_ctl0", "")}\">1</a>&nbsp;<span>2</span></td></tr>"
    const val ONLY_PAGE = "<tr align=\"left\"><td colspan=\"15\"><span>1</span></td></tr>"

    /** A pager row on [page] that links the page after it. */
    fun pagerTo(page: Int) = "<tr align=\"left\"><td colspan=\"15\"><span>$page</span>&nbsp;" +
        "<a href=\"${postback("pager\$${page + 1}", "")}\">${page + 1}</a></td></tr>"

    /** The calendar of the month of 2026-10-14, linking that day to 2026-11-07, with the steps to September and November. */
    val october = calendar(9783..9807, listOf(9740, 9801))

    /** The next month's view, which links every day it shows. */
    val november = calendar(9794..9835, listOf(9770, 9831))

    fun calendar(days: IntRange, months: List<Int>): String {
        val steps = months.joinToString("") { "<td><a href=\"javascript:__doPostBack('date_cal','V$it')\">&lt;</a></td>" }
        val links = days.joinToString("") { "<td><a href=\"javascript:__doPostBack('date_cal','$it')\">$it</a></td>" }
        return "<table id=\"date_cal\" title=\"月曆\"><tr>$steps</tr><tr>$links</tr></table>"
    }

    /** [rows] null leaves the grid out, as the page before a campus is chosen does. */
    fun page(
        rows: List<String>? = emptyList(),
        pager: String = ONLY_PAGE,
        calendar: String = october,
        campus: String = "HQ",
        viewState: String = "dGVzdA+/==",
    ): String {
        fun selected(code: String) = if (code == campus) " selected=\"selected\"" else ""
        val grid = rows?.let {
            """
            <table cellspacing="0" rules="all" border="1" id="showinfo_grd">
            $header
            ${it.joinToString("\n")}
            $pager
            </table>
            """
        }.orEmpty()
        return """
            <html><head><title>教室使用狀況</title></head><body>
            <form name="Form2" method="post" action="./classroom_usecondition.aspx" id="Form2">
            <input type="hidden" name="__EVENTTARGET" id="__EVENTTARGET" value="" />
            <input type="hidden" name="__EVENTARGUMENT" id="__EVENTARGUMENT" value="" />
            <input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="$viewState" />
            <input type="hidden" name="__EVENTVALIDATION" id="__EVENTVALIDATION" value="ZXZlbnQ=" />
            <input type="submit" name="query_btn" value="查詢" />
            <select name="DropdownCampusListID" id="DropdownCampusListID">
              <option value="">請選擇校區</option><option${selected("HHC")} value="HHC">華夏校區</option>
              <option${selected("HQ")} value="HQ">校本部</option>
            </select>
            <select name="DropDownBuildingListID" id="DropDownBuildingListID">
              <option selected="selected" value="">請選擇大樓</option>
              <option value="TR">研揚大樓</option><option value="IB">國際大樓</option>
            </select>
            $calendar
            $grid
            </form></body></html>
        """.trimIndent()
    }

    const val SIGN_IN_PAGE = """<html><body><form id="loginForm" action="/account/login" method="post">
        <input name="Username" /><input name="Password" type="password" /></form></body></html>"""

    const val ERROR_PAGE = """<html><head><title>執行階段錯誤</title></head><body><h1>Server Error in '/' Application.</h1></body></html>"""
}
