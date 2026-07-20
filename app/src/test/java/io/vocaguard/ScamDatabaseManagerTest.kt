package io.vocaguard

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.data.ScamInfo
import io.vocaguard.data.ScamType
import io.vocaguard.data.db.VocaGuardDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ScamDatabaseManagerTest {

    private lateinit var manager: ScamDatabaseManager
    private lateinit var db: VocaGuardDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, VocaGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Inject new DB first, then force a fresh ScamDatabaseManager so its DAO
        // points to this test's database, not a stale one from a previous test.
        VocaGuardDatabase.setTestInstance(db)
        ScamDatabaseManager.resetInstance()
        manager = ScamDatabaseManager.getInstance(context)
        manager.resetForTesting()
    }

    // --- checkNumber ---

    @Test
    fun `checkNumber returns clean ScamInfo for unknown number`() {
        // Use a number with no sequential or repeated-digit patterns
        val result = manager.checkNumber("5550001111")
        assertFalse(result.isKnownScammer)
        assertFalse(result.isSuspicious)
    }

    @Test
    fun `checkNumber returns known scammer for reported number`() {
        runBlocking { manager.reportScamNumber("5551234567", ScamType.IRS_SCAM) }

        val result = manager.checkNumber("5551234567")
        assertTrue(result.isKnownScammer)
        assertEquals(ScamType.IRS_SCAM, result.scamType)
    }

    @Test
    fun `checkNumber cleans phone number formatting`() {
        // cleanPhoneNumber() strips all non-digit characters; report the bare number
        // and verify that the same digits in different punctuation formats all match.
        runBlocking { manager.reportScamNumber("5551234567", ScamType.BANK_FRAUD) }

        val result1 = manager.checkNumber("555 123 4567")   // spaces → "5551234567"
        val result2 = manager.checkNumber("555-123-4567")   // hyphens → "5551234567"
        val result3 = manager.checkNumber("(555) 123-4567") // parens  → "5551234567"

        assertTrue(result1.isKnownScammer)
        assertTrue(result2.isKnownScammer)
        assertTrue(result3.isKnownScammer)
    }

    @Test
    fun `checkNumber flags repeated digits as suspicious`() {
        val result = manager.checkNumber("1111111111")
        assertTrue(result.isSuspicious)
    }

    @Test
    fun `checkNumber flags sequential digits as suspicious`() {
        val result = manager.checkNumber("1234567890")
        assertTrue(result.isSuspicious)
    }

    // --- reportScamNumber ---

    @Test
    fun `reportScamNumber increments report count`() {
        runBlocking {
            manager.reportScamNumber("5559876543", ScamType.TECH_SUPPORT)
            manager.reportScamNumber("5559876543", ScamType.TECH_SUPPORT)
        }

        val result = manager.checkNumber("5559876543")
        assertEquals(2, result.reportCount)
    }

    @Test
    fun `reportScamNumber updates scam type on re-report`() {
        runBlocking {
            manager.reportScamNumber("5559876543", ScamType.ROBOCALL)
            manager.reportScamNumber("5559876543", ScamType.PHISHING)
        }

        val result = manager.checkNumber("5559876543")
        assertEquals(ScamType.PHISHING, result.scamType)
    }

    // --- Whitelist ---

    @Test
    fun `whitelisted number is not flagged even if in scam database`() {
        runBlocking { manager.reportScamNumber("5551112222", ScamType.IRS_SCAM) }
        runBlocking { manager.addToWhitelist("5551112222") }

        val result = manager.checkNumber("5551112222")
        assertFalse(result.isKnownScammer)
        assertFalse(result.isSuspicious)
    }

    @Test
    fun `isWhitelisted returns true after adding`() {
        runBlocking { manager.addToWhitelist("5553334444") }
        assertTrue(manager.isWhitelisted("5553334444"))
    }

    @Test
    fun `removeFromWhitelist works`() {
        runBlocking { manager.addToWhitelist("5553334444") }
        manager.removeFromWhitelist("5553334444")
        assertFalse(manager.isWhitelisted("5553334444"))
    }

    @Test
    fun `whitelist cleans phone number formatting`() {
        runBlocking { manager.addToWhitelist("(555) 333-4444") } // strips to "5553334444"
        assertTrue(manager.isWhitelisted("5553334444"))
    }

    // --- Monitoring ---

    @Test
    fun `markCallForMonitoring sets activeCallPhoneNumber`() {
        manager.markCallForMonitoring("5550001111", isSuspicious = true)
        assertEquals("15550001111", manager.activeCallPhoneNumber)
        assertTrue(manager.isCallBeingMonitored("5550001111"))
    }

    @Test
    fun `stopMonitoringCall clears active call`() {
        manager.markCallForMonitoring("5550001111", isSuspicious = false)
        manager.stopMonitoringCall("5550001111")
        assertEquals("", manager.activeCallPhoneNumber)
        assertFalse(manager.isCallBeingMonitored("5550001111"))
    }
}
