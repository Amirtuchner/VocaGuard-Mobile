package io.vocaguard.detection

import android.util.Log
import io.vocaguard.data.ScamType

class ScamPatternDetector() {

    companion object {
        private const val TAG = "ScamPatternDetector"
        private const val CONFIDENCE_THRESHOLD = 0.6f

        // Scam pattern keywords organized by type
        private val SCAM_PATTERNS = mapOf(
            ScamType.IRS_SCAM to listOf(
                "IRS", "internal revenue", "tax", "owe money", "arrest warrant",
                "legal action", "tax refund", "tax fraud", "tax lien"
            ),
            ScamType.TECH_SUPPORT to listOf(
                "computer virus", "microsoft support", "windows support", "technical support",
                "your computer", "infected", "malware", "remote access", "teamviewer",
                "tech support", "apple support", "google support", "anydesk"
            ),
            ScamType.BANK_FRAUD to listOf(
                "bank account", "credit card", "suspended", "verify your account",
                "unusual activity", "fraud alert", "unauthorized transaction",
                "account locked", "security breach", "confirm your identity", "anydesk"
            ),
            ScamType.LOTTERY_PRIZE to listOf(
                "won", "lottery", "prize", "sweepstakes", "winner", "congratulations",
                "claim your prize", "free vacation", "free cruise", "cash prize"
            ),
            ScamType.SOCIAL_SECURITY to listOf(
                "social security", "SSN", "social security number", "suspended",
                "compromised", "illegal activity", "social security administration"
            ),
            ScamType.ROBOCALL to listOf(
                "this is a recorded message", "do not hang up", "press 1",
                "call back immediately", "final notice", "last chance"
            ),
            ScamType.PHISHING to listOf(
                "verify", "confirm", "update your information", "account verification",
                "password reset", "click the link", "provide your"
            ),
            ScamType.INSURANCE to listOf(
                "health insurance", "medicare", "medicaid", "insurance plan",
                "free insurance", "qualify for", "limited time offer"
            ),
            ScamType.INVESTMENT_SCAM to listOf(
                "guaranteed returns", "high returns", "risk free", "double your money",
                "investment opportunity", "limited slots", "exclusive offer", "crypto",
                "bitcoin investment", "forex", "trading platform", "passive income",
                "financial freedom", "get rich", "insider tip", "secret strategy", "anydesk"
            ),
            ScamType.DONATION_FRAUD to listOf(
                "charity", "donate", "donation", "help victims", "disaster relief",
                "make a contribution", "support our cause", "relief fund",
                "humanitarian", "tax deductible donation", "nonprofit", "fundraising"
            )
        )

        // High-priority urgent language patterns
        private val URGENCY_KEYWORDS = listOf(
            "immediately", "urgent", "right now", "within 24 hours",
            "today only", "expires today", "last chance", "final notice",
            "act now", "time sensitive", "limited time"
        )

        // Threat/pressure keywords
        private val THREAT_KEYWORDS = listOf(
            "arrest", "police", "lawsuit", "legal action", "suspended",
            "cancelled", "locked", "frozen", "seized", "warrant"
        )

        // Payment request keywords
        private val PAYMENT_KEYWORDS = listOf(
            "gift card", "bitcoin", "wire transfer", "cash", "prepaid card",
            "money order", "western union", "zelle", "venmo", "cashapp",
            "pay now", "payment required", "send money"
        )

        // Personal info request keywords
        private val INFO_REQUEST_KEYWORDS = listOf(
            "social security number", "SSN", "date of birth", "mother's maiden name",
            "bank account number", "credit card number", "PIN", "password",
            "routing number", "account number"
        )
    }

    /**
     * Returns true if [text] contains [keyword] as a whole word (word-boundary match,
     * case-insensitive). Prevents false positives like "bitcoin" inside "habitcoin".
     */
    private fun containsWord(text: String, keyword: String): Boolean {
        val pattern = "(?<![\\w])${Regex.escape(keyword.lowercase())}(?![\\w])"
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    fun analyzeText(text: String): DetectionResult {
        val lowerText = text.lowercase()

        // Shared signal boosts (computed once)
        val urgencyBoost = if (URGENCY_KEYWORDS.any { containsWord(lowerText, it) }) 0.2f else 0f
        val threatBoost  = if (THREAT_KEYWORDS.any  { containsWord(lowerText, it) }) 0.25f else 0f
        val paymentBoost = if (PAYMENT_KEYWORDS.any  { containsWord(lowerText, it) }) 0.2f else 0f
        val infoBoost    = if (INFO_REQUEST_KEYWORDS.any { containsWord(lowerText, it) }) 0.15f else 0f

        // Score every scam type and keep the best match
        var bestType: ScamType = ScamType.UNKNOWN
        var bestConfidence = 0f
        var bestKeywords: List<String> = emptyList()

        for ((scamType, keywords) in SCAM_PATTERNS) {
            val matched = keywords.filter { containsWord(lowerText, it) }
            if (matched.isEmpty()) continue

            // Each matched keyword contributes a fixed weight — prevents bias toward
            // categories with fewer keywords (e.g. ROBOCALL has 6, TECH_SUPPORT has 14).
            val keywordScore = (matched.size * 0.12f).coerceAtMost(0.60f)
            val confidence = (keywordScore + urgencyBoost + threatBoost + paymentBoost + infoBoost)
                .coerceAtMost(1.0f)

            if (confidence > bestConfidence) {
                bestConfidence = confidence
                bestType = scamType
                bestKeywords = matched
            }
        }

        if (bestConfidence >= CONFIDENCE_THRESHOLD) {
            Log.w(TAG, "Scam detected: $bestType (confidence: $bestConfidence)")
            return DetectionResult(
                isScam = true,
                scamType = bestType,
                confidence = bestConfidence,
                reason = "Detected ${bestKeywords.size} scam keywords: ${bestKeywords.take(3).joinToString(", ")}",
                keywords = bestKeywords
            )
        }

        // Check for generic suspicious patterns even if no specific type matched
        val suspiciousScore = calculateSuspiciousScore(lowerText)
        if (suspiciousScore >= CONFIDENCE_THRESHOLD) {
            return DetectionResult(
                isScam = true,
                scamType = ScamType.UNKNOWN,
                confidence = suspiciousScore,
                reason = "High suspicious activity score"
            )
        }

        return DetectionResult(
            isScam = false,
            scamType = ScamType.UNKNOWN,
            confidence = 0f,
            reason = "No scam patterns detected"
        )
    }

    private fun calculateSuspiciousScore(text: String): Float {
        var score = 0f

        // Check for multiple red flags
        val urgencyCount = URGENCY_KEYWORDS.count { containsWord(text, it) }
        val threatCount = THREAT_KEYWORDS.count { containsWord(text, it) }
        val paymentCount = PAYMENT_KEYWORDS.count { containsWord(text, it) }
        val infoRequestCount = INFO_REQUEST_KEYWORDS.count { containsWord(text, it) }

        // Weight different factors
        score += urgencyCount * 0.15f
        score += threatCount * 0.25f
        score += paymentCount * 0.2f
        score += infoRequestCount * 0.3f

        // If multiple categories are present, it's more suspicious
        val categoriesPresent = listOf(urgencyCount, threatCount, paymentCount, infoRequestCount)
            .count { it > 0 }

        if (categoriesPresent >= 3) {
            score += 0.3f
        } else if (categoriesPresent >= 2) {
            score += 0.15f
        }

        return score.coerceAtMost(1.0f)
    }

}
