package com.utemaster.dealtexter.core

import java.time.Instant

/** Everything the workflow needs from Settings, already validated and parsed. */
data class TexterConfig(
    val pipelineId: String,
    val stageId: String,
    val ownerId: String,
    val linkProperty: String,
    val myName: String,
    /** Your own mobile; in test mode every text goes here instead of to the customer. */
    val myNumber: String,
    val testMode: Boolean,
    /** Also write HubSpot notes for test-mode sends (off by default so customer deals aren't cluttered). */
    val notesInTestMode: Boolean,
    val messageTemplate: String,
    /** Moment the app was first switched on. Deals created before this are never texted. */
    val activatedAt: Instant,
)

data class Deal(
    val id: String,
    val name: String?,
    val createdAt: Instant,
)

data class Contact(
    val id: String,
    val firstName: String?,
    val mobilePhone: String?,
    val phone: String?,
)

/** HubSpot access. Implementations throw [CrmException] on API failures. */
interface CrmGateway {
    suspend fun findQualifyingDeals(config: TexterConfig): List<Deal>
    suspend fun contactsForDeal(dealId: String): List<Contact>
    suspend fun noteBodiesForDeal(dealId: String): List<String>
    suspend fun createDealNote(dealId: String, ownerId: String, htmlBody: String, timestamp: Instant)
}

class CrmException(message: String, val statusCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

sealed interface SmsResult {
    data object Sent : SmsResult
    /** Definitely not sent. Safe to retry. */
    data class Failed(val reason: String) : SmsResult
    /** The phone never reported back, so it may or may not have gone. Not retried automatically. */
    data class Unknown(val reason: String) : SmsResult
}

interface SmsGateway {
    /** Sends [text] (split into parts if long) and waits for the radio's sent result. */
    suspend fun send(to: String, text: String): SmsResult
}

enum class Status {
    /** Text sent successfully. Never sent again. */
    SENT,
    /** A send was started but the app stopped before learning the result. Needs a human decision. */
    SENDING,
    /** Last attempt failed; will be retried on the next check (until [DealProcessor.MAX_ATTEMPTS]). */
    FAILED,
    /** Gave up after repeated failures; retry from the app. */
    GAVE_UP,
    /** Created during weekday work hours: no text by design. */
    NO_TEXT_NEEDED,
    /** HubSpot already has a "text sent" note (e.g. after reinstalling the app). */
    ALREADY_NOTED,
}

data class TextRecord(
    val dealId: String,
    val dealName: String?,
    val status: Status,
    val attempts: Int = 0,
    val lastError: String? = null,
    val message: String? = null,
    val sentTo: String? = null,
    val sentAt: Instant? = null,
    val testMode: Boolean = false,
    /** Text went out but the HubSpot note hasn't been written yet. */
    val notePending: Boolean = false,
    val updatedAt: Instant,
)

interface TextStore {
    suspend fun get(dealId: String): TextRecord?
    suspend fun put(record: TextRecord)
}

interface Notifier {
    /** A problem that needs your attention. [key] lets the implementation suppress repeats. */
    fun problem(key: String, title: String, text: String)
    fun sent(dealId: String, title: String, text: String)
}
