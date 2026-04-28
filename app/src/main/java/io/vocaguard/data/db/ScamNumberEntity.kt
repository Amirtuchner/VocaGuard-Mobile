package io.vocaguard.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.vocaguard.data.ScamInfo
import io.vocaguard.data.ScamType

@Entity(tableName = "scam_numbers")
data class ScamNumberEntity(
    @PrimaryKey val phoneNumber: String,
    val isKnownScammer: Boolean,
    val isSuspicious: Boolean,
    val scamType: String,
    val reportCount: Int,
    val lastReported: Long,
    /** Epoch ms after which this entry is considered stale; 0 means never expires. */
    val expiresAt: Long = 0L
) {
    fun toDomain() = ScamInfo(
        phoneNumber = phoneNumber,
        isKnownScammer = isKnownScammer,
        isSuspicious = isSuspicious,
        scamType = runCatching { ScamType.valueOf(scamType) }.getOrDefault(ScamType.UNKNOWN),
        reportCount = reportCount,
        lastReported = lastReported
    )
}

fun ScamInfo.toEntity(expiresAt: Long = 0L) = ScamNumberEntity(
    phoneNumber = phoneNumber,
    isKnownScammer = isKnownScammer,
    isSuspicious = isSuspicious,
    scamType = scamType.name,
    reportCount = reportCount,
    lastReported = lastReported,
    expiresAt = expiresAt
)
