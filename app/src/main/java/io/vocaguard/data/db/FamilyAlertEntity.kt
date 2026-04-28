package io.vocaguard.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores scam alerts received from monitored family members on the caregiver's device.
 *
 * Records arrive via the `vocaguard://alert` deep-link that [FamilyAlertSender] embeds in
 * the SMS it sends to configured contacts. The caregiver taps the link and VocaGuard parses
 * the URL parameters into this entity.
 */
@Entity(tableName = "family_alerts")
data class FamilyAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display name of the monitored person (e.g. "Grandma", "Dad"). */
    val senderName: String,
    /** Normalised E.164 phone number (without +) of the monitored person's device. */
    val senderNumber: String,
    /** [io.vocaguard.data.ScamType] name of the detected scam. */
    val scamType: String,
    /** Detection confidence 0.0–1.0. */
    val confidence: Float,
    /** Epoch milliseconds when the scam was detected on the sender's device. */
    val timestamp: Long,
    /** True once the caregiver has viewed this alert in the dashboard. */
    val isRead: Boolean = false
)
