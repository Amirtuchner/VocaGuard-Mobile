package com.example.vocaguard

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.vocaguard.ml.TextPreprocessor
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
    fun `features array has exactly 26 elements`() {
        val features = preprocessor.extractFeatures("Hello world")
        assertEquals(26, features.size)
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
}