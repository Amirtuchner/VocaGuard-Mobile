package io.vocaguard.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.vocaguard.data.CallTranscript

@Entity(tableName = "call_transcripts")
data class CallTranscriptEntity(
    @PrimaryKey val id: Long,
    val timestamp: Long,
    val text: String,
    val phoneNumber: String,
    val detectedScamTypesJson: String // comma-separated enum names
) {
    fun toDomain(): CallTranscript = CallTranscript(
        id = id,
        timestamp = timestamp,
        text = text,
        phoneNumber = phoneNumber,
        detectedScamTypes = if (detectedScamTypesJson.isEmpty()) emptyList()
                            else detectedScamTypesJson.split(",")
    )
}

fun CallTranscript.toEntity() = CallTranscriptEntity(
    id = id,
    timestamp = timestamp,
    text = text,
    phoneNumber = phoneNumber,
    detectedScamTypesJson = detectedScamTypes.joinToString(",")
)
