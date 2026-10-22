package com.utemaster.dealtexter.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The only timing rule: deals created during weekday work hours (Sydney time) don't get a text,
 * because I'd normally just call them. Everything else – evenings, overnight, weekends – does.
 */
object WorkHours {

    val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")

    fun shouldText(
        createdAt: Instant,
        start: LocalTime = LocalTime.of(7, 0),
        end: LocalTime = LocalTime.of(15, 0),
    ): Boolean {
        val local = sydneyTime(createdAt)
        val weekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY
        val time = local.toLocalTime()
        val duringWorkHours = !weekend && !time.isBefore(start) && time.isBefore(end)
        return !duringWorkHours
    }

    fun sydneyTime(instant: Instant): ZonedDateTime = instant.atZone(SYDNEY)
}
