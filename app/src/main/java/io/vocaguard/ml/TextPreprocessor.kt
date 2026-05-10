package io.vocaguard.ml

import android.util.Log

class TextPreprocessor {

    companion object {
        private const val TAG = "TextPreprocessor"
        private const val MAX_SEQUENCE_LENGTH = 128
        private const val VOCAB_SIZE = 10000

        /**
         * Bump this constant AND the matching version in train_model.py whenever
         * [extractFeatures] changes. A mismatch means the Android features no longer
         * align with the TFLite model's input expectations and predictions will be wrong.
         */
        const val FEATURE_VERSION = 3
        const val EXPECTED_FEATURE_COUNT = 42

        // Common stop words to remove
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "can", "i", "you", "he", "she",
            "it", "we", "they", "me", "him", "her", "us", "them", "my", "your",
            "his", "her", "its", "our", "their", "this", "that", "these", "those"
        )
    }

    private val vocabulary = mutableMapOf<String, Int>()
    private var isInitialized = false

    init {
        buildVocabulary()
    }

    private fun buildVocabulary() {
        // Build a simple vocabulary based on common words and scam keywords
        val commonWords = listOf(
            "call", "phone", "number", "account", "bank", "card", "credit", "social",
            "security", "irs", "tax", "fraud", "scam", "money", "payment", "urgent",
            "immediate", "now", "today", "suspended", "locked", "verify", "confirm",
            "computer", "virus", "windows", "microsoft", "apple", "support", "technical",
            "tech", "infected", "malware", "fix", "repair", "access", "remote",
            "teamviewer", "anydesk", "password", "username", "pin", "code", "verify",
            "lottery", "prize", "won", "winner", "congratulations", "free", "claim",
            "refund", "arrest", "warrant", "police", "lawsuit", "legal", "action",
            "lawsuit", "court", "judge", "gift", "card", "bitcoin", "wire", "transfer",
            "western", "union", "paypal", "venmo", "zelle", "cashapp", "donation",
            "charity", "help", "victims", "disaster", "relief", "contribute",
            "insurance", "medicare", "medicaid", "health", "qualify", "eligible",
            "robocall", "recorded", "message", "press", "one", "callback"
        )

        vocabulary["<PAD>"] = 0
        vocabulary["<UNK>"] = 1
        vocabulary["<START>"] = 2
        vocabulary["<END>"] = 3

        var index = 4
        for (word in commonWords) {
            vocabulary[word.lowercase()] = index++
        }

        isInitialized = true
        Log.d(TAG, "Vocabulary initialized with ${vocabulary.size} words")
    }

    fun preprocessText(text: String): IntArray {
        val cleanedText = cleanText(text)
        val tokens = tokenize(cleanedText)
        val sequence = tokensToSequence(tokens)
        return padSequence(sequence)
    }

    private fun cleanText(text: String): String {
        // Convert to lowercase and remove special characters
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(text: String): List<String> {
        return text.split(" ")
            .filter { it.isNotEmpty() && !STOP_WORDS.contains(it) }
    }

    private fun tokensToSequence(tokens: List<String>): List<Int> {
        val sequence = mutableListOf<Int>()
        sequence.add(vocabulary["<START>"] ?: 2)

        for (token in tokens.take(MAX_SEQUENCE_LENGTH - 2)) {
            val index = vocabulary[token] ?: vocabulary["<UNK>"] ?: 1
            sequence.add(index)
        }

        sequence.add(vocabulary["<END>"] ?: 3)
        return sequence
    }

    private fun padSequence(sequence: List<Int>): IntArray {
        val padded = IntArray(MAX_SEQUENCE_LENGTH)
        val padValue = vocabulary["<PAD>"] ?: 0

        // Fill with padding
        padded.fill(padValue)

        // Copy sequence
        val copyLength = minOf(sequence.size, MAX_SEQUENCE_LENGTH)
        for (i in 0 until copyLength) {
            padded[i] = sequence[i]
        }

        return padded
    }

    fun extractFeatures(text: String, context: CallContext? = null): FloatArray {
        // Extract 37 numerical features for the ML model.
        // Must exactly match extract_features() in train_model.py.
        val features = mutableListOf<Float>()
        val t = text.lowercase()
        val len = text.length.coerceAtLeast(1)

        fun flag(vararg keywords: String): Float =
            if (keywords.any { t.contains(it) }) 1f else 0f

        // Features 1-6: Text statistics
        features.add((text.length.toFloat() / 1000f).coerceAtMost(1f))
        features.add((text.split(" ").count { it.isNotEmpty() }.toFloat() / 100f).coerceAtMost(1f))
        features.add(text.count { it.isDigit() }.toFloat() / len)
        features.add(text.count { it == '!' }.toFloat() / len)
        features.add(text.count { it == '?' }.toFloat() / len)
        features.add(text.count { it.isUpperCase() }.toFloat() / len)

        // Features 7-10: Generic scam signals
        features.add(flag(
            "urgent", "immediately", "right now", "at once",
            // Russian
            "срочно", "немедленно", "сейчас же", "прямо сейчас", "незамедлительно",
            // Hebrew
            "עכשיו", "מיד", "דחוף", "בדחיפות", "תוך שעה", "ללא דיחוי", "מהר",
            // Arabic
            "الآن", "عاجل", "فوراً", "على الفور", "بسرعة"))
        features.add(flag(
            "suspended", "locked", "frozen", "blocked",
            // Russian
            "заблокирован", "заморожен", "приостановлен", "закрыт",
            // Hebrew
            "חסום", "נחסם", "הוקפא", "מוקפא", "מושעה", "נחסמה", "הוקפאה", "נחסמת",
            // Arabic
            "محظور", "مجمد", "معلق", "موقوف", "مغلق"))
        features.add(flag(
            "verify", "confirm", "validate",
            // Russian
            "подтвердите", "верифицируйте", "проверьте", "подтверждение",
            // Hebrew
            "אמת", "אימות", "לאמת", "לאשר", "לוודא", "אישור", "קוד אימות", "קוד otp",
            // Arabic
            "تحقق", "أكد", "تأكيد", "التحقق", "رمز التحقق", "رمز otp"))
        features.add(flag(
            "money", "payment", "funds", "cash", "pay",
            // Russian
            "деньги", "оплата", "средства", "перевод", "платёж", "платеж",
            // Hebrew
            "כסף", "תשלום", "העברה", "לשלם", "ביט", "פייבוקס", "מזומן", "העברת כסף",
            // Arabic
            "المال", "دفع", "تحويل", "مبلغ", "أموال"))

        // Features 11-19: Category-specific keywords
        features.add(flag(
            "irs", "internal revenue", "tax department", "tax debt", "back taxes", "unpaid tax",
            // Russian
            "налоговая", "налоги", "налоговый долг", "задолженность", "налоговая служба", "фнс",
            // Hebrew
            "מס הכנסה", "רשות המסים", "חוב מס", "חוב לרשות", "עיקול מס",
            // Arabic
            "الضريبة", "مصلحة الضرائب", "ديون ضريبية", "الإيرادات الداخلية"))
        features.add(flag(
            "arrest", "warrant", "jail", "prison", "prosecution",
            "charges", "law enforcement", "officer",
            // Russian
            "арест", "ордер на арест", "тюрьма", "уголовное дело", "полиция",
            "следствие", "обвинение", "прокуратура",
            // Hebrew
            "מעצר", "צו מעצר", "תיק פלילי", "כליאה", "עצור", "תביעה פלילית",
            "הוצאה לפועל", "עיקול", "צו", "חקירה",
            // Arabic
            "اعتقال", "مذكرة اعتقال", "قضية جنائية", "الحجز", "سجن", "ملاحقة قضائية"))
        features.add(flag(
            "virus", "malware", "infected", "spyware", "ransomware",
            "remote access", "anydesk", "teamviewer",
            // Russian
            "вирус", "вредоносное", "заражён", "взломан", "хакер",
            "удалённый доступ", "шпионское по",
            // Hebrew
            "וירוס", "תוכנה זדונית", "פרוץ", "נגוע", "גישה מרחוק",
            "teamviewer", "anydesk", "להוריד תוכנה", "תיקון מחשב",
            // Arabic
            "فيروس", "برامج خبيثة", "مخترق", "وصول عن بعد"))
        features.add(flag(
            "microsoft", "windows", "apple", "computer", "device",
            "tech support", "technical support",
            // Russian
            "майкрософт", "виндовс", "эппл", "компьютер", "техподдержка", "техническая поддержка",
            // Hebrew
            "מיקרוסופט", "חלונות", "תמיכה טכנית", "שירות לקוחות טכני", "נציג תמיכה",
            // Arabic
            "مايكروسوفت", "ويندوز", "آبل", "دعم فني", "خدمة العملاء التقنية"))
        features.add(flag(
            "bank", "credit card", "debit card", "account number",
            "routing number", "wire transfer", "pin",
            // Russian
            "банк", "кредитная карта", "дебетовая карта", "номер счёта",
            "реквизиты", "пин-код", "перевод средств",
            // Hebrew
            "בנק", "כרטיס אשראי", "מספר חשבון", "פרטי בנק", "פין", "העברה בנקאית",
            "חשבון בנק", "כרטיס חיוב",
            // Arabic
            "بنك", "بطاقة ائتمان", "رقم الحساب", "تحويل بنكي", "بطاقة الخصم"))
        features.add(flag(
            "won", "winner", "prize", "lottery", "sweepstakes", "congratulations", "reward",
            // Russian
            "выиграли", "победитель", "приз", "лотерея", "поздравляем", "выигрыш", "джекпот",
            // Hebrew
            "זכית", "הגרלה", "פרס", "מזל טוב", "זוכה", "זכייה", "הגרלת",
            // Arabic
            "فزت", "جائزة", "يانصيب", "مبروك", "فائز", "قرعة"))
        features.add(flag(
            "social security", "ssn", "social security number", "ss number", "federal benefits",
            // Russian
            "снилс", "пенсионный фонд", "страховой номер", "инн", "паспортные данные",
            // Hebrew
            "תעודת זהות", "מספר תעודת זהות", "פרטים אישיים", "מספר ביטוח לאומי", "ת.ז",
            // Arabic
            "رقم الهوية", "بطاقة هوية", "الهوية الوطنية", "رقم الضمان الاجتماعي"))
        features.add(flag(
            "press one", "press 1", "recorded message", "automated", "warranty", "extended warranty",
            // Russian
            "нажмите один", "нажмите 1", "записанное сообщение",
            "автоматическое уведомление", "гарантия на автомобиль",
            // Hebrew
            "לחץ אחת", "לחץ 1", "הודעה מוקלטת", "הודעה אוטומטית", "אחריות מורחבת",
            // Arabic
            "اضغط واحد", "اضغط 1", "رسالة مسجلة", "آلية", "ضمان ممتد"))
        features.add(flag(
            "password", "credentials", "login", "username", "click", "link", "update your",
            // Russian
            "пароль", "логин", "учётные данные", "ссылка", "обновите данные", "войдите в систему",
            // Hebrew
            "סיסמה", "פרטי כניסה", "לחץ כאן", "קישור", "עדכן פרטים", "היכנס",
            "פרטי משתמש", "כניסה לחשבון",
            // Arabic
            "كلمة المرور", "بيانات الدخول", "انقر هنا", "رابط", "تسجيل الدخول"))

        // Features 20-26: More category signals
        features.add(flag(
            "insurance", "medicare", "medicaid", "health plan",
            "health insurance", "coverage", "enrollment",
            // Russian
            "страховка", "медицинская страховка", "полис", "страхование", "омс", "дмс",
            // Hebrew
            "ביטוח", "ביטוח בריאות", "פוליסה", "כיסוי ביטוחי", "ביטוח לאומי", "מגן",
            // Arabic
            "تأمين", "تأمين صحي", "وثيقة تأمين", "تغطية تأمينية"))
        features.add(flag(
            "investment", "trading", "profit", "returns",
            "broker", "portfolio", "invest", "stock", "crypto",
            // Russian
            "инвестиции", "трейдинг", "прибыль", "доходность", "брокер",
            "криптовалюта", "акции", "вложить", "заработок", "пассивный доход",
            // Hebrew
            "השקעה", "מסחר", "רווח", "תשואה", "ברוקר", "קריפטו", "ביטקוין",
            "להשקיע", "הכפלת כסף", "פורקס", "מניות",
            // Arabic
            "استثمار", "تداول", "ربح", "عائد", "وسيط", "عملة مشفرة", "بيتكوين"))
        features.add(flag(
            "gift card", "bitcoin", "western union", "wire",
            "cryptocurrency", "prepaid card",
            // Russian
            "биткоин", "криптовалюта", "вестерн юнион", "электронный кошелёк",
            "предоплата", "подарочная карта",
            // Hebrew
            "גיפט קארד", "ביטקוין", "קריפטו", "כרטיס מתנה", "ביט", "פייבוקס",
            "העברה מיידית",
            // Arabic
            "بطاقة هدية", "بيتكوين", "تحويل مالي", "ويسترن يونيون"))
        features.add(flag(
            "free", "no cost", "no charge", "at no cost", "qualify", "eligible", "complimentary",
            // Russian
            "бесплатно", "без оплаты", "имеете право", "подходите", "бесплатная консультация",
            // Hebrew
            "חינם", "ללא עלות", "זכאי", "מגיע לך", "בחינם", "ללא תשלום",
            // Arabic
            "مجاناً", "مجاني", "مؤهل", "تستحق", "بدون رسوم"))
        features.add(flag(
            "call back", "call now", "call immediately", "call us", "contact us", "call this number",
            // Russian
            "перезвоните", "позвоните сейчас", "срочно позвоните",
            "свяжитесь с нами", "звоните немедленно",
            // Hebrew
            "התקשר עכשיו", "חזור אלינו", "התקשרו אלינו", "צור קשר", "התקשר למספר",
            // Arabic
            "اتصل الآن", "اتصل بنا", "تواصل معنا", "اتصل بهذا الرقم"))
        features.add(flag(
            "final notice", "last chance", "act now",
            "time is running out", "do not delay", "do not ignore",
            "last warning", "failure to",
            // Russian
            "последнее уведомление", "последний шанс", "действуйте сейчас",
            "время истекает", "не игнорируйте", "финальное предупреждение",
            // Hebrew
            "הודעה אחרונה", "הזדמנות אחרונה", "פג תוקף", "תוך 24 שעות",
            "תוך 48 שעות", "עד מחר", "ייחסם", "יינתק", "יבוטל", "יימחק",
            // Arabic
            "إشعار نهائي", "فرصة أخيرة", "ينتهي", "خلال 24 ساعة", "آخر تحذير"))
        features.add(flag(
            "charity", "donate", "donation", "help victims",
            "disaster relief", "relief fund", "humanitarian",
            "tax deductible", "nonprofit", "fundraising",
            // Russian
            "благотворительность", "пожертвование", "помогите жертвам",
            "гуманитарная помощь", "фонд помощи", "сбор средств",
            // Hebrew
            "צדקה", "תרומה", "לתרום", "קרן סיוע", "עמותה", "ארגון ללא מטרות רווח",
            // Arabic
            "خيرية", "تبرع", "صندوق مساعدة", "إغاثة", "منظمة غير ربحية"))

        // Features 27-31: Conversational behaviour
        features.add(repetitionScore(t))                           // 27: repeated phrases
        features.add(questionDensity(t))                           // 28: question-word density
        features.add(if (hasLongMonologue(text)) 1f else 0f)       // 29: uninterrupted monologue
        features.add(if (urgencyEscalates(t)) 1f else 0f)         // 30: urgency heavier in 2nd half
        features.add(if (hasRepeatedSentenceOpeners(t)) 1f else 0f) // 31: scripted sentence openers

        // Features 32-37: Audio/prosody + call metadata (0.0 when context unavailable)
        features.add((context?.avgRmsDb?.div(20f))?.coerceIn(0f, 1f) ?: 0f)           // 32: normalised avg loudness
        features.add((context?.rmsStdDev?.div(5f))?.coerceIn(0f, 1f) ?: 0f)           // 33: normalised loudness variation
        features.add(context?.silenceRatio?.coerceIn(0f, 1f) ?: 0f)                   // 34: fraction of call silent
        features.add(if (context?.hadLongSilence == true) 1f else 0f)                 // 35: had a scripted-pause silence
        features.add((context?.callDurationSeconds?.toFloat()?.div(600f))?.coerceIn(0f, 1f) ?: 0f) // 36: normalised duration
        features.add(if (context != null &&
            (context.callStartHour < 8 || context.callStartHour >= 20)) 1f else 0f)   // 37: off-hours call

        // Features 38-42: Call-centre / multi-speaker / DTMF signals (0.0 when context unavailable)
        val callDurMin = (context?.callDurationSeconds?.toFloat() ?: 0f) / 60f
        features.add(                                                                   // 38: speaker switches per minute (capped at 1.0)
            if (callDurMin > 0f)
                ((context?.speakerSwitchCount?.toFloat() ?: 0f) / callDurMin).coerceIn(0f, 1f)
            else 0f
        )
        features.add((context?.noiseFloorDb?.div(2f))?.coerceIn(0f, 1f) ?: 0f)        // 39: background noise floor normalised
        features.add((context?.speechRateWpm?.div(300f))?.coerceIn(0f, 1f) ?: 0f)     // 40: speech rate normalised (300 wpm max)
        features.add(if (context?.dtmfDetected == true) 1f else 0f)                   // 41: DTMF / IVR tones detected
        features.add(if ((context?.noiseFloorDb ?: 0f) > 1f) 1f else 0f)             // 42: elevated background noise flag

        val result = features.toFloatArray()
        if (result.size != EXPECTED_FEATURE_COUNT) {
            Log.e(TAG, "Feature count mismatch: expected $EXPECTED_FEATURE_COUNT, got ${result.size}. " +
                "Bump FEATURE_VERSION and retrain the model.")
        }
        return result
    }

    // --- Conversational behaviour helpers ---

    /** Fraction of 3-word n-grams that appear more than once — high value signals scripted speech. */
    private fun repetitionScore(text: String): Float {
        val words = text.split(" ").filter { it.length > 2 }
        if (words.size < 3) return 0f
        val trigrams = (0..words.size - 3).map { "${words[it]} ${words[it + 1]} ${words[it + 2]}" }
        val repeatedCount = trigrams.size - trigrams.toSet().size
        return (repeatedCount.toFloat() / trigrams.size).coerceIn(0f, 1f)
    }

    /** Ratio of question words to total words — high value suggests interrogation pressure. */
    private fun questionDensity(text: String): Float {
        val words = text.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return 0f
        val qWords = setOf(
            // English
            "who", "what", "when", "where", "why", "how", "can", "could", "would", "do", "are", "is",
            // Hebrew
            "מה", "מי", "מתי", "איפה", "למה", "איך", "האם", "כמה", "היכן",
            // Arabic
            "ما", "من", "متى", "أين", "لماذا", "كيف", "هل", "كم"
        )
        return (words.count { it in qWords }.toFloat() / words.size).coerceIn(0f, 1f)
    }

    /** True if any sentence (split on punctuation) exceeds 50 words — suggests script reading. */
    private fun hasLongMonologue(text: String): Boolean =
        text.split(Regex("[.!?]+"))
            .any { segment -> segment.trim().split(" ").count { it.isNotEmpty() } > 50 }

    /** True if urgency terms are denser in the second half — classic scam escalation pattern. */
    private fun urgencyEscalates(text: String): Boolean {
        val words = text.split(" ")
        val mid = words.size / 2
        val urgencyTerms = listOf(
            // English
            "urgent", "immediately", "right now", "act now", "final", "last chance", "failure to",
            // Russian
            "срочно", "немедленно", "последний шанс", "действуйте сейчас",
            // Hebrew
            "דחוף", "עכשיו", "מיד", "תוך שעה", "הזדמנות אחרונה", "ייחסם", "יינתק",
            // Arabic
            "عاجل", "الآن", "فوراً", "فرصة أخيرة"
        )
        val firstCount = urgencyTerms.count { words.take(mid).joinToString(" ").contains(it) }
        val secondCount = urgencyTerms.count { words.drop(mid).joinToString(" ").contains(it) }
        return secondCount > firstCount
    }

    /** True if 3+ sentences share the same opening word — hallmark of a read script. */
    private fun hasRepeatedSentenceOpeners(text: String): Boolean {
        val openers = text.split(Regex("[.!?]+"))
            .mapNotNull { it.trim().split(" ").firstOrNull()?.lowercase() }
            .filter { it.isNotEmpty() }
        if (openers.size < 3) return false
        return openers.groupBy { it }.values.any { it.size >= 3 }
    }
}
