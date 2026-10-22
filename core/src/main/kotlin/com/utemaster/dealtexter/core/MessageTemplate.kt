package com.utemaster.dealtexter.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object MessageTemplate {

    /** Text that marks the qualifying questions as not filled in yet. Live sending is blocked while it is present. */
    const val PLACEHOLDER_MARKER = "PLACEHOLDER"

    const val DEFAULT_QUESTIONS =
        "[QUALIFYING QUESTIONS PLACEHOLDER – edit these in Settings before turning test mode off]"

    /**
     * Placeholders:
     *   {first_name}  contact's first name ("there" if blank)
     *   {my_name}     your name from Settings
     *   {when}        "tomorrow" / "this morning" / "today" / "on Monday"
     *   {questions}   the qualifying questions from Settings
     *   {deal_name}   the HubSpot deal name
     */
    const val DEFAULT_TEMPLATE =
        "Hi {first_name}, it's {my_name} from Utemaster. Thanks for sending through your Utebuild! " +
            "I'll call you {when} to go through it.\n\n" +
            "{questions}"

    fun render(
        template: String,
        firstName: String?,
        myName: String,
        whenPhrase: String,
        questions: String,
        dealName: String?,
    ): String {
        val first = firstName?.trim()?.takeIf { it.isNotEmpty() } ?: "there"
        return template
            .replace("{first_name}", first)
            .replace("{my_name}", myName.trim())
            .replace("{when}", whenPhrase)
            .replace("{questions}", questions.trim())
            .replace("{deal_name}", dealName?.trim().orEmpty())
            .trim()
    }

    fun hasPlaceholderQuestions(questions: String) = questions.contains(PLACEHOLDER_MARKER, ignoreCase = true)
}

/** The note written to the HubSpot deal after a successful send. */
object SentNote {

    /** Searched for in existing deal notes to avoid double-texting (backup to the local record). */
    const val MARKER = "[Utebuild auto-text sent]"

    /** Test-mode notes deliberately use a different marker, so they never block a real text. */
    const val TEST_MARKER = "[Utebuild auto-text TEST – sent to my own phone, NOT the customer]"

    private val FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a '(Sydney time)'", Locale.ENGLISH)

    fun html(text: String, to: String, sentAt: Instant, testMode: Boolean): String {
        val header = if (testMode) TEST_MARKER else MARKER
        val time = FORMAT.format(CallTiming.sydneyTime(sentAt))
        return "<p><strong>${escape(header)}</strong></p>" +
            "<p>Sent ${escape(time)} to ${escape(PhoneNumbers.pretty(to))} by SMS from my mobile (Utebuild Texter app).</p>" +
            "<p>${escape(text).replace("\n", "<br>")}</p>"
    }

    fun containsSentMarker(noteBody: String?): Boolean = noteBody?.contains(MARKER) == true

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
