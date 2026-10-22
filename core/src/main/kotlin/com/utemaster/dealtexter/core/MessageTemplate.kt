package com.utemaster.dealtexter.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object MessageTemplate {

    /**
     * Placeholder wording – edit in Settings.
     *   {first_name}  contact's first name ("there" if blank)
     *   {my_name}     your name from Settings
     */
    const val DEFAULT_TEMPLATE =
        "Hey {first_name}, I noticed you were on our website. I'll give you a call next time " +
            "I'm in the office to provide a consultation. - {my_name}, Utemaster"

    fun render(template: String, firstName: String?, myName: String): String {
        val first = firstName?.trim()?.takeIf { it.isNotEmpty() } ?: "there"
        return template
            .replace("{first_name}", first)
            .replace("{my_name}", myName.trim())
            .trim()
    }
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
        val time = FORMAT.format(WorkHours.sydneyTime(sentAt))
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
