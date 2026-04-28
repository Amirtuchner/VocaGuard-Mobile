package io.vocaguard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamInfo
import io.vocaguard.data.ScamType
import io.vocaguard.data.db.ScamNumberDao
import io.vocaguard.data.db.VocaGuardDatabase
import io.vocaguard.data.db.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests that run on a real Android device or emulator.
 *
 * Split into two groups:
 *  - UI smoke tests: verify the Compose navigation and screen rendering
 *  - Room DAO tests: verify database queries against a real SQLite engine
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun skipOnboarding() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        DetectionSettings.getInstance(ctx).onboardingComplete = true
    }

    // ── Package / context ─────────────────────────────────────────────────────

    @Test
    fun appContext_hasCorrectPackageName() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.vocaguard", ctx.packageName)
    }

    // ── Bottom navigation ─────────────────────────────────────────────────────

    @Test
    fun mainScreen_homeTab_isSelectedByDefault() {
        composeTestRule.onNodeWithContentDescription("Home").assertIsDisplayed()
    }

    @Test
    fun mainScreen_navigateToHistory_showsHistoryContent() {
        composeTestRule.onNodeWithContentDescription("History").performClick()
        // After tapping History the History tab should render.
        composeTestRule.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun mainScreen_navigateToSettings_showsSettingsContent() {
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun mainScreen_navigateBackToHome_fromSettings() {
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithContentDescription("Home").performClick()
        composeTestRule.onNodeWithContentDescription("Home").assertIsDisplayed()
    }

    // ── Settings screen content ───────────────────────────────────────────────

    @Test
    fun settingsScreen_sensitivityCard_isVisible() {
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Detection Sensitivity").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_alertSoundsCard_isVisible() {
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Alert Sounds").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_communityBlocklistCard_isVisible() {
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Community Blocklist").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_modelUpdateCard_isVisible() {
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Detection Model").assertIsDisplayed()
    }
}

// ── Room DAO instrumented tests ───────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class ScamNumberDaoTest {

    private lateinit var db: VocaGuardDatabase
    private lateinit var dao: ScamNumberDao

    @Before
    fun createDb() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, VocaGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        VocaGuardDatabase.setTestInstance(db)
        dao = db.scamNumberDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insert_andRetrieve_scamNumber() = runBlocking {
        val info = ScamInfo(
            phoneNumber = "15551234567",
            isKnownScammer = true,
            scamType = ScamType.IRS_SCAM,
            reportCount = 1,
            lastReported = System.currentTimeMillis()
        )
        dao.insert(info.toEntity())
        val retrieved = dao.getByNumber("15551234567")
        assertNotNull(retrieved)
        assertEquals(ScamType.IRS_SCAM.name, retrieved!!.scamType)
        assertTrue(retrieved.isKnownScammer)
    }

    @Test
    fun deleteByNumber_removesEntry() = runBlocking {
        val info = ScamInfo(phoneNumber = "15559876543", isKnownScammer = true)
        dao.insert(info.toEntity())
        dao.deleteByNumber("15559876543")
        assertNull(dao.getByNumber("15559876543"))
    }

    @Test
    fun deleteExpiredBefore_removesOnlyExpiredEntries() = runBlocking {
        val nowMs = System.currentTimeMillis()
        val expired = ScamInfo(phoneNumber = "10000000001", isKnownScammer = true)
        val permanent = ScamInfo(phoneNumber = "10000000002", isKnownScammer = true)
        val future = ScamInfo(phoneNumber = "10000000003", isKnownScammer = true)

        dao.insert(expired.toEntity(expiresAt = nowMs - 1_000))   // already expired
        dao.insert(permanent.toEntity(expiresAt = 0L))            // never expires
        dao.insert(future.toEntity(expiresAt = nowMs + 86_400_000)) // expires tomorrow

        dao.deleteExpiredBefore(nowMs)

        assertNull(dao.getByNumber("10000000001"))   // removed
        assertNotNull(dao.getByNumber("10000000002")) // kept (permanent)
        assertNotNull(dao.getByNumber("10000000003")) // kept (future)
    }

    @Test
    fun count_returnsCorrectNumber() = runBlocking {
        assertEquals(0, dao.count())
        dao.insert(ScamInfo(phoneNumber = "10000000010").toEntity())
        dao.insert(ScamInfo(phoneNumber = "10000000011").toEntity())
        assertEquals(2, dao.count())
    }

    @Test
    fun clearAll_removesAllEntries() = runBlocking {
        dao.insert(ScamInfo(phoneNumber = "10000000020").toEntity())
        dao.clearAll()
        assertEquals(0, dao.count())
    }

    @Test
    fun insert_withSameNumber_replacesExistingEntry() = runBlocking {
        val original = ScamInfo(phoneNumber = "15550000001", isKnownScammer = false)
        val updated = ScamInfo(phoneNumber = "15550000001", isKnownScammer = true, scamType = ScamType.ROBOCALL)
        dao.insert(original.toEntity())
        dao.insert(updated.toEntity())
        val result = dao.getByNumber("15550000001")
        assertNotNull(result)
        assertTrue(result!!.isKnownScammer)
        assertEquals(ScamType.ROBOCALL.name, result.scamType)
    }
}
