package io.vocaguard

import io.vocaguard.service.PhoneValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases taken from real registrations that ended up unroutable (2026-11). */
class PhoneValidationTest {

    private fun ok(raw: String, want: String, region: String = "IL") {
        val r = PhoneValidation.check(raw, region)
        assertTrue("expected valid: $raw (${r.message})", r.valid)
        assertEquals(want, r.normalized)
    }

    private fun bad(raw: String, region: String = "IL") {
        val r = PhoneValidation.check(raw, region)
        assertFalse("expected invalid: $raw -> ${r.normalized}", r.valid)
        assertNotNull(r.message)
    }

    @Test fun israeliMobileFormats() {
        ok("052-823-3354", "+972528233354")
        ok("0528233354", "+972528233354")
        ok("+972528233354", "+972528233354")
        ok("+972 52 823 3354", "+972528233354")
        ok("00972528233354", "+972528233354")
        ok("(052) 823.3354", "+972528233354")
        ok("‏+972 52-823-3354", "+972528233354")   // RTL mark from a Hebrew keyboard
    }

    @Test fun trunkZeroAfterCountryCodeIsFixed() {
        ok("+972 0523624141", "+972523624141")   // was stored as 9720523624141
        ok("009720523624141", "+972523624141")
        ok("9720523624141", "+972523624141")
    }

    @Test fun realTyposAreFlagged() {
        bad("+97251454701")    // a digit short
        bad("+97258677474")    // a digit short
        bad("051454701")
        bad("")
        bad("abc")
    }

    @Test fun landlineIsFlagged() {
        bad("03-374-1493")     // Tel Aviv landline (our own shared DID)
    }

    @Test fun usNumbersWork() {
        ok("+1 202 456 1111", "+12024561111", region = "US")
        ok("(202) 456-1111", "+12024561111", region = "US")
    }
}
