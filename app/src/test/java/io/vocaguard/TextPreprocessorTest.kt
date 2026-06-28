package io.vocaguard

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.ml.CallContext
import io.vocaguard.ml.TextPreprocessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])

class TextPreprocessorTest {

    private lateinit var preprocessor: TextPreprocessor

    @Before
    fun setUp() {
        preprocessor = TextPreprocessor()
    }

    @Test
    fun `features array has exactly 46 elements`() {
        val features = preprocessor.extractFeatures("Hello world")
        assertEquals(49, features.size)
    }

    @Test
    fun `all features are between 0 and 1`() {
        val texts = listOf(
            "Hello world",
            "THIS IS THE IRS YOU OWE TAXES PAY NOW!!!",
            "Your account has been suspended verify immediately",
            "A".repeat(2000) // Very long text — tests clamping
        )
        for (text in texts) {
            val features = preprocessor.extractFeatures(text)
            features.forEachIndexed { i, f ->
                assertTrue("Feature $i out of range for text '$text': $f", f in 0f..1f)
            }
        }
    }

    @Test
    fun `empty text returns all zeros`() {
        val features = preprocessor.extractFeatures("")
        features.forEach { assertEquals(0f, it) }
    }

    @Test
    fun `urgency keywords set feature 7`() {
        val withUrgency = preprocessor.extractFeatures("You must act urgent immediately")
        val withoutUrgency = preprocessor.extractFeatures("Hello how are you today")
        assertEquals(1f, withUrgency[6])
        assertEquals(0f, withoutUrgency[6])
    }

    @Test
    fun `suspended keyword sets feature 8`() {
        val withSuspended = preprocessor.extractFeatures("Your account is suspended")
        val without = preprocessor.extractFeatures("Your account is active")
        assertEquals(1f, withSuspended[7])
        assertEquals(0f, without[7])
    }

    @Test
    fun `verify keyword sets feature 9`() {
        val withVerify = preprocessor.extractFeatures("Please verify your identity")
        val without = preprocessor.extractFeatures("Please call us back")
        assertEquals(1f, withVerify[8])
        assertEquals(0f, without[8])
    }

    @Test
    fun `money keyword sets feature 10`() {
        val withMoney = preprocessor.extractFeatures("You owe money now")
        val without = preprocessor.extractFeatures("You owe nothing")
        assertEquals(1f, withMoney[9])
        assertEquals(0f, without[9])
    }

    @Test
    fun `uppercase ratio is higher for all-caps text`() {
        val caps = preprocessor.extractFeatures("IRS ARREST WARRANT PAY NOW")
        val lower = preprocessor.extractFeatures("irs arrest warrant pay now")
        assertTrue(caps[5] > lower[5])
    }

    @Test
    fun `long text feature is clamped at 1`() {
        val longText = "word ".repeat(300) // >1000 chars
        val features = preprocessor.extractFeatures(longText)
        assertEquals(1f, features[0])
    }

    @Test
    fun `many words feature is clamped at 1`() {
        val manyWords = (1..150).joinToString(" ") { "word$it" } // >100 words
        val features = preprocessor.extractFeatures(manyWords)
        assertEquals(1f, features[1])
    }

    // --- Features 38-42: call-centre signals ---

    private fun baseContext(
        speakerSwitchCount: Int = 0,
        noiseFloorDb: Float = 0f,
        speechRateWpm: Float = 0f,
        dtmfDetected: Boolean = false,
        callDurationSeconds: Long = 60L,
    ) = CallContext(
        callDurationSeconds = callDurationSeconds,
        avgRmsDb = 5f,
        rmsStdDev = 1f,
        silenceRatio = 0.2f,
        hadLongSilence = false,
        callStartHour = 12,
        speakerSwitchCount = speakerSwitchCount,
        noiseFloorDb = noiseFloorDb,
        speechRateWpm = speechRateWpm,
        dtmfDetected = dtmfDetected,
    )

    @Test
    fun `features 38-42 are all zero when context is null`() {
        val features = preprocessor.extractFeatures("Hello", context = null)
        (37..41).forEach { i ->
            assertEquals("Feature ${i + 1} should be 0 with null context", 0f, features[i])
        }
    }

    @Test
    fun `feature 38 speaker switch rate is non-zero when switches occur`() {
        // 3 switches in 60s = 3 per minute → capped at 1.0
        val ctx = baseContext(speakerSwitchCount = 3, callDurationSeconds = 60L)
        val features = preprocessor.extractFeatures("Hello", context = ctx)
        assertTrue("Feature 38 should be > 0", features[37] > 0f)
        assertTrue("Feature 38 should be <= 1", features[37] <= 1f)
    }

    @Test
    fun `feature 38 is zero when call duration is zero`() {
        val ctx = baseContext(speakerSwitchCount = 5, callDurationSeconds = 0L)
        val features = preprocessor.extractFeatures("Hello", context = ctx)
        assertEquals(0f, features[37])
    }

    @Test
    fun `feature 39 noise floor normalised increases with higher noise`() {
        val quietCtx = baseContext(noiseFloorDb = 0f)
        val noisyCtx = baseContext(noiseFloorDb = 1.5f)
        val quietFeatures = preprocessor.extractFeatures("Hello", context = quietCtx)
        val noisyFeatures = preprocessor.extractFeatures("Hello", context = noisyCtx)
        assertTrue("Noisy context should produce higher feature 39", noisyFeatures[38] > quietFeatures[38])
    }

    @Test
    fun `feature 40 speech rate normalised scales with wpm`() {
        val slowCtx = baseContext(speechRateWpm = 100f)
        val fastCtx = baseContext(speechRateWpm = 200f)
        val slowFeatures = preprocessor.extractFeatures("Hello", context = slowCtx)
        val fastFeatures = preprocessor.extractFeatures("Hello", context = fastCtx)
        assertTrue("Faster speech should produce higher feature 40", fastFeatures[39] > slowFeatures[39])
        // 300 wpm → capped at 1.0
        val maxCtx = baseContext(speechRateWpm = 400f)
        assertEquals(1f, preprocessor.extractFeatures("Hello", context = maxCtx)[39])
    }

    @Test
    fun `feature 41 DTMF flag is 1 when dtmfDetected is true`() {
        val withDtmf = baseContext(dtmfDetected = true)
        val withoutDtmf = baseContext(dtmfDetected = false)
        assertEquals(1f, preprocessor.extractFeatures("Hello", context = withDtmf)[40])
        assertEquals(0f, preprocessor.extractFeatures("Hello", context = withoutDtmf)[40])
    }

    @Test
    fun `feature 42 elevated noise flag triggers above 1dB noise floor`() {
        val belowThreshold = baseContext(noiseFloorDb = 0.9f)
        val aboveThreshold = baseContext(noiseFloorDb = 1.1f)
        assertEquals(0f, preprocessor.extractFeatures("Hello", context = belowThreshold)[41])
        assertEquals(1f, preprocessor.extractFeatures("Hello", context = aboveThreshold)[41])
    }
}
