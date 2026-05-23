package io.vocaguard

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.data.ScamType
import io.vocaguard.detection.HybridScamDetector
import io.vocaguard.detection.ScamPatternDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test: verifies the end-to-end text → DetectionResult pipeline
 * using [ScamPatternDetector] (rule-based, no ML model required).
 *
 * Each test exercises a real text input through the full rule-based path and
 * asserts the fields that [CallMonitoringService] uses to decide whether to
 * call [ScamAlertManager.triggerScamAlert].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ScamDetectionPipelineTest {

    private lateinit var detector: ScamPatternDetector

    @Before
    fun setUp() {
        detector = ScamPatternDetector()
    }

    // ---- IRS scam ----

    @Test
    fun `IRS owe-money text triggers scam alert`() {
        val result = detector.analyzeText(
            "This is the IRS. You owe money and have tax fraud on your record. Pay immediately " +
            "or face an arrest warrant and legal action."
        )
        assertTrue(result.isScam)
        assertEquals(ScamType.IRS_SCAM, result.scamType)
        assertTrue(result.confidence > 0f)
        assertTrue(result.reason.isNotEmpty())
    }

    // ---- Tech support scam ----

    @Test
    fun `tech support virus text triggers scam alert`() {
        val result = detector.analyzeText(
            "Windows technical support here. Your computer has malware detected. " +
            "You need to give us remote access via TeamViewer immediately."
        )
        assertTrue(result.isScam)
        assertEquals(ScamType.TECH_SUPPORT, result.scamType)
    }

    // ---- Bank fraud ----

    @Test
    fun `bank account suspended text triggers scam alert`() {
        val result = detector.analyzeText(
            "Your account has been locked due to unusual activity. " +
            "Please verify your account immediately. We detected an unauthorized transaction and a security breach."
        )
        assertTrue(result.isScam)
        assertEquals(ScamType.BANK_FRAUD, result.scamType)
    }

    // ---- Social security scam ----

    @Test
    fun `social security suspended text triggers scam alert`() {
        val result = detector.analyzeText(
            "Your social security number has been suspended due to illegal activity. " +
            "Contact us immediately or face arrest."
        )
        assertTrue(result.isScam)
        assertEquals(ScamType.SOCIAL_SECURITY, result.scamType)
    }

    // ---- Negative / legitimate calls ----

    @Test
    fun `doctor appointment reminder is not flagged`() {
        val result = detector.analyzeText(
            "This is a reminder for your doctor appointment tomorrow at 2pm. Please call us to confirm."
        )
        assertFalse(result.isScam)
    }

    @Test
    fun `pizza delivery notification is not flagged`() {
        val result = detector.analyzeText(
            "Your pizza order is on its way. Estimated delivery in 20 minutes."
        )
        assertFalse(result.isScam)
    }

    @Test
    fun `empty text is not flagged`() {
        val result = detector.analyzeText("")
        assertFalse(result.isScam)
    }

    // ---- Pipeline field contract ----

    @Test
    fun `scam result has non-empty reason and keywords`() {
        val result = detector.analyzeText(
            "Urgent: your account will be locked. Verify your information now to avoid suspension."
        )
        if (result.isScam) {
            assertTrue(result.reason.isNotEmpty())
            assertTrue(result.keywords.isNotEmpty())
        }
    }

    @Test
    fun `confidence is bounded between 0 and 1`() {
        listOf(
            "IRS scam call: pay now or be arrested",
            "Your pizza is ready",
            ""
        ).forEach { text ->
            val result = detector.analyzeText(text)
            assertTrue("confidence out of range for: $text", result.confidence in 0f..1f)
        }
    }
}
