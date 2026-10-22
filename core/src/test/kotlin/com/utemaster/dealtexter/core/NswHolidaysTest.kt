package com.utemaster.dealtexter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NswHolidaysTest {

    @Test fun `built-in list parses cleanly and covers the next few years`() {
        val result = NswHolidays.parse(NswHolidays.DEFAULT_LIST)
        assertEquals(emptyList<String>(), result.errors)
        for (year in 2026..2028) assertTrue("$year", result.calendar.hasDataFor(year))
    }

    @Test fun `bad lines are reported, good lines kept`() {
        val result = NswHolidays.parse("# comment\n\n2030-01-01 New Year\n1/1/2030 oops\n")
        assertEquals(1, result.errors.size)
        assertTrue(result.calendar.isHoliday(LocalDate.parse("2030-01-01")))
    }

    @Test fun `working days exclude weekends and holidays`() {
        val cal = NswHolidays.default()
        assertFalse(cal.isWorkingDay(LocalDate.parse("2026-10-05"))) // Labour Day
        assertFalse(cal.isWorkingDay(LocalDate.parse("2026-10-24"))) // Saturday
        assertTrue(cal.isWorkingDay(LocalDate.parse("2026-10-26")))
        assertFalse(cal.isHoliday(LocalDate.parse("2026-08-03"))) // Bank Holiday is not a public holiday
    }
}
