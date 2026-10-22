package com.utemaster.dealtexter.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Decides whether a deal gets a text and which day the call is promised for.
 *
 * Rules (Australia/Sydney local time of the deal's createdate, DST aware):
 *  - Saturday, Sunday or an NSW public holiday (any time) → call on the next working day.
 *  - Weekday, from [TimingConfig.afternoonStart] (3pm) to midnight → call on the next working day.
 *  - Weekday, midnight to [TimingConfig.morningEnd] (7am) → call the same day ("this morning").
 *  - Weekday, morning to 3pm → no text.
 *
 * The call *day* is fixed from the createdate, but the words ("tomorrow", "this morning",
 * "on Monday") are worked out relative to the moment the text is actually sent. That way a
 * deal created at 11:58pm and texted at 12:10am still promises the right day.
 */
object CallTiming {

    val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")

    data class TimingConfig(
        val morningEnd: LocalTime = LocalTime.of(7, 0),
        val afternoonStart: LocalTime = LocalTime.of(15, 0),
    )

    sealed interface Plan {
        /** Created during weekday business hours: no text. */
        data object NoText : Plan

        data class CallOn(val date: LocalDate) : Plan
    }

    fun plan(
        createdAt: Instant,
        holidays: HolidayCalendar,
        config: TimingConfig = TimingConfig(),
    ): Plan {
        val local = createdAt.atZone(SYDNEY)
        val date = local.toLocalDate()
        val time = local.toLocalTime()
        return when {
            !holidays.isWorkingDay(date) -> Plan.CallOn(holidays.nextWorkingDayAfter(date))
            time < config.morningEnd -> Plan.CallOn(date)
            time >= config.afternoonStart -> Plan.CallOn(holidays.nextWorkingDayAfter(date))
            else -> Plan.NoText
        }
    }

    /**
     * The words that follow "I'll call you", e.g. "tomorrow", "this morning", "on Monday".
     * Returns null when the call day is already in the past (too late to text).
     */
    fun whenPhrase(callDate: LocalDate, now: Instant): String? {
        val localNow = now.atZone(SYDNEY)
        val today = localNow.toLocalDate()
        return when {
            callDate.isBefore(today) -> null
            callDate == today -> if (localNow.hour < 12) "this morning" else "today"
            callDate == today.plusDays(1) -> "tomorrow"
            callDate.isAfter(today.plusDays(6)) -> "on ${dayName(callDate)} ${callDate.dayOfMonth} ${monthName(callDate)}"
            else -> "on ${dayName(callDate)}"
        }
    }

    fun sydneyTime(instant: Instant): ZonedDateTime = instant.atZone(SYDNEY)

    private fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun monthName(d: LocalDate) = d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
}
