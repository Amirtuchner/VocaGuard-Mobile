package io.vocaguard.service

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType

/**
 * Soft check for a phone number the user TYPED at registration. Catches the typos
 * that left users unreachable (a digit short, "+972 052…" keeping the trunk 0, a
 * stray leading digit). It never blocks: the UI shows [Result.message] and offers
 * "Use this number anyway", so a reviewer typing a test number can still proceed.
 * Numbers from the SIM or the verification call are trusted and not checked.
 */
object PhoneValidation {

    data class Result(
        val valid: Boolean,
        /** E.164 with "+" when parseable (cleaned up even if invalid), else the trimmed input. */
        val normalized: String,
        /** User-facing explanation when [valid] is false. */
        val message: String? = null
    )

    private val TRUNK_ZERO_AFTER_IL_CC = Regex("^(?:\\+|00)?9720")

    fun check(context: Context, raw: String): Result = check(raw, simRegion(context))

    /** [region] = ISO country used for numbers typed without a country code. */
    fun check(raw: String, region: String): Result {
        val trimmed = raw.trim()
        // Keep digits and a leading "+" only (drops spaces, dashes, brackets, bidi marks).
        var s = trimmed.filterIndexed { i, c -> c.isDigit() || (c == '+' && i == trimmed.indexOf('+')) }
        // "+972 052…" → "+97252…": the hint says "include your country code" and many
        // Israelis keep the 0 as well.
        if (TRUNK_ZERO_AFTER_IL_CC.containsMatchIn(s)) s = TRUNK_ZERO_AFTER_IL_CC.replace(s, "+972")
        if (s.isEmpty()) return Result(false, trimmed, "Enter your phone number.")

        val util = PhoneNumberUtil.getInstance()
        val parsed = try {
            util.parse(s, region)
        } catch (_: NumberParseException) {
            return Result(false, s, invalidMessage(region))
        }
        val e164 = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        if (!util.isValidNumber(parsed)) return Result(false, e164, invalidMessage(region))
        // Forwarding is set on the handset, so it must be a mobile (US/CA numbers
        // can't be told apart → FIXED_LINE_OR_MOBILE is accepted).
        val type = util.getNumberType(parsed)
        if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) {
            return Result(false, e164,
                "This looks like a landline. Enter the mobile number of this phone.")
        }
        return Result(true, e164)
    }

    private fun invalidMessage(region: String): String =
        if (region == "US" || region == "CA")
            "This doesn't look like a valid phone number. Check the digits and the country code (e.g. +1 202 555 1234)."
        else
            "This doesn't look like a valid mobile number. Check the digits and the country code (e.g. +972 50 123 4567)."

    /** SIM / network country, defaulting to Israel (where VocaGuard's numbers are). */
    private fun simRegion(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return tm?.simCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
            ?: "IL"
    }
}
