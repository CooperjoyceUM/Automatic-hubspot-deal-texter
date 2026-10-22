package com.utemaster.dealtexter.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * NSW public holidays.
 *
 * HOW TO UPDATE EACH YEAR
 * -----------------------
 * The list can be edited inside the app (Settings → NSW public holidays) without
 * rebuilding. To change the built-in default, edit [DEFAULT_LIST] below.
 *
 * Format: one holiday per line, `YYYY-MM-DD` followed by an optional name.
 * Lines starting with `#` and blank lines are ignored.
 * Source of truth: https://www.nsw.gov.au/about-nsw/public-holidays
 *
 * Only state-wide public holidays are listed. Bank Holiday (first Monday in
 * August) is NOT included because it is only a holiday for banks.
 * Weekend dates are listed for completeness; they don't change the outcome.
 */
object NswHolidays {

    const val DEFAULT_LIST = """# NSW public holidays – one per line: YYYY-MM-DD Name
# 2026
2026-01-01 New Year's Day
2026-01-26 Australia Day
2026-04-03 Good Friday
2026-04-04 Easter Saturday
2026-04-05 Easter Sunday
2026-04-06 Easter Monday
2026-04-25 Anzac Day
2026-06-08 King's Birthday
2026-10-05 Labour Day
2026-12-25 Christmas Day
2026-12-26 Boxing Day
2026-12-28 Additional day (Boxing Day)
# 2027
2027-01-01 New Year's Day
2027-01-26 Australia Day
2027-03-26 Good Friday
2027-03-27 Easter Saturday
2027-03-28 Easter Sunday
2027-03-29 Easter Monday
2027-04-25 Anzac Day
2027-06-14 King's Birthday
2027-10-04 Labour Day
2027-12-25 Christmas Day
2027-12-26 Boxing Day
2027-12-27 Additional day (Christmas Day)
2027-12-28 Additional day (Boxing Day)
# 2028
2028-01-01 New Year's Day
2028-01-03 Additional day (New Year's Day)
2028-01-26 Australia Day
2028-04-14 Good Friday
2028-04-15 Easter Saturday
2028-04-16 Easter Sunday
2028-04-17 Easter Monday
2028-04-25 Anzac Day
2028-06-12 King's Birthday
2028-10-02 Labour Day
2028-12-25 Christmas Day
2028-12-26 Boxing Day
"""

    /** Parses the text format described above. Invalid lines are reported in [ParseResult.errors]. */
    fun parse(text: String): ParseResult {
        val dates = mutableSetOf<LocalDate>()
        val errors = mutableListOf<String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val token = line.substringBefore(' ').substringBefore('\t')
            try {
                dates += LocalDate.parse(token)
            } catch (e: DateTimeParseException) {
                errors += "Line ${index + 1}: \"$line\" does not start with a YYYY-MM-DD date"
            }
        }
        return ParseResult(HolidayCalendar(dates), errors)
    }

    fun default(): HolidayCalendar = parse(DEFAULT_LIST).calendar

    data class ParseResult(val calendar: HolidayCalendar, val errors: List<String>)
}

class HolidayCalendar(private val holidays: Set<LocalDate>) {

    fun isHoliday(date: LocalDate) = date in holidays

    fun isWorkingDay(date: LocalDate) =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(date)

    /** The first working day strictly after [date]. */
    fun nextWorkingDayAfter(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (!isWorkingDay(d)) d = d.plusDays(1)
        return d
    }

    /** True if the list contains at least one holiday in [year], used to warn when the list needs updating. */
    fun hasDataFor(year: Int) = holidays.any { it.year == year }
}
