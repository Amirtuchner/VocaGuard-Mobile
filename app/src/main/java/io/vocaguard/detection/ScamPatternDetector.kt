package io.vocaguard.detection

import android.util.Log
import io.vocaguard.data.ScamType

class ScamPatternDetector() {

    companion object {
        private const val TAG = "ScamPatternDetector"
        private const val CONFIDENCE_THRESHOLD = 0.72f
        private const val MIN_KEYWORD_MATCHES = 2

        // Scam pattern keywords organized by type.
        // Only multi-word or highly specific phrases — single generic words removed to cut false positives.
        private val SCAM_PATTERNS = mapOf(
            ScamType.IRS_SCAM to listOf(
                // English
                "IRS", "internal revenue", "owe money", "arrest warrant",
                "legal action", "tax refund", "tax fraud", "tax lien", "back taxes",
                // Russian
                "налоговая служба", "налоговый долг", "задолженность по налогам", "фнс",
                // Hebrew
                "מס הכנסה", "רשות המסים", "חוב מס", "עיקול מס",
                // Arabic
                "مصلحة الضرائب", "ديون ضريبية", "مديونية ضريبية",
                // Spanish
                "servicio de impuestos", "deuda fiscal", "agencia tributaria", "hacienda pública",
                // French
                "service des impôts", "dette fiscale", "direction générale des finances publiques"
            ),
            ScamType.TECH_SUPPORT to listOf(
                // English
                "computer virus", "microsoft support", "windows support", "technical support",
                "your computer", "malware detected", "remote access", "teamviewer",
                "tech support", "apple support", "google support", "anydesk",
                "your device is infected", "suspicious activity on your computer",
                // Russian
                "техподдержка", "удалённый доступ", "ваш компьютер взломан", "заражён вирусом",
                // Hebrew
                "תמיכה טכנית", "גישה מרחוק", "תוכנה זדונית", "המחשב שלך נגוע",
                // Arabic
                "دعم فني", "وصول عن بعد", "برامج خبيثة", "جهازك مخترق",
                // Spanish
                "soporte técnico", "acceso remoto", "virus informático", "software malicioso",
                // French
                "support technique", "accès à distance", "virus informatique", "logiciel malveillant"
            ),
            ScamType.BANK_FRAUD to listOf(
                // English
                "verify your account", "unusual activity", "fraud alert",
                "unauthorized transaction", "account locked", "security breach",
                "confirm your identity", "anydesk", "your account has been",
                "suspicious transaction", "your card has been",
                // Russian
                "счёт заблокирован", "подозрительная активность", "несанкционированная операция",
                // Hebrew
                "חשבון חסום", "פעילות חשודה", "עסקה לא מאושרת", "אמת את זהותך",
                // Arabic
                "نشاط مشبوه", "معاملة غير مصرح بها", "حسابك محظور",
                // Spanish
                "actividad sospechosa", "fraude bancario", "cuenta bloqueada", "transacción no autorizada",
                // French
                "activité suspecte", "fraude bancaire", "compte bloqué", "transaction non autorisée"
            ),
            ScamType.LOTTERY_PRIZE to listOf(
                // English
                "lottery", "sweepstakes", "claim your prize", "free vacation",
                "free cruise", "cash prize", "you have been selected",
                "you are our winner", "collect your winnings",
                // Russian
                "лотерея", "вы выиграли приз", "получите выигрыш",
                // Hebrew
                "הגרלה", "זכית בפרס", "תבע את הפרס שלך",
                // Arabic
                "يانصيب", "فزت بجائزة", "استلم جائزتك",
                // Spanish
                "lotería", "reclamar su premio", "usted ha ganado", "cobrar su premio",
                // French
                "loterie", "réclamez votre prix", "vous avez gagné", "encaissez vos gains"
            ),
            ScamType.SOCIAL_SECURITY to listOf(
                // English
                "social security", "SSN", "social security number",
                "illegal activity", "social security administration",
                "your SSN has been", "social security suspended",
                // Russian
                "страховой номер снилс", "пенсионный фонд заблокирован", "паспортные данные украдены",
                // Hebrew
                "מספר ביטוח לאומי", "ת.ז חסומה", "פרטים אישיים נגנבו",
                // Arabic
                "رقم الضمان الاجتماعي", "الهوية الوطنية محظورة",
                // Spanish
                "número de seguridad social", "actividad ilegal detectada", "documentos de identidad suspendidos",
                // French
                "numéro de sécurité sociale", "activité illégale détectée", "pièce d'identité suspendue"
            ),
            ScamType.ROBOCALL to listOf(
                // English
                "this is a recorded message", "do not hang up", "press 1",
                "call back immediately", "final notice", "this call may be recorded",
                "you have been selected", "respond to this message",
                // Russian
                "нажмите один", "нажмите 1", "это записанное сообщение",
                // Hebrew
                "לחץ 1", "הודעה מוקלטת", "הודעה אוטומטית",
                // Arabic
                "اضغط 1", "رسالة مسجلة", "لا تغلق الخط",
                // Spanish
                "presione 1", "mensaje grabado", "no cuelgue", "aviso final",
                // French
                "appuyez sur 1", "message enregistré", "ne raccrochez pas", "avis final"
            ),
            ScamType.PHISHING to listOf(
                // English
                "update your information", "account verification",
                "password reset", "click the link", "provide your details",
                "verify your identity", "confirm your account", "log in immediately",
                // Russian
                "обновите данные", "подтвердите свою личность", "войдите в систему немедленно",
                // Hebrew
                "עדכן פרטים", "אמת את זהותך", "לחץ כאן לאישור",
                // Arabic
                "تحديث معلوماتك", "تأكيد هويتك", "انقر هنا للتحقق",
                // Spanish
                "actualice su información", "verifique su identidad", "haga clic en el enlace",
                // French
                "mettez à jour vos informations", "vérifiez votre identité", "cliquez sur le lien"
            ),
            ScamType.INSURANCE to listOf(
                // English
                "health insurance", "medicare", "medicaid", "insurance plan",
                "free insurance", "limited time offer", "you qualify for coverage",
                "insurance expires", "your coverage will end",
                // Russian
                "медицинская страховка", "ваш полис истекает",
                // Hebrew
                "ביטוח בריאות", "הפוליסה שלך פגה", "כיסוי ביטוחי",
                // Arabic
                "تأمين صحي", "وثيقة تأمين", "تغطية تأمينية",
                // Spanish
                "seguro de salud", "cobertura médica", "póliza de seguro", "seguro médico",
                // French
                "assurance maladie", "assurance santé", "couverture médicale", "mutuelle"
            ),
            ScamType.INVESTMENT_SCAM to listOf(
                // English
                "guaranteed returns", "high returns", "risk free", "double your money",
                "investment opportunity", "limited slots", "exclusive offer",
                "bitcoin investment", "trading platform", "passive income",
                "financial freedom", "get rich quick", "insider tip", "secret strategy",
                // Russian
                "гарантированный доход", "криптовалютные инвестиции", "пассивный доход без риска",
                // Hebrew
                "תשואה מובטחת", "השקעה בקריפטו", "הכפלת כסף", "פורקס",
                // Arabic
                "عائد مضمون", "استثمار بعملة مشفرة", "مضاعفة الأموال",
                // Spanish
                "inversión garantizada", "ganancias altas sin riesgo", "criptomoneda",
                // French
                "investissement garanti", "rendements élevés sans risque", "cryptomonnaie"
            ),
            ScamType.DONATION_FRAUD to listOf(
                // English
                "help victims", "disaster relief", "make a contribution",
                "support our cause", "relief fund", "tax deductible donation",
                "donate now to help", "emergency fundraising",
                // Russian
                "гуманитарная помощь пострадавшим", "пожертвуйте сейчас",
                // Hebrew
                "קרן סיוע לנפגעים", "לתרום עכשיו", "עמותה לסיוע",
                // Arabic
                "إغاثة المتضررين", "تبرع الآن", "منظمة إغاثة",
                // Spanish
                "ayuda a las víctimas", "donación de emergencia", "fondo de socorro",
                // French
                "aide aux victimes", "don d'urgence", "fonds de secours"
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
            "عاجل", "الآن", "فوراً", "فرصة أخيرة", "خلال 24 ساعة",
            // Spanish
            "urgente", "inmediatamente", "ahora mismo", "última oportunidad", "solo hoy", "tiempo limitado",
            // French
            "immédiatement", "maintenant", "dernière chance", "aujourd'hui seulement", "temps limité"
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
            "اعتقال", "شرطة", "قضية جنائية", "محظور", "مجمد",
            // Spanish
            "arresto", "policía", "demanda", "acción legal", "suspendido", "congelado", "orden de arresto",
            // French
            "arrestation", "police", "poursuites judiciaires", "suspendu", "gelé", "mandat d'arrêt"
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
            "بطاقة هدية", "بيتكوين", "تحويل مالي", "ويسترن يونيون",
            // Spanish
            "tarjeta de regalo", "transferencia bancaria", "western union", "pague ahora",
            // French
            "carte cadeau", "virement bancaire", "western union", "payez maintenant"
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
            "كلمة المرور", "رقم الحساب", "بطاقة هوية",
            // Spanish
            "número de seguridad social", "contraseña", "número de cuenta", "fecha de nacimiento", "pin",
            // French
            "numéro de sécurité sociale", "mot de passe", "numéro de compte", "date de naissance", "code pin"
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

        // Boost signals — computed once but only applied when keywords are matched (boost guard)
        val hasUrgency = URGENCY_KEYWORDS.any { containsWord(lowerText, it) }
        val hasThreat  = THREAT_KEYWORDS.any  { containsWord(lowerText, it) }
        val hasPayment = PAYMENT_KEYWORDS.any  { containsWord(lowerText, it) }
        val hasInfo    = INFO_REQUEST_KEYWORDS.any { containsWord(lowerText, it) }

        // Score every scam type and keep the best match
        var bestType: ScamType = ScamType.UNKNOWN
        var bestConfidence = 0f
        var bestKeywords: List<String> = emptyList()

        for ((scamType, keywords) in SCAM_PATTERNS) {
            val matched = keywords.filter { containsWord(lowerText, it) }

            // Boost guard: require at least MIN_KEYWORD_MATCHES before boosts apply
            if (matched.size < MIN_KEYWORD_MATCHES) continue

            val keywordScore = (matched.size * 0.12f).coerceAtMost(0.60f)

            // Boosts only fire because keywords already matched — no free boost from tone alone
            val urgencyBoost = if (hasUrgency) 0.15f else 0f
            val threatBoost  = if (hasThreat)  0.20f else 0f
            val paymentBoost = if (hasPayment) 0.15f else 0f
            val infoBoost    = if (hasInfo)    0.10f else 0f

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

        // Last resort: all 4 signal categories must be simultaneously present
        val suspiciousScore = calculateSuspiciousScore(lowerText)
        if (suspiciousScore >= CONFIDENCE_THRESHOLD) {
            return DetectionResult(
                isScam = true,
                scamType = ScamType.UNKNOWN,
                confidence = suspiciousScore,
                reason = "Multiple simultaneous scam signals detected"
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
        val urgencyCount = URGENCY_KEYWORDS.count { containsWord(text, it) }
        val threatCount  = THREAT_KEYWORDS.count  { containsWord(text, it) }
        val paymentCount = PAYMENT_KEYWORDS.count  { containsWord(text, it) }
        val infoCount    = INFO_REQUEST_KEYWORDS.count { containsWord(text, it) }

        // All 4 signal categories must be present — prevents false positives from tone alone
        val categoriesPresent = listOf(urgencyCount, threatCount, paymentCount, infoCount)
            .count { it > 0 }
        if (categoriesPresent < 4) return 0f

        var score = 0f
        score += urgencyCount * 0.12f
        score += threatCount  * 0.18f
        score += paymentCount * 0.15f
        score += infoCount    * 0.20f

        return score.coerceAtMost(1.0f)
    }

}
