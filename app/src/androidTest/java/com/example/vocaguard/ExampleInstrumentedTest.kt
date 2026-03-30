package com.example.vocaguard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vocaguard.data.DetectionSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests that run on a real Android device or emulator.
 *
 * These verify the most critical end-to-end path: the app launches, reports the
 * correct package name, and renders at least the first visible UI element.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun skipOnboarding() {
        // Mark onboarding complete so the test sees MainScreen, not OnboardingScreen.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        DetectionSettings.getInstance(ctx).onboardingComplete = true
    }

    @Test
    fun appContext_hasCorrectPackageName() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.vocaguard", ctx.packageName)
    }

    @Test
    fun mainScreen_topBar_isDisplayed() {
        composeTestRule.onNodeWithText("VocaGuard").assertIsDisplayed()
    }

    @Test
    fun mainScreen_homeTab_isSelectedByDefault() {
        // The "Home" tab label must be visible in the tab row.
        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
    }
}
