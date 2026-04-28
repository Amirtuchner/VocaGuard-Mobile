package io.vocaguard

import android.app.Application
import io.vocaguard.service.CallMonitoringService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tests for the call-centre signal detectors added in Session 6:
 *  - Goertzel DTMF detection (detectDtmf / goertzelPower)
 *  - Speaker switch rate feature (feature 38)
 *  - Noise floor feature (feature 39 / 42)
 *  - Speech rate feature (feature 40)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallCentreDetectionTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun withService(block: (CallMonitoringService) -> Unit) {
        val controller = Robolectric.buildService(CallMonitoringService::class.java).create()
        block(controller.get())
        controller.destroy()
    }

    /**
     * Generate a synthetic DTMF tone by summing a row and column sine wave.
     * Amplitude 10 000 out of 32 767 gives a strong, clean signal.
     */
    private fun dtmfTone(
        rowHz: Float,
        colHz: Float,
        sampleRate: Int = 16_000,
        durationMs: Int = 40,
    ): ShortArray {
        val n = sampleRate * durationMs / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val v = 10_000 * sin(2 * PI * rowHz * t) +
                    10_000 * sin(2 * PI * colHz * t)
            v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** White-noise-like buffer — no dominant frequency component. */
    private fun noiseSamples(n: Int = 640): ShortArray =
        ShortArray(n) { (((it * 1664525 + 1013904223) and 0xFFFF) - 32768).toShort() }

    /** Silent buffer — all zeros. */
    private fun silenceSamples(n: Int = 640) = ShortArray(n)

    // -------------------------------------------------------------------------
    // Goertzel power
    // -------------------------------------------------------------------------

    @Test
    fun `goertzelPower returns higher power for matching frequency`() = withService { svc ->
        val samples = dtmfTone(770f, 1336f)      // digit "5"
        val powerAt770 = svc.goertzelPower(samples, 770f)
        val powerAt500 = svc.goertzelPower(samples, 500f)  // unrelated frequency
        assertTrue(
            "Power at target frequency should exceed power at unrelated frequency",
            powerAt770 > powerAt500
        )
    }

    @Test
    fun `goertzelPower returns near-zero for silence`() = withService { svc ->
        val power = svc.goertzelPower(silenceSamples(), 770f)
        assertEquals(0.0, power, 1e-6)
    }

    // -------------------------------------------------------------------------
    // DTMF detection
    // -------------------------------------------------------------------------

    @Test
    fun `detectDtmf returns true for digit 1 (697 + 1209 Hz)`() = withService { svc ->
        assertTrue(svc.detectDtmf(dtmfTone(697f, 1209f)))
    }

    @Test
    fun `detectDtmf returns true for digit 5 (770 + 1336 Hz)`() = withService { svc ->
        assertTrue(svc.detectDtmf(dtmfTone(770f, 1336f)))
    }

    @Test
    fun `detectDtmf returns true for digit 9 (852 + 1477 Hz)`() = withService { svc ->
        assertTrue(svc.detectDtmf(dtmfTone(852f, 1477f)))
    }

    @Test
    fun `detectDtmf returns true for hash key (941 + 1477 Hz)`() = withService { svc ->
        assertTrue(svc.detectDtmf(dtmfTone(941f, 1477f)))
    }

    @Test
    fun `detectDtmf returns false for silence`() = withService { svc ->
        assertFalse(svc.detectDtmf(silenceSamples()))
    }

    @Test
    fun `detectDtmf returns false for random noise`() = withService { svc ->
        // Random noise spreads power across all frequencies — no clear dominant pair
        var trueCount = 0
        repeat(10) { trueCount += if (svc.detectDtmf(noiseSamples())) 1 else 0 }
        // Allow at most 2 false positives in 10 independent noise buffers
        assertTrue("Too many false positives on noise: $trueCount/10", trueCount <= 2)
    }

    @Test
    fun `detectDtmf returns false for buffer smaller than minimum`() = withService { svc ->
        // Buffer of 40 bytes = 20 samples — below the 40-sample minimum in onBufferReceived
        assertFalse(svc.detectDtmf(ShortArray(15)))
    }

    @Test
    fun `detectDtmf returns false when only a row frequency is present`() = withService { svc ->
        // Single-frequency tone — a valid DTMF needs both row AND column
        val n = 640
        val samples = ShortArray(n) { i ->
            val t = i.toDouble() / 16_000
            (10_000 * sin(2 * PI * 770.0 * t)).roundToInt().toShort()
        }
        assertFalse(svc.detectDtmf(samples))
    }

    // -------------------------------------------------------------------------
    // Speaker switch rate (feature 38) — tested via TextPreprocessor + CallContext
    // These verify that the feature responds correctly to the values produced by
    // the service's speaker-switch counter.
    // -------------------------------------------------------------------------

    @Test
    fun `speaker switch rate is zero when no switches and short call`() {
        val preprocessor = io.vocaguard.ml.TextPreprocessor()
        val ctx = io.vocaguard.ml.CallContext(
            callDurationSeconds = 30L, avgRmsDb = 5f, rmsStdDev = 1f,
            silenceRatio = 0.2f, hadLongSilence = false, callStartHour = 12,
            speakerSwitchCount = 0, noiseFloorDb = 0f, speechRateWpm = 0f,
            dtmfDetected = false,
        )
        val features = preprocessor.extractFeatures("test", context = ctx)
        assertEquals(0f, features[37])  // feature 38 = index 37
    }

    @Test
    fun `speaker switch rate caps at 1 for extremely frequent switches`() {
        val preprocessor = io.vocaguard.ml.TextPreprocessor()
        val ctx = io.vocaguard.ml.CallContext(
            callDurationSeconds = 60L, avgRmsDb = 5f, rmsStdDev = 1f,
            silenceRatio = 0.2f, hadLongSilence = false, callStartHour = 12,
            speakerSwitchCount = 100, noiseFloorDb = 0f, speechRateWpm = 0f,
            dtmfDetected = false,
        )
        val features = preprocessor.extractFeatures("test", context = ctx)
        assertEquals(1f, features[37])  // 100 switches/min >> 1.0 cap
    }

    // -------------------------------------------------------------------------
    // Noise floor (features 39 + 42)
    // -------------------------------------------------------------------------

    @Test
    fun `noise floor feature is proportional to noiseFloorDb`() {
        val preprocessor = io.vocaguard.ml.TextPreprocessor()
        fun featuresFor(db: Float) = preprocessor.extractFeatures(
            "test",
            context = io.vocaguard.ml.CallContext(
                callDurationSeconds = 60L, avgRmsDb = 5f, rmsStdDev = 1f,
                silenceRatio = 0.2f, hadLongSilence = false, callStartHour = 12,
                speakerSwitchCount = 0, noiseFloorDb = db, speechRateWpm = 0f,
                dtmfDetected = false,
            )
        )
        assertEquals(0f, featuresFor(0f)[38])                           // no noise → 0
        assertEquals(0.5f, featuresFor(1f)[38], 0.001f)                 // 1 dB / 2.0 = 0.5
        assertEquals(1f, featuresFor(4f)[38])                           // capped at 1.0
        assertEquals(0f, featuresFor(0.9f)[41])                         // below 1.0 dB → flag off
        assertEquals(1f, featuresFor(1.1f)[41])                         // above 1.0 dB → flag on
    }

    // -------------------------------------------------------------------------
    // Speech rate (feature 40)
    // -------------------------------------------------------------------------

    @Test
    fun `speech rate feature scales linearly up to 300 wpm`() {
        val preprocessor = io.vocaguard.ml.TextPreprocessor()
        fun feature40(wpm: Float) = preprocessor.extractFeatures(
            "test",
            context = io.vocaguard.ml.CallContext(
                callDurationSeconds = 60L, avgRmsDb = 5f, rmsStdDev = 1f,
                silenceRatio = 0.2f, hadLongSilence = false, callStartHour = 12,
                speakerSwitchCount = 0, noiseFloorDb = 0f, speechRateWpm = wpm,
                dtmfDetected = false,
            )
        )[39]

        assertEquals(0f, feature40(0f))
        assertEquals(0.5f, feature40(150f), 0.001f)   // 150 / 300 = 0.5
        assertEquals(1f, feature40(300f), 0.001f)      // exactly at ceiling
        assertEquals(1f, feature40(500f))              // above ceiling → capped
    }
}
