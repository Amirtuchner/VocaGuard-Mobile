package io.vocaguard.data

data class ScamInfo(
    val phoneNumber: String,
    val isKnownScammer: Boolean = false,
    val isSuspicious: Boolean = false,
    val scamType: ScamType = ScamType.UNKNOWN,
    val reportCount: Int = 0,
    val lastReported: Long = 0
)

enum class ScamType {
    UNKNOWN,
    IRS_SCAM,
    TECH_SUPPORT,
    BANK_FRAUD,
    LOTTERY_PRIZE,
    SOCIAL_SECURITY,
    ROBOCALL,
    PHISHING,
    INSURANCE,
    INVESTMENT_SCAM,
    DONATION_FRAUD,
    ROMANCE_SCAM,
    DELIVERY_SCAM,
    JOB_SCAM
}
