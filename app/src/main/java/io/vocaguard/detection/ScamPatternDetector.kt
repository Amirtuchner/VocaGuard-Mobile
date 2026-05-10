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
                // English
                "IRS", "internal revenue", "tax", "owe money", "arrest warrant",
                "legal action", "tax refund", "tax fraud", "tax lien",
                // Russian
                "налоговая", "налоговый долг", "задолженность", "фнс",
                // Hebrew
                "מס הכנסה", "רשות המסים", "חוב מס", "עיקול מס",
                // Arabic
                "مصلحة الضرائب", "ديون ضريبية", "الضريبة"
            ),
            ScamType.TECH_SUPPORT to listOf(
                // English
                "computer virus", "microsoft support", "windows support", "technical support",
                "your computer", "infected", "malware", "remote access", "teamviewer",
                "tech support", "apple support", "google support", "anydesk",
                // Russian
                "вирус", "техподдержка", "удалённый доступ", "взломан", "заражён",
                // Hebrew
                "וירוס", "תמיכה טכנית", "גישה מרחוק", "תוכנה זדונית", "נגוע",
                // Arabic
                "فيروس", "دعم فني", "وصول عن بعد", "برامج خبيثة"
            ),
            ScamType.BANK_FRAUD to listOf(
                // English
                "bank account", "credit card", "suspended", "verify your account",
                "unusual activity", "fraud alert", "unauthorized transaction",
                "account locked", "security breach", "confirm your identity", "anydesk",
                // Russian
                "банк", "кредитная карта", "счёт заблокирован", "подозрительная активность",
                // Hebrew
                "חשבון בנק", "כרטיס אשראי", "חשבון חסום", "פעילות חשודה", "הונאה",
                // Arabic
                "حساب بنكي", "بطاقة ائتمان", "نشاط مشبوه", "احتيال"
            ),
            ScamType.LOTTERY_PRIZE to listOf(
                // English
                "won", "lottery", "prize", "sweepstakes", "winner", "congratulations",
                "claim your prize", "free vacation", "free cruise", "cash prize",
                // Russian
                "выиграли", "лотерея", "приз", "поздравляем", "победитель",
                // Hebrew
                "זכית", "הגרלה", "פרס", "מזל טוב", "זוכה",
                // Arabic
                "فزت", "يانصيب", "جائزة", "مبروك", "فائز"
            ),
            ScamType.SOCIAL_SECURITY to listOf(
                // English
                "social security", "SSN", "social security number", "suspended",
                "compromised", "illegal activity", "social security administration",
                // Russian
                "снилс", "пенсионный фонд", "страховой номер", "паспортные данные",
                // Hebrew
                "תעודת זהות", "מספר ביטוח לאומי", "פרטים אישיים", "ת.ז",
                // Arabic
                "رقم الهوية", "الهوية الوطنية", "رقم الضمان الاجتماعي"
            ),
            ScamType.ROBOCALL to listOf(
                // English
                "this is a recorded message", "do not hang up", "press 1",
                "call back immediately", "final notice", "last chance",
                // Russian
                "нажмите один", "нажмите 1", "записанное сообщение",
                // Hebrew
                "לחץ 1", "הודעה מוקלטת", "הודעה אוטומטית",
                // Arabic
                "اضغط 1", "رسالة مسجلة", "لا تغلق"
            ),
            ScamType.PHISHING to listOf(
                // English
                "verify", "confirm", "update your information", "account verification",
                "password reset", "click the link", "provide your",
                // Russian
                "подтвердите", "обновите данные", "войдите в систему", "ссылка",
                // Hebrew
                "אמת", "לחץ כאן", "קישור", "עדכן פרטים", "אישור",
                // Arabic
                "تحقق", "انقر هنا", "رابط", "تأكيد"
            ),
            ScamType.INSURANCE to listOf(
                // English
                "health insurance", "medicare", "medicaid", "insurance plan",
                "free insurance", "qualify for", "limited time offer",
                // Russian
                "страховка", "медицинская страховка", "полис",
                // Hebrew
                "ביטוח בריאות", "פוליסה", "כיסוי ביטוחי",
                // Arabic
                "تأمين صحي", "وثيقة تأمين", "تغطية تأمينية"
            ),
            ScamType.INVESTMENT_SCAM to listOf(
                // English
                "guaranteed returns", "high returns", "risk free", "double your money",
                "investment opportunity", "limited slots", "exclusive offer", "crypto",
                "bitcoin investment", "forex", "trading platform", "passive income",
                "financial freedom", "get rich", "insider tip", "secret strategy", "anydesk",
                // Russian
                "инвестиции", "гарантированный доход", "криптовалюта", "пассивный доход",
                // Hebrew
                "השקעה", "תשואה מובטחת", "קריפטו", "הכפלת כסף", "פורקס",
                // Arabic
                "استثمار", "عائد مضمون", "عملة مشفرة", "بيتكوين", "تداول"
            ),
            ScamType.DONATION_FRAUD to listOf(
                // English
                "charity", "donate", "donation", "help victims", "disaster relief",
                "make a contribution", "support our cause", "relief fund",
                "humanitarian", "tax deductible donation", "nonprofit", "fundraising",
                // Russian
                "благотворительность", "пожертвование", "гуманитарная помощь",
                // Hebrew
                "צדקה", "תרומה", "לתרום", "עמותה", "קרן סיוע",
                // Arabic
                "خيرية", "تبرع", "إغاثة", "منظمة غير ربحية"
            )
        )

        // High-priority urgent language patterns
        private val URGENCY_KEYWORDS = listOf(
            // English
            "immediately", "urgent", "right now", "within 24 hours",
            "today only", "expires today", "last chance", "final notice",
            "act now", "time sensitive", "limited time",
            // Russian
            "срочно", "немедленно", "последний шанс", "действуйте сейчас",
            // Hebrew
            "דחוף", "עכשיו", "מיד", "הזדמנות אחרונה", "תוך 24 שעות",
            // Arabic
            "عاجل", "الآن", "فوراً", "فرصة أخيرة", "خلال 24 ساعة"
        )

        // Threat/pressure keywords
        private val THREAT_KEYWORDS = listOf(
            // English
            "arrest", "police", "lawsuit", "legal action", "suspended",
            "cancelled", "locked", "frozen", "seized", "warrant",
            // Russian
            "арест", "полиция", "уголовное дело", "заблокирован", "заморожен",
            // Hebrew
            "מעצר", "משטרה", "תיק פלילי", "חסום", "מוקפא", "עיקול",
            // Arabic
            "اعتقال", "شرطة", "قضية جنائية", "محظور", "مجمد"
        )

        // Payment request keywords
        private val PAYMENT_KEYWORDS = listOf(
            // English
            "gift card", "bitcoin", "wire transfer", "cash", "prepaid card",
            "money order", "western union", "zelle", "venmo", "cashapp",
            "pay now", "payment required", "send money",
            // Russian
            "подарочная карта", "биткоин", "перевод средств", "вестерн юнион",
            // Hebrew
            "גיפט קארד", "ביטקוין", "העברה בנקאית", "ביט", "פייבוקס",
            // Arabic
            "بطاقة هدية", "بيتكوين", "تحويل مالي", "ويسترن يونيون"
        )

        // Personal info request keywords
        private val INFO_REQUEST_KEYWORDS = listOf(
            // English
            "social security number", "SSN", "date of birth", "mother's maiden name",
            "bank account number", "credit card number", "PIN", "password",
            "routing number", "account number",
            // Russian
            "пароль", "пин-код", "номер счёта", "паспортные данные",
            // Hebrew
            "סיסמה", "פין", "מספר חשבון", "תעודת זהות",
            // Arabic
            "كلمة المرور", "رقم الحساب", "بطاقة هوية"
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
