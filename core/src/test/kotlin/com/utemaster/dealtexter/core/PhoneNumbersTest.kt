package com.utemaster.dealtexter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test fun `australian mobile formats normalise to +61`() {
        for (raw in listOf(
            "0412 345 678", "0412345678", "412345678", "+61412345678", "+61 412 345 678",
            "61412345678", "0061412345678", "+61 (0)412 345 678", "(0412) 345-678", " 0412.345.678 ",
        )) {
            assertEquals(raw, "+61412345678", PhoneNumbers.normalise(raw))
        }
    }

    @Test fun `landlines normalise but are not textable`() {
        val n = PhoneNumbers.normalise("(02) 9876 5432")
        assertEquals("+61298765432", n)
        assertFalse(PhoneNumbers.isTextable(n!!))
    }

    @Test fun `overseas numbers are kept`() {
        assertEquals("+64211234567", PhoneNumbers.normalise("+64 21 123 4567"))
        assertEquals("+64211234567", PhoneNumbers.normalise("0011 64 21 123 4567"))
        assertTrue(PhoneNumbers.isTextable("+64211234567"))
    }

    @Test fun `junk returns null`() {
        for (raw in listOf(null, "", "   ", "n/a", "12345", "04123")) {
            assertNull(raw, PhoneNumbers.normalise(raw))
        }
    }

    @Test fun `pretty prints mobiles`() {
        assertEquals("+61 412 345 678", PhoneNumbers.pretty("+61412345678"))
    }
}
