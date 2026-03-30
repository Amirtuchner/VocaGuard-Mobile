package com.example.vocaguard

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.vocaguard.data.ScamType
import com.example.vocaguard.detection.HybridScamDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class HybridScamDetectorTest {

    private lateinit var detector: HybridScamDetector

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        detector = HybridScamDetector(context)
    }

    // --- ML unavailable path (rule-based fallback) ---

    @Test
    fun `returns rule-based result when ML unavailable`() {
        // TFLite model is not in test assets, so ML is disabled and rule-based takes over
        val result = detector.analyzeText("This is the IRS you owe back taxes pay now or face arrest")
        // With ML disabled, result should still detect the scam via rules
        assertTrue("Expected scam to be detected by rule-based fallback", result.isScam)
        assertEquals(ScamType.IRS_SCAM, result.scamType)
    }

    // --- Ensemble logic ---

    @Test
    fun `confidence is between 0 and 1`() {
        val result = detector.analyzeText("Hello this is a normal call")
        assertTrue(result.confidence in 0f..1f)
    }

    @Test
    fun `high confidence scam text produces isScam true`() {
        val result = detector.analyzeText(
            "IRS arrest warrant legal action social security suspended bitcoin wire transfer " +
            "verify your account immediately urgent pay now"
        )
        assertTrue(result.isScam)
        assertTrue(result.confidence > 0.5f)
    }

    @Test
    fun `clean call text produces isScam false`() {
        val result = detector.analyzeText("Hi calling to confirm your dentist appointment tomorrow at 2pm")
        assertFalse(result.isScam)
    }

    @Test
    fun `result contains reason string`() {
        val result = detector.analyzeText("IRS you owe taxes arrest warrant immediately")
        assertTrue(result.reason.isNotEmpty())
    }

    @Test
    fun `keywords list populated for rule-based hits`() {
        val result = detector.analyzeText("IRS you owe back taxes pay immediately arrest")
        assertTrue(result.isScam)
        assertTrue(result.keywords.isNotEmpty())
    }

    // --- getDetectorInfo ---

    @Test
    fun `getDetectorInfo returns non-empty string`() {
        assertTrue(detector.getDetectorInfo().isNotEmpty())
    }

    // --- Release ---

    @Test
    fun `release does not throw`() {
        detector.release()
        // Should not throw, model cleanup should be graceful
    }
}
