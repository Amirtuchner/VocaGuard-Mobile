package io.vocaguard.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists all Family Guard Mode settings.
 *
 * **Senior's device (monitored side):**
 *   - [isEnabled] — activates SMS alerts to contacts on scam detection
 *   - [contacts] — list of family members / caregivers to notify
 *   - [seniorModeEnabled] — activates the large-print, voice-guided UI
 *   - [seniorName] — the name shown in outgoing alert messages (e.g. "Grandma")
 *
 * **Caregiver's device (monitoring side):**
 *   - Receives alerts via the `vocaguard://alert` deep-link embedded in the SMS
 *   - Stores them in Room ([FamilyAlertEntity]) and shows them in [FamilyDashboard]
 */
class FamilyGuardSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Feature toggles ───────────────────────────────────────────────────────

    /** Whether to send SMS alerts to [contacts] when a scam is detected. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Whether to use the large-print, simplified, voice-guided UI for seniors. */
    var seniorModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENIOR_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SENIOR_MODE, value).apply()

    /** The name used in outgoing alert messages (e.g. "Grandma", "Dad"). */
    var seniorName: String
        get() = prefs.getString(KEY_SENIOR_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SENIOR_NAME, value.trim()).apply()

    /**
     * Whether to make an outgoing phone call to the primary contact after a scam call ends.
     * The call plays a TTS voice message so the caregiver knows immediately what happened.
     * Requires the CALL_PHONE permission.
     */
    var callAlertEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALL_ALERT, false)
        set(value) = prefs.edit().putBoolean(KEY_CALL_ALERT, value).apply()

    // ── Contacts ──────────────────────────────────────────────────────────────

    /** The list of family members / caregivers to notify on scam detection. */
    var contacts: List<FamilyContact>
        get() {
            val json = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    FamilyContact(
                        name = obj.getString("name"),
                        phoneNumber = obj.getString("phoneNumber")
                    )
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { contact ->
                arr.put(JSONObject().apply {
                    put("name", contact.name)
                    put("phoneNumber", contact.phoneNumber)
                })
            }
            prefs.edit().putString(KEY_CONTACTS, arr.toString()).apply()
        }

    fun addContact(contact: FamilyContact) {
        contacts = contacts + contact
    }

    fun removeContact(phoneNumber: String) {
        contacts = contacts.filter { it.phoneNumber != phoneNumber }
    }

    companion object {
        private const val PREFS_NAME = "vocaguard_family_guard"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SENIOR_MODE = "senior_mode"
        private const val KEY_SENIOR_NAME = "senior_name"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_CALL_ALERT = "call_alert_enabled"

        @Volatile private var instance: FamilyGuardSettings? = null

        fun getInstance(context: Context): FamilyGuardSettings =
            instance ?: synchronized(this) {
                instance ?: FamilyGuardSettings(context.applicationContext).also { instance = it }
            }
    }
}

/** A family member or caregiver who should be notified when a scam is detected. */
data class FamilyContact(
    val name: String,
    val phoneNumber: String
)
