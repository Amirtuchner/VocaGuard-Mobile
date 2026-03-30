package com.example.vocaguard

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.vocaguard.alert.ScamAlertManager
import com.example.vocaguard.data.ScamType
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ScamAlertManager], covering notification channel creation,
 * alert triggering, and graceful shutdown — all without a real device or TTS engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScamAlertManagerTest {

    private lateinit var context: Context
    private lateinit var manager: ScamAlertManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = ScamAlertManager(context)
    }

    // -------------------------------------------------------------------------
    // Notification channel
    // -------------------------------------------------------------------------

    @Test
    fun `notification channel is created on init`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel("scam_alert_channel")
        assertNotNull("Alert notification channel must be registered", channel)
    }

    // -------------------------------------------------------------------------
    // triggerScamAlert — should not throw even when TTS is unavailable
    // -------------------------------------------------------------------------

    @Test
    fun `triggerScamAlert does not throw for IRS scam type`() {
        manager.triggerScamAlert(
            scamType = ScamType.IRS_SCAM,
            transcript = "This is the IRS you owe money",
            confidence = 0.9f,
            phoneNumber = "15551234567"
        )
    }

    @Test
    fun `triggerScamAlert does not throw for Tech Support scam type`() {
        manager.triggerScamAlert(
            scamType = ScamType.TECH_SUPPORT,
            transcript = "Your computer has a virus",
            confidence = 0.85f,
            phoneNumber = "15559876543"
        )
    }

    @Test
    fun `triggerScamAlert does not throw for all scam types`() {
        ScamType.entries.forEach { scamType ->
            manager.triggerScamAlert(
                scamType = scamType,
                transcript = "Test transcript for $scamType",
                confidence = 0.8f,
                phoneNumber = "15550000000"
            )
        }
    }

    @Test
    fun `triggerScamAlert does not throw when phone number is empty`() {
        manager.triggerScamAlert(
            scamType = ScamType.BANK_FRAUD,
            transcript = "Your bank account is suspended",
            confidence = 0.75f,
            phoneNumber = ""
        )
    }

    @Test
    fun `triggerScamAlert does not throw when transcript is empty`() {
        manager.triggerScamAlert(
            scamType = ScamType.ROBOCALL,
            transcript = "",
            confidence = 0.6f,
            phoneNumber = "15551112222"
        )
    }

    // -------------------------------------------------------------------------
    // shutdown — should not throw even if called multiple times
    // -------------------------------------------------------------------------

    @Test
    fun `shutdown does not throw`() {
        manager.shutdown()
    }

    @Test
    fun `shutdown can be called multiple times without throwing`() {
        manager.shutdown()
        manager.shutdown()
    }
}
