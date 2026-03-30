package com.example.vocaguard.ml

import android.util.Log

class TextPreprocessor {

    companion object {
        private const val TAG = "TextPreprocessor"
        private const val MAX_SEQUENCE_LENGTH = 128
        private const val VOCAB_SIZE = 10000

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

    fun extractFeatures(text: String): FloatArray {
        // Extract 25 numerical features for the ML model.
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
        features.add(flag("urgent", "immediately", "right now", "at once"))
        features.add(flag("suspended", "locked", "frozen", "blocked"))
        features.add(flag("verify", "confirm", "validate"))
        features.add(flag("money", "payment", "funds", "cash", "pay"))

        // Features 11-19: Category-specific keywords
        features.add(flag("irs", "internal revenue", "tax department", "tax debt",
            "back taxes", "unpaid tax"))
        features.add(flag("arrest", "warrant", "jail", "prison", "prosecution",
            "charges", "law enforcement", "officer"))
        features.add(flag("virus", "malware", "infected", "spyware", "ransomware",
            "remote access", "anydesk", "teamviewer"))
        features.add(flag("microsoft", "windows", "apple", "computer", "device",
            "tech support", "technical support"))
        features.add(flag("bank", "credit card", "debit card", "account number",
            "routing number", "wire transfer", "pin"))
        features.add(flag("won", "winner", "prize", "lottery", "sweepstakes",
            "congratulations", "reward"))
        features.add(flag("social security", "ssn", "social security number",
            "ss number", "federal benefits"))
        features.add(flag("press one", "press 1", "recorded message",
            "automated", "warranty", "extended warranty"))
        features.add(flag("password", "credentials", "login", "username",
            "click", "link", "update your"))

        // Features 20-25: More category signals
        features.add(flag("insurance", "medicare", "medicaid", "health plan",
            "health insurance", "coverage", "enrollment"))
        features.add(flag("investment", "trading", "profit", "returns",
            "broker", "portfolio", "invest", "stock", "crypto"))
        features.add(flag("gift card", "bitcoin", "western union", "wire",
            "cryptocurrency", "prepaid card"))
        features.add(flag("free", "no cost", "no charge", "at no cost",
            "qualify", "eligible", "complimentary"))
        features.add(flag("call back", "call now", "call immediately",
            "call us", "contact us", "call this number"))
        features.add(flag("final notice", "last chance", "act now",
            "time is running out", "do not delay", "do not ignore",
            "last warning", "failure to"))
        features.add(flag("charity", "donate", "donation", "help victims",
            "disaster relief", "relief fund", "humanitarian",
            "tax deductible", "nonprofit", "fundraising"))

        return features.toFloatArray()
    }
}