package com.utemaster.dealtexter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

class WorkHoursTest {

    /** Sydney wall-clock time → instant (uses the real Australia/Sydney rules). */
    private fun syd(s: String): Instant = LocalDateTime.parse(s).atZone(WorkHours.SYDNEY).toInstant()

    private fun texts(created: Instant) = WorkHours.shouldText(created)

    @Test fun `weekday work hours get no text`() {
        for (t in listOf("07:00", "09:30", "12:00", "14:59")) assertFalse(t, texts(syd("2026-10-28T$t")))
    }

    @Test fun `weekday afternoon and evening get a text`() {
        for (t in listOf("15:00", "16:30", "23:59")) assertTrue(t, texts(syd("2026-10-28T$t")))
    }

    @Test fun `Friday afternoon gets a text`() {
        assertTrue(texts(syd("2026-10-23T15:30")))
        assertFalse(texts(syd("2026-10-23T14:30")))
    }

    @Test fun `Saturday and Sunday any time get a text`() {
        for (d in listOf("2026-10-24", "2026-10-25")) {
            for (t in listOf("00:01", "09:00", "12:00", "15:00", "23:59")) assertTrue("$d $t", texts(syd("${d}T$t")))
        }
    }

    @Test fun `2am Monday gets a text, 7am does not`() {
        assertTrue(texts(syd("2026-10-26T02:00")))
        assertTrue(texts(syd("2026-10-26T06:59")))
        assertFalse(texts(syd("2026-10-26T07:00")))
    }

    @Test fun `work hours are configurable`() {
        assertTrue(WorkHours.shouldText(syd("2026-10-26T07:30"), start = LocalTime.of(8, 0)))
    }

    // ---- Daylight saving changeover --------------------------------------------------------

    @Test fun `after DST starts, a weekday uses the +11 offset`() {
        // Clocks go forward Sun 4 Oct 2026. Tue 6 Oct: 04:30Z = 15:30 AEDT (text); under +10 it'd be 14:30.
        assertTrue(texts(Instant.parse("2026-10-06T04:30:00Z")))
        assertFalse(texts(Instant.parse("2026-10-06T03:59:00Z"))) // 14:59 AEDT
        // 19:30Z Mon = 06:30 AEDT Tue (text); 20:30Z = 07:30 AEDT (no text).
        assertTrue(texts(Instant.parse("2026-10-05T19:30:00Z")))
        assertFalse(texts(Instant.parse("2026-10-05T20:30:00Z")))
    }

    @Test fun `DST start day itself is a Sunday so it texts`() {
        assertTrue(texts(Instant.parse("2026-10-03T16:30:00Z"))) // Sun 4 Oct 03:30 AEDT
    }

    @Test fun `after DST ends, a weekday uses the +10 offset`() {
        // Clocks go back Sun 4 Apr 2027. Mon 5 Apr: 04:30Z = 14:30 AEST (no text); under +11 it'd be 15:30.
        assertFalse(texts(Instant.parse("2027-04-05T04:30:00Z")))
        assertTrue(texts(Instant.parse("2027-04-05T05:00:00Z"))) // 15:00 AEST
    }

    @Test fun `Friday before DST ends is still on +11`() {
        // Fri 2 Apr 2027: 04:30Z = 15:30 AEDT → text.
        assertTrue(texts(Instant.parse("2027-04-02T04:30:00Z")))
    }
}
