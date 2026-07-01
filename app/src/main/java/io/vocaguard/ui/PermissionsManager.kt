package io.vocaguard.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.vocaguard.service.MessagingScamDetectorService

class PermissionsManager(private val context: Context) {

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        )
    }

    fun checkAllPermissions(): Map<String, Boolean> {
        val permissions = mutableMapOf<String, Boolean>()

        permissions["Phone State"] = hasPermission(Manifest.permission.READ_PHONE_STATE)
        permissions["Answer Calls"] = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        permissions["Record Audio"] = hasPermission(Manifest.permission.RECORD_AUDIO)
        permissions["Notifications"] = hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        permissions["Contacts"] = hasPermission(Manifest.permission.READ_CONTACTS)
        permissions["Call Screening"] = isCallScreeningEnabled()
        permissions["Draw Overlay"] = Settings.canDrawOverlays(context)
        permissions["Notification Access"] = hasNotificationListenerAccess()

        return permissions
    }

    /** Whether SEND_SMS is granted — required for Family Guard SMS alerts. */
    fun hasSendSms(): Boolean = hasPermission(Manifest.permission.SEND_SMS)

    /** Whether CALL_PHONE is granted — required for Family Guard phone-call alerts. */
    fun hasCallPhone(): Boolean = hasPermission(Manifest.permission.CALL_PHONE)

    /** Whether VocaGuard has Notification Access — required for message scanning. */
    fun hasNotificationListenerAccess(): Boolean =
        MessagingScamDetectorService.isEnabled(context)

    /** Opens the system Notification Access settings screen. */
    fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isCallScreeningEnabled(): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun openCallScreeningSettings() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun createCallScreeningIntent(): Intent {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", context.packageName, null)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null)
        )
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
