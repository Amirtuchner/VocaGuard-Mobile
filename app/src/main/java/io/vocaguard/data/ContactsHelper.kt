package io.vocaguard.data

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/**
 * Utility for checking whether a phone number or display name belongs to a
 * saved contact on the device.
 *
 * Used by the scam detection pipeline to skip analysis for known contacts —
 * scammers are never in the victim's contact list, so a known-contact check
 * is the single most effective false-positive filter available.
 *
 * All methods return false silently when READ_CONTACTS permission has not been
 * granted, so the rest of the pipeline continues unaffected.
 */
object ContactsHelper {

    private const val TAG = "ContactsHelper"

    /**
     * Returns true if [phoneNumber] matches any entry in the device contacts.
     * Uses [ContactsContract.PhoneLookup] which normalises number format
     * (country code, spaces, dashes) automatically.
     */
    fun isKnownContact(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        return try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon().appendPath(phoneNumber).build()
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { it.count > 0 } ?: false
        } catch (e: SecurityException) {
            Log.d(TAG, "READ_CONTACTS not granted — phone lookup skipped")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Phone lookup failed for $phoneNumber", e)
            false
        }
    }

    /**
     * Returns true if [displayName] exactly matches any contact's display name
     * (case-insensitive via COLLATE NOCASE).
     *
     * Used for messaging notifications where only the sender's display name is
     * available (WhatsApp / Telegram show the contact name in the notification
     * title, not the phone number).
     */
    fun isKnownContactByName(context: Context, displayName: String): Boolean {
        if (displayName.isBlank()) return false
        return try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ? COLLATE NOCASE",
                arrayOf(displayName),
                null
            )?.use { it.count > 0 } ?: false
        } catch (e: SecurityException) {
            Log.d(TAG, "READ_CONTACTS not granted — name lookup skipped")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Name lookup failed for '$displayName'", e)
            false
        }
    }
}
