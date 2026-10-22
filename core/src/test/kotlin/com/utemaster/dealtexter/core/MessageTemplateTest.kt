package com.utemaster.dealtexter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MessageTemplateTest {

    @Test fun `default template identifies me and Utemaster`() {
        val text = MessageTemplate.render(
            MessageTemplate.DEFAULT_TEMPLATE, "Sam", "Cooper", "tomorrow", "Q1?\nQ2?", "Sam's Hilux",
        )
        assertEquals(
            "Hi Sam, it's Cooper from Utemaster. Thanks for sending through your Utebuild! " +
                "I'll call you tomorrow to go through it.\n\nQ1?\nQ2?",
            text,
        )
    }

    @Test fun `missing first name falls back to there`() {
        val text = MessageTemplate.render("Hi {first_name}, {when}", "  ", "Cooper", "on Monday", "", null)
        assertEquals("Hi there, on Monday", text)
    }

    @Test fun `placeholder questions are detected`() {
        assertTrue(MessageTemplate.hasPlaceholderQuestions(MessageTemplate.DEFAULT_QUESTIONS))
        assertFalse(MessageTemplate.hasPlaceholderQuestions("What vehicle is it going on?"))
    }

    @Test fun `note contains marker, exact text and escapes html`() {
        val html = SentNote.html("Hi <Sam> & co\nline 2", "+61412345678", Instant.parse("2026-10-23T05:00:00Z"), false)
        assertTrue(SentNote.containsSentMarker(html))
        assertTrue(html.contains("Hi &lt;Sam&gt; &amp; co<br>line 2"))
        assertTrue(html.contains("Fri 23 Oct 2026, 4:00 PM (Sydney time)"))
        assertTrue(html.contains("+61 412 345 678"))
    }

    @Test fun `test mode note does not count as sent`() {
        val html = SentNote.html("x", "+61412345678", Instant.now(), true)
        assertFalse(SentNote.containsSentMarker(html))
    }
}
