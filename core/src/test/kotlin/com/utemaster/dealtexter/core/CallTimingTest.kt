package com.utemaster.dealtexter.core

import com.utemaster.dealtexter.core.CallTiming.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class CallTimingTest {

    private val holidays = NswHolidays.default()

    /** Sydney wall-clock time → instant (uses the real Australia/Sydney rules). */
    private fun syd(s: String): Instant = LocalDateTime.parse(s).atZone(CallTiming.SYDNEY).toInstant()

    private fun plan(created: Instant) = CallTiming.plan(created, holidays)

    private fun callDay(created: Instant): LocalDate = (plan(created) as Plan.CallOn).date

    /** Full answer as the customer would read it: created at [created], texted at [sent]. */
    private fun phrase(created: Instant, sent: Instant = created.plusSeconds(10 * 60)): String? =
        when (val p = plan(created)) {
            Plan.NoText -> "NO TEXT"
            is Plan.CallOn -> CallTiming.whenPhrase(p.date, sent)
        }

    // ---- Weekday afternoons / evenings -------------------------------------------------

    @Test fun `weekday afternoon promises tomorrow`() {
        assertEquals("tomorrow", phrase(syd("2026-10-27T16:00"))) // Tue → Wed
    }

    @Test fun `3pm exactly is the start of the texting window`() {
        assertEquals("tomorrow", phrase(syd("2026-10-27T15:00")))
        assertEquals("NO TEXT", phrase(syd("2026-10-27T14:59:59")))
    }

    @Test fun `weekday 11_59pm promises tomorrow`() {
        assertEquals("tomorrow", phrase(syd("2026-10-27T23:59"), syd("2026-10-27T23:59:30")))
    }

    @Test fun `deal created just before midnight but texted after midnight says this morning`() {
        // Created Tue 11:58pm → call Wed. Text goes out Wed 12:10am, so "tomorrow" would be wrong.
        assertEquals("this morning", phrase(syd("2026-10-27T23:58"), syd("2026-10-28T00:10")))
    }

    @Test fun `Friday afternoon promises Monday`() {
        val created = syd("2026-10-23T15:30") // Friday
        assertEquals(LocalDate.parse("2026-10-26"), callDay(created))
        assertEquals("on Monday", phrase(created))
    }

    @Test fun `Friday late night texted after midnight still promises Monday`() {
        assertEquals("on Monday", phrase(syd("2026-10-23T23:55"), syd("2026-10-24T00:10")))
    }

    // ---- Weekends ------------------------------------------------------------------------

    @Test fun `Saturday any time promises Monday`() {
        for (t in listOf("00:01", "09:00", "12:00", "15:00", "23:45")) {
            val created = syd("2026-10-24T$t")
            assertEquals("Saturday $t", LocalDate.parse("2026-10-26"), callDay(created))
            assertEquals("Saturday $t", "on Monday", phrase(created))
        }
    }

    @Test fun `Sunday afternoon says tomorrow`() {
        // Sunday → Monday, which is literally tomorrow.
        assertEquals("tomorrow", phrase(syd("2026-10-25T14:00")))
    }

    @Test fun `Sunday late night texted on Monday morning says this morning`() {
        assertEquals("this morning", phrase(syd("2026-10-25T23:50"), syd("2026-10-26T00:05")))
    }

    @Test fun `Saturday before a holiday Monday promises Tuesday`() {
        // King's Birthday Mon 8 June 2026
        val created = syd("2026-06-06T11:00")
        assertEquals(LocalDate.parse("2026-06-09"), callDay(created))
        assertEquals("on Tuesday", phrase(created))
    }

    // ---- Early mornings --------------------------------------------------------------------

    @Test fun `2am Monday build says this morning`() {
        val created = syd("2026-10-26T02:00")
        assertEquals(LocalDate.parse("2026-10-26"), callDay(created))
        assertEquals("this morning", phrase(created))
    }

    @Test fun `morning window ends at 7am`() {
        assertEquals("this morning", phrase(syd("2026-10-26T06:59")))
        assertEquals("NO TEXT", phrase(syd("2026-10-26T07:00")))
    }

    @Test fun `morning cutoff is configurable`() {
        val cfg = CallTiming.TimingConfig(morningEnd = java.time.LocalTime.of(8, 0))
        assertEquals(Plan.CallOn(LocalDate.parse("2026-10-26")), CallTiming.plan(syd("2026-10-26T07:30"), holidays, cfg))
    }

    @Test fun `2am on a holiday Monday promises Tuesday as tomorrow`() {
        val created = syd("2026-06-08T02:00") // King's Birthday
        assertEquals(LocalDate.parse("2026-06-09"), callDay(created))
        assertEquals("tomorrow", phrase(created))
    }

    // ---- Business hours: no text ---------------------------------------------------------

    @Test fun `weekday business hours get no text`() {
        for (t in listOf("07:00", "09:30", "12:00", "14:59")) {
            assertEquals(t, Plan.NoText, plan(syd("2026-10-28T$t")))
        }
    }

    // ---- Public holidays -------------------------------------------------------------------

    @Test fun `weekday public holiday during business hours promises next working day`() {
        // Labour Day Mon 5 Oct 2026, 10am
        assertEquals("tomorrow", phrase(syd("2026-10-05T10:00")))
    }

    @Test fun `Thursday afternoon before Easter promises Tuesday`() {
        // Good Friday 26 Mar 2027, Easter Monday 29 Mar 2027
        val created = syd("2027-03-25T16:00")
        assertEquals(LocalDate.parse("2027-03-30"), callDay(created))
        assertEquals("on Tuesday", phrase(created))
    }

    @Test fun `Christmas Eve 2026 afternoon skips Christmas, weekend and Boxing Day substitute`() {
        val created = syd("2026-12-24T16:00")
        assertEquals(LocalDate.parse("2026-12-29"), callDay(created))
        assertEquals("on Tuesday", phrase(created))
    }

    @Test fun `Christmas Eve 2027 afternoon skips two substitute days`() {
        val created = syd("2027-12-24T16:00") // Friday
        assertEquals(LocalDate.parse("2027-12-29"), callDay(created))
        assertEquals("on Wednesday", phrase(created))
    }

    @Test fun `New Year's Eve afternoon promises the Monday after New Year's Day`() {
        val created = syd("2026-12-31T16:00") // Thursday; Fri 1 Jan is a holiday
        assertEquals(LocalDate.parse("2027-01-04"), callDay(created))
        assertEquals("on Monday", phrase(created))
    }

    @Test fun `Easter Sunday deal with phone off until Tuesday morning says this morning`() {
        // Created Easter Sunday 2026 → call Tue 7 Apr (Easter Monday is a holiday).
        assertEquals("this morning", phrase(syd("2026-04-05T12:00"), syd("2026-04-07T06:00")))
    }

    // ---- Daylight saving changeover --------------------------------------------------------

    @Test fun `DST start weekend - Friday afternoon before Labour Day promises Tuesday`() {
        // Clocks go forward Sun 4 Oct 2026 2am→3am; Mon 5 Oct is Labour Day.
        assertEquals("on Tuesday", phrase(syd("2026-10-02T16:00")))
    }

    @Test fun `DST start day itself - Sunday 3_30am AEDT promises Tuesday`() {
        val created = Instant.parse("2026-10-03T16:30:00Z") // = Sun 4 Oct 03:30 AEDT (+11)
        assertEquals(LocalDate.parse("2026-10-06"), callDay(created))
    }

    @Test fun `after DST starts, a weekday uses the +11 offset`() {
        // Tue 6 Oct 2026. 04:30Z is 15:30 AEDT (text); with the old +10 offset it would be 14:30 (no text).
        assertEquals("tomorrow", phrase(Instant.parse("2026-10-06T04:30:00Z")))
        assertEquals("NO TEXT", phrase(Instant.parse("2026-10-06T03:59:00Z"))) // 14:59 AEDT
    }

    @Test fun `DST end day itself - Sunday promises Monday`() {
        // Clocks go back Sun 4 Apr 2027 3am→2am. 15:30Z on Sat 3 Apr = 02:30 AEDT Sunday (first pass).
        val created = Instant.parse("2027-04-03T15:30:00Z")
        assertEquals(LocalDate.parse("2027-04-05"), callDay(created))
    }

    @Test fun `after DST ends, a weekday uses the +10 offset`() {
        // Mon 5 Apr 2027. 04:30Z is 14:30 AEST (no text); with +11 it would wrongly be 15:30.
        assertEquals("NO TEXT", phrase(Instant.parse("2027-04-05T04:30:00Z")))
        assertEquals("tomorrow", phrase(Instant.parse("2027-04-05T05:00:00Z"))) // 15:00 AEST
    }

    @Test fun `Friday before DST ends is still on +11`() {
        // Fri 2 Apr 2027, 04:30Z = 15:30 AEDT → text, promising Monday 5 Apr.
        val created = Instant.parse("2027-04-02T04:30:00Z")
        assertEquals(LocalDate.parse("2027-04-05"), callDay(created))
        assertEquals("on Monday", phrase(created))
    }

    @Test fun `early morning across DST start uses the right offset`() {
        // Tue 6 Oct 2026 19:30Z on Mon 5th = 06:30 AEDT Tue → this morning
        assertEquals("this morning", phrase(Instant.parse("2026-10-05T19:30:00Z")))
        // 20:30Z = 07:30 AEDT → no text (would be 06:30 under +10)
        assertEquals("NO TEXT", phrase(Instant.parse("2026-10-05T20:30:00Z")))
    }

    // ---- Wording edge cases ------------------------------------------------------------------

    @Test fun `call day today after midday says today`() {
        assertEquals("today", CallTiming.whenPhrase(LocalDate.parse("2026-10-26"), syd("2026-10-26T13:00")))
    }

    @Test fun `call day in the past returns null`() {
        assertNull(CallTiming.whenPhrase(LocalDate.parse("2026-10-26"), syd("2026-10-27T01:00")))
    }

    @Test fun `call day more than six days away includes the date`() {
        assertEquals(
            "on Monday 9 November",
            CallTiming.whenPhrase(LocalDate.parse("2026-11-09"), syd("2026-10-31T10:00")),
        )
    }
}
