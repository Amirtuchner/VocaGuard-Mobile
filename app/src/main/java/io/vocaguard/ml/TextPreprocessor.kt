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
        features.add(flag("urgent", "immediately", "right now", "at once",
            "срочно", "немедленно", "сейчас же", "прямо сейчас", "незамедлительно"))
        features.add(flag("suspended", "locked", "frozen", "blocked",
            "заблокирован", "заморожен", "приостановлен", "закрыт"))
        features.add(flag("verify", "confirm", "validate",
            "подтвердите", "верифицируйте", "проверьте", "подтверждение"))
        features.add(flag("money", "payment", "funds", "cash", "pay",
            "деньги", "оплата", "средства", "перевод", "платёж", "платеж"))

        // Features 11-19: Category-specific keywords
        features.add(flag("irs", "internal revenue", "tax department", "tax debt",
            "back taxes", "unpaid tax",
            "налоговая", "налоги", "налоговый долг", "задолженность", "налоговая служба", "фнс"))
        features.add(flag("arrest", "warrant", "jail", "prison", "prosecution",
            "charges", "law enforcement", "officer",
            "арест", "ордер на арест", "тюрьма", "уголовное дело", "полиция",
            "следствие", "обвинение", "прокуратура"))
        features.add(flag("virus", "malware", "infected", "spyware", "ransomware",
            "remote access", "anydesk", "teamviewer",
            "вирус", "вредоносное", "заражён", "взломан", "хакер",
            "удалённый доступ", "шпионское по"))
        features.add(flag("microsoft", "windows", "apple", "computer", "device",
            "tech support", "technical support",
            "майкрософт", "виндовс", "эппл", "компьютер", "техподдержка", "техническая поддержка"))
        features.add(flag("bank", "credit card", "debit card", "account number",
            "routing number", "wire transfer", "pin",
            "банк", "кредитная карта", "дебетовая карта", "номер счёта",
            "реквизиты", "пин-код", "перевод средств"))
        features.add(flag("won", "winner", "prize", "lottery", "sweepstakes",
            "congratulations", "reward",
            "выиграли", "победитель", "приз", "лотерея", "поздравляем", "выигрыш", "джекпот"))
        features.add(flag("social security", "ssn", "social security number",
            "ss number", "federal benefits",
            "снилс", "пенсионный фонд", "страховой номер", "инн", "паспортные данные"))
        features.add(flag("press one", "press 1", "recorded message",
            "automated", "warranty", "extended warranty",
            "нажмите один", "нажмите 1", "записанное сообщение",
            "автоматическое уведомление", "гарантия на автомобиль"))
        features.add(flag("password", "credentials", "login", "username",
            "click", "link", "update your",
            "пароль", "логин", "учётные данные", "ссылка", "обновите данные", "войдите в систему"))

        // Features 20-26: More category signals
        features.add(flag("insurance", "medicare", "medicaid", "health plan",
            "health insurance", "coverage", "enrollment",
            "страховка", "медицинская страховка", "полис", "страхование", "омс", "дмс"))
        features.add(flag("investment", "trading", "profit", "returns",
            "broker", "portfolio", "invest", "stock", "crypto",
            "инвестиции", "трейдинг", "прибыль", "доходность", "брокер",
            "криптовалюта", "акции", "вложить", "заработок", "пассивный доход"))
        features.add(flag("gift card", "bitcoin", "western union", "wire",
            "cryptocurrency", "prepaid card",
            "биткоин", "криптовалюта", "вестерн юнион", "электронный кошелёк",
            "предоплата", "подарочная карта"))
        features.add(flag("free", "no cost", "no charge", "at no cost",
            "qualify", "eligible", "complimentary",
            "бесплатно", "без оплаты", "имеете право", "подходите", "бесплатная консультация"))
        features.add(flag("call back", "call now", "call immediately",
            "call us", "contact us", "call this number",
            "перезвоните", "позвоните сейчас", "срочно позвоните",
            "свяжитесь с нами", "звоните немедленно"))
        features.add(flag("final notice", "last chance", "act now",
            "time is running out", "do not delay", "do not ignore",
            "last warning", "failure to",
            "последнее уведомление", "последний шанс", "действуйте сейчас",
            "время истекает", "не игнорируйте", "финальное предупреждение"))
        features.add(flag("charity", "donate", "donation", "help victims",
            "disaster relief", "relief fund", "humanitarian",
            "tax deductible", "nonprofit", "fundraising",
            "благотворительность", "пожертвование", "помогите жертвам",
            "гуманитарная помощь", "фонд помощи", "сбор средств"))

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
        val qWords = setOf("who", "what", "when", "where", "why", "how",
            "can", "could", "would", "do", "are", "is")
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
        val urgencyTerms = listOf("urgent", "immediately", "right now", "act now",
            "final", "last chance", "failure to")
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
