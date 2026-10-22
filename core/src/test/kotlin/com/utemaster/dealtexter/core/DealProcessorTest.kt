package com.utemaster.dealtexter.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class DealProcessorTest {

    private fun syd(s: String): Instant = LocalDateTime.parse(s).atZone(WorkHours.SYDNEY).toInstant()

    private class FakeCrm : CrmGateway {
        val deals = mutableListOf<Deal>()
        val contacts = mutableMapOf<String, List<Contact>>()
        val notes = mutableMapOf<String, MutableList<String>>()
        var failSearch = false
        var failNotes = false
        var failCreateNote = false

        override suspend fun findQualifyingDeals(config: TexterConfig) =
            if (failSearch) throw CrmException("HTTP 500") else deals.toList()

        override suspend fun contactsForDeal(dealId: String) = contacts[dealId].orEmpty()

        override suspend fun noteBodiesForDeal(dealId: String): List<String> =
            if (failNotes) throw CrmException("HTTP 429") else notes[dealId].orEmpty()

        override suspend fun createDealNote(dealId: String, ownerId: String, htmlBody: String, timestamp: Instant) {
            if (failCreateNote) throw CrmException("HTTP 403")
            notes.getOrPut(dealId) { mutableListOf() } += htmlBody
        }
    }

    private class FakeSms : SmsGateway {
        val sent = mutableListOf<Pair<String, String>>()
        var fail: String? = null
        var unknown = false
        override suspend fun send(to: String, text: String): SmsResult {
            fail?.let { return SmsResult.Failed(it) }
            if (unknown) return SmsResult.Unknown("No confirmation from the phone")
            sent += to to text
            return SmsResult.Sent
        }
    }

    private class FakeStore : TextStore {
        val records = mutableMapOf<String, TextRecord>()
        override suspend fun get(dealId: String) = records[dealId]
        override suspend fun put(record: TextRecord) { records[record.dealId] = record }
    }

    private class FakeNotifier : Notifier {
        val problems = mutableListOf<String>()
        val sent = mutableListOf<String>()
        override fun problem(key: String, title: String, text: String) { problems += "$title: $text" }
        override fun sent(dealId: String, title: String, text: String) { sent += text }
    }

    private val crm = FakeCrm()
    private val sms = FakeSms()
    private val store = FakeStore()
    private val notifier = FakeNotifier()

    private fun processorAt(now: Instant) =
        DealProcessor(crm, sms, store, notifier, Clock.fixed(now, ZoneOffset.UTC))

    private fun config(testMode: Boolean = false) = TexterConfig(
        pipelineId = "658412132",
        stageId = "967474456",
        ownerId = "1101472483",
        linkProperty = "utebuilder_submission_link",
        myName = "Cooper",
        myNumber = "0400 000 000",
        testMode = testMode,
        notesInTestMode = false,
        messageTemplate = MessageTemplate.DEFAULT_TEMPLATE,
        activatedAt = syd("2026-10-01T00:00"),
    )

    private fun addDeal(id: String, created: Instant, mobile: String? = "0412 345 678", phone: String? = null) {
        crm.deals += Deal(id, "Deal $id", created)
        crm.contacts[id] = listOf(Contact("c$id", "Sam", mobile, phone))
    }

    @Test fun `sends once, records it and writes a note`() = runTest {
        addDeal("1", syd("2026-10-23T16:00")) // Friday afternoon
        val p = processorAt(syd("2026-10-23T16:10"))

        val summary = p.runCheck(config())
        assertEquals(1, summary.sent)
        assertEquals("+61412345678", sms.sent.single().first)
        assertTrue(sms.sent.single().second.startsWith("Hey Sam,"))
        assertEquals(Status.SENT, store.records["1"]!!.status)
        assertFalse(store.records["1"]!!.notePending)
        assertTrue(SentNote.containsSentMarker(crm.notes["1"]!!.single()))

        // Second check: nothing new is sent.
        p.runCheck(config())
        assertEquals(1, sms.sent.size)
        assertEquals(1, crm.notes["1"]!!.size)
    }

    @Test fun `existing HubSpot note prevents a send even with no local record`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"))
        crm.notes["1"] = mutableListOf("<p>${SentNote.MARKER}</p>")
        processorAt(syd("2026-10-23T16:10")).runCheck(config())
        assertTrue(sms.sent.isEmpty())
        assertEquals(Status.ALREADY_NOTED, store.records["1"]!!.status)
    }

    @Test fun `business hours deal is not texted`() = runTest {
        addDeal("1", syd("2026-10-23T10:00"))
        processorAt(syd("2026-10-23T10:10")).runCheck(config())
        assertTrue(sms.sent.isEmpty())
        assertEquals(Status.NO_TEXT_NEEDED, store.records["1"]!!.status)
    }

    @Test fun `deals created before activation are ignored`() = runTest {
        addDeal("1", syd("2026-09-30T16:00"))
        processorAt(syd("2026-10-23T16:10")).runCheck(config())
        assertTrue(sms.sent.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test fun `test mode sends to my number and writes no note`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"))
        processorAt(syd("2026-10-23T16:10")).runCheck(config(testMode = true))
        assertEquals("+61400000000", sms.sent.single().first)
        assertTrue(crm.notes["1"].isNullOrEmpty())
        assertTrue(store.records["1"]!!.testMode)
    }

    @Test fun `falls back to phone when mobilephone is empty`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"), mobile = null, phone = "0412 999 888")
        processorAt(syd("2026-10-23T16:10")).runCheck(config())
        assertEquals("+61412999888", sms.sent.single().first)
    }

    @Test fun `no mobile number notifies and is retried later without marking texted`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"), mobile = null, phone = "02 9876 5432")
        val p = processorAt(syd("2026-10-23T16:10"))
        p.runCheck(config())
        assertTrue(sms.sent.isEmpty())
        assertEquals(Status.FAILED, store.records["1"]!!.status)
        assertTrue(notifier.problems.single().contains("No mobile number"))

        // Number gets fixed in HubSpot → next check sends.
        crm.contacts["1"] = listOf(Contact("c1", "Sam", "0412 345 678", null))
        p.runCheck(config())
        assertEquals(1, sms.sent.size)
        assertEquals(Status.SENT, store.records["1"]!!.status)
    }

    @Test fun `SMS failure is retried then gives up`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"))
        sms.fail = "No service"
        val p = processorAt(syd("2026-10-23T16:10"))
        repeat(DealProcessor.MAX_ATTEMPTS) { p.runCheck(config()) }
        assertEquals(Status.GAVE_UP, store.records["1"]!!.status)
        sms.fail = null
        p.runCheck(config())
        assertTrue("no more attempts after giving up", sms.sent.isEmpty())
    }

    @Test fun `HubSpot error before sending does not mark texted`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"))
        crm.failNotes = true
        val p = processorAt(syd("2026-10-23T16:10"))
        p.runCheck(config())
        assertTrue(sms.sent.isEmpty())
        assertEquals(Status.FAILED, store.records["1"]!!.status)
        crm.failNotes = false
        p.runCheck(config())
        assertEquals(1, sms.sent.size)
    }

    @Test fun `search failure notifies`() = runTest {
        crm.failSearch = true
        val s = processorAt(syd("2026-10-23T16:10")).runCheck(config())
        assertTrue(s.error!!.contains("HubSpot"))
        assertEquals(1, notifier.problems.size)
    }

    @Test fun `note failure retries the note but never resends the SMS`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"))
        crm.failCreateNote = true
        val p = processorAt(syd("2026-10-23T16:10"))
        p.runCheck(config())
        assertEquals(1, sms.sent.size)
        assertTrue(store.records["1"]!!.notePending)

        crm.failCreateNote = false
        p.runCheck(config())
        assertEquals(1, sms.sent.size)
        assertFalse(store.records["1"]!!.notePending)
        assertEquals(1, crm.notes["1"]!!.size)
    }

    @Test fun `interrupted send is flagged for review, not resent`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"))
        store.records["1"] = TextRecord("1", "Deal 1", Status.SENDING, attempts = 1, updatedAt = syd("2026-10-23T16:05"))
        processorAt(syd("2026-10-23T16:20")).runCheck(config())
        assertTrue(sms.sent.isEmpty())
        assertEquals(Status.SENDING, store.records["1"]!!.status)
        assertTrue(notifier.problems.single().contains("may or may not"))
    }

    @Test fun `unconfirmed send stays flagged and is never resent`() = runTest {
        addDeal("1", syd("2026-10-23T16:00"))
        sms.unknown = true
        val p = processorAt(syd("2026-10-23T16:10"))
        p.runCheck(config())
        assertEquals(Status.SENDING, store.records["1"]!!.status)
        sms.unknown = false
        p.runCheck(config())
        assertTrue(sms.sent.isEmpty())
    }
}
