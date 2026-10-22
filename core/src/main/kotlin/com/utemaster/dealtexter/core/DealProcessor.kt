package com.utemaster.dealtexter.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * One "check": find qualifying deals in HubSpot and text the ones that need it.
 *
 * Duplicate protection, in order:
 *  1. Local record (SENT / SENDING / etc.) – checked first, always.
 *  2. A "text sent" note already on the deal in HubSpot.
 *  3. The record is set to SENDING *before* the SMS goes out, so if the app is killed
 *     mid-send the deal is flagged for review rather than texted again.
 *  4. A process-wide lock stops the periodic check and "Run check now" overlapping.
 */
class DealProcessor(
    private val crm: CrmGateway,
    private val sms: SmsGateway,
    private val store: TextStore,
    private val notifier: Notifier,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class Summary(
        val found: Int = 0,
        val sent: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val error: String? = null,
    ) {
        override fun toString(): String =
            error?.let { "Error: $it" } ?: "Found $found deal(s): $sent texted, $failed failed, $skipped skipped"
    }

    suspend fun runCheck(config: TexterConfig): Summary = LOCK.withLock { runLocked(config) }

    private suspend fun runLocked(config: TexterConfig): Summary {
        configProblem(config)?.let { problem ->
            notifier.problem("config", "Utebuild Texter is not set up", problem)
            return Summary(error = problem)
        }

        val deals = try {
            crm.findQualifyingDeals(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Couldn't search HubSpot: ${e.message}"
            notifier.problem("hubspot-search", "HubSpot error", msg)
            return Summary(error = msg)
        }

        var summary = Summary(found = deals.size)
        for (deal in deals.sortedBy { it.createdAt }) {
            if (deal.createdAt.isBefore(config.activatedAt)) {
                summary = summary.copy(skipped = summary.skipped + 1)
                continue
            }
            summary = when (processDeal(deal, config)) {
                Outcome.SENT -> summary.copy(sent = summary.sent + 1)
                Outcome.FAILED -> summary.copy(failed = summary.failed + 1)
                Outcome.SKIPPED -> summary.copy(skipped = summary.skipped + 1)
            }
        }
        return summary
    }

    private enum class Outcome { SENT, FAILED, SKIPPED }

    private suspend fun processDeal(deal: Deal, config: TexterConfig): Outcome {
        val existing = store.get(deal.id)
        when (existing?.status) {
            Status.SENT -> {
                if (existing.notePending) writeNote(existing, config)
                return Outcome.SKIPPED
            }
            Status.SENDING -> {
                notifier.problem(
                    "uncertain-${deal.id}",
                    "Check text for ${label(deal)}",
                    "The app stopped while sending this text, so it may or may not have gone out. " +
                        "Open the app and choose \"Mark as sent\" or \"Retry\".",
                )
                return Outcome.SKIPPED
            }
            Status.GAVE_UP, Status.NO_TEXT_NEEDED, Status.ALREADY_NOTED -> return Outcome.SKIPPED
            Status.FAILED, null -> Unit // (re)try below
        }
        val attempts = existing?.attempts ?: 0

        if (!WorkHours.shouldText(deal.createdAt, config.workStart, config.workEnd)) {
            store.put(record(deal, Status.NO_TEXT_NEEDED, attempts))
            return Outcome.SKIPPED
        }

        return try {
            // Backup dedupe: is there already a "text sent" note on the deal?
            if (crm.noteBodiesForDeal(deal.id).any { SentNote.containsSentMarker(it) }) {
                store.put(record(deal, Status.ALREADY_NOTED, attempts))
                return Outcome.SKIPPED
            }

            val contacts = crm.contactsForDeal(deal.id)
            val (contact, customerNumber) = pickRecipient(contacts)
                ?: return fail(deal, attempts, "No mobile number on the deal's contact" +
                    if (contacts.isEmpty()) " (no contact associated)" else "")

            val to = if (config.testMode) PhoneNumbers.normalise(config.myNumber)!! else customerNumber
            val text = MessageTemplate.render(config.messageTemplate, contact.firstName, config.myName)

            store.put(record(deal, Status.SENDING, attempts + 1).copy(message = text, sentTo = to, testMode = config.testMode))
            when (val result = sms.send(to, text)) {
                is SmsResult.Failed -> fail(deal, attempts + 1, "SMS failed: ${result.reason}")
                is SmsResult.Unknown -> {
                    // Leave the record as SENDING: it will be flagged for review, never resent automatically.
                    store.put(
                        record(deal, Status.SENDING, attempts + 1, result.reason)
                            .copy(message = text, sentTo = to, testMode = config.testMode),
                    )
                    notifier.problem(
                        "uncertain-${deal.id}",
                        "Check text for ${label(deal)}",
                        "${result.reason}. It may or may not have gone out. " +
                            "Open the app and choose \"Mark as sent\" or \"Retry\".",
                    )
                    Outcome.FAILED
                }
                SmsResult.Sent -> {
                    val sent = record(deal, Status.SENT, attempts + 1).copy(
                        message = text,
                        sentTo = to,
                        sentAt = clock.instant(),
                        testMode = config.testMode,
                        notePending = !config.testMode || config.notesInTestMode,
                    )
                    store.put(sent)
                    val who = if (config.testMode) {
                        "TEST MODE – sent to your number (customer: ${PhoneNumbers.pretty(customerNumber)})"
                    } else {
                        "Sent to ${PhoneNumbers.pretty(to)}"
                    }
                    notifier.sent(deal.id, "Texted ${contact.firstName ?: ""} – ${label(deal)}".trim(), "$who: \"$text\"")
                    if (sent.notePending) writeNote(sent, config)
                    Outcome.SENT
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // HubSpot (or anything else) failed before the SMS was sent.
            fail(deal, attempts, "HubSpot error: ${e.message}", countAttempt = true)
        }
    }

    private fun pickRecipient(contacts: List<Contact>): Pair<Contact, String>? {
        for (c in contacts) {
            for (raw in listOf(c.mobilePhone, c.phone)) {
                val n = PhoneNumbers.normalise(raw) ?: continue
                if (PhoneNumbers.isTextable(n)) return c to n
            }
        }
        return null
    }

    private suspend fun writeNote(rec: TextRecord, config: TexterConfig) {
        try {
            crm.createDealNote(
                rec.dealId,
                config.ownerId,
                SentNote.html(rec.message.orEmpty(), rec.sentTo.orEmpty(), rec.sentAt ?: clock.instant(), rec.testMode),
                rec.sentAt ?: clock.instant(),
            )
            store.put(rec.copy(notePending = false, lastError = null, updatedAt = clock.instant()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The text is out; only the note is missing. It will be retried on the next check – the SMS is not resent.
            store.put(rec.copy(lastError = "Note not saved yet: ${e.message}", updatedAt = clock.instant()))
            notifier.problem(
                "note-${rec.dealId}",
                "HubSpot note not saved",
                "The text to ${rec.dealName ?: rec.dealId} was sent, but the note couldn't be added (${e.message}). Will retry.",
            )
        }
    }

    private suspend fun fail(deal: Deal, attempts: Int, reason: String, countAttempt: Boolean = false): Outcome {
        val n = if (countAttempt) attempts + 1 else attempts
        val gaveUp = n >= MAX_ATTEMPTS
        store.put(record(deal, if (gaveUp) Status.GAVE_UP else Status.FAILED, n, reason))
        val title = if (gaveUp) "Gave up texting ${label(deal)}" else "Couldn't text ${label(deal)}"
        val text = if (gaveUp) "$reason. Tried $n times – use Retry in the app once fixed." else "$reason. Will retry on the next check."
        // Key includes the reason so the same failure isn't re-notified every 15 minutes.
        notifier.problem("fail-${deal.id}-${if (gaveUp) "gaveup" else reason}", title, text)
        return Outcome.FAILED
    }

    private fun record(deal: Deal, status: Status, attempts: Int, error: String? = null) = TextRecord(
        dealId = deal.id,
        dealName = deal.name,
        status = status,
        attempts = attempts,
        lastError = error,
        updatedAt = clock.instant(),
    )

    private fun label(deal: Deal) = deal.name?.takeIf { it.isNotBlank() } ?: "deal ${deal.id}"

    companion object {
        /** About 1.5 hours of retries at the 15-minute check interval. */
        const val MAX_ATTEMPTS = 6

        private val LOCK = Mutex()

        /** Returns a human-readable reason the app can't run yet, or null if it's ready. */
        fun configProblem(c: TexterConfig): String? = when {
            c.pipelineId.isBlank() || c.stageId.isBlank() || c.ownerId.isBlank() || c.linkProperty.isBlank() ->
                "Fill in the pipeline ID, stage ID, owner ID and Utebuilder link property in Settings."
            c.myName.isBlank() -> "Fill in your name in Settings."
            c.testMode && PhoneNumbers.normalise(c.myNumber) == null ->
                "Test mode is on but your own mobile number isn't set (or isn't valid) in Settings."
            c.messageTemplate.isBlank() -> "The message is empty. Write one in Settings."
            else -> null
        }
    }
}
