package com.utemaster.dealtexter.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When to text (Sydney time, by the deal's createdate):
 *  - Weekday before 7:00am → text
 *  - Weekday from 3:00pm → text
 *  - Weekday 7:00am–3:00pm → no text
 *  - Weekends and NSW public holidays → text at any time
 */
object WorkHours {

    val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")
    val START: LocalTime = LocalTime.of(7, 0)
    val END: LocalTime = LocalTime.of(15, 0)

    /**
     * NSW public holidays. To update: add next year's dates from
     * https://www.nsw.gov.au/about-nsw/public-holidays and rebuild.
     * (Weekend holidays are left out – weekends always get a text anyway.)
     */
    val NSW_PUBLIC_HOLIDAYS: Set<LocalDate> = listOf(
        // 2026
        "2026-01-01", "2026-01-26", "2026-04-03", "2026-04-06", "2026-06-08", "2026-10-05",
        "2026-12-25", "2026-12-28",
        // 2027
        "2027-01-01", "2027-01-26", "2027-03-26", "2027-03-29", "2027-06-14", "2027-10-04",
        "2027-12-27", "2027-12-28",
        // 2028
        "2028-01-03", "2028-01-26", "2028-04-14", "2028-04-17", "2028-04-25", "2028-06-12",
        "2028-10-02", "2028-12-25", "2028-12-26",
    ).map(LocalDate::parse).toSet()

    fun shouldText(createdAt: Instant): Boolean {
        val local = sydneyTime(createdAt)
        val date = local.toLocalDate()
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return true
        if (date in NSW_PUBLIC_HOLIDAYS) return true
        val time = local.toLocalTime()
        return time.isBefore(START) || !time.isBefore(END)
    }

    fun sydneyTime(instant: Instant): ZonedDateTime = instant.atZone(SYDNEY)
}
