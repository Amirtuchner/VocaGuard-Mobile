package com.example.vocaguard.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.vocaguard.data.ScamInfo
import com.example.vocaguard.data.ScamType

@Entity(tableName = "scam_numbers")
data class ScamNumberEntity(
    @PrimaryKey val phoneNumber: String,
    val isKnownScammer: Boolean,
    val isSuspicious: Boolean,
    val scamType: String,
    val reportCount: Int,
    val lastReported: Long
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

fun ScamInfo.toEntity() = ScamNumberEntity(
    phoneNumber = phoneNumber,
    isKnownScammer = isKnownScammer,
    isSuspicious = isSuspicious,
    scamType = scamType.name,
    reportCount = reportCount,
    lastReported = lastReported
)