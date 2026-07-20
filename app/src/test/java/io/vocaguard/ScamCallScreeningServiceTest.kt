package io.vocaguard

import android.app.Application
import android.net.Uri
import android.telecom.Call
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.data.ScamType
import io.vocaguard.data.db.VocaGuardDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Unit tests for the screening logic in ScamCallScreeningService.
 *
 * The service delegates all decisions to ScamDatabaseManager, so these tests
 * drive that manager directly and verify the expected outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ScamCallScreeningServiceTest {

    private lateinit var db: VocaGuardDatabase
    private lateinit var manager: ScamDatabaseManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, VocaGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        VocaGuardDatabase.setTestInstance(db)
        ScamDatabaseManager.resetInstance()
        manager = ScamDatabaseManager.getInstance(context)
        manager.resetForTesting()
    }

    // --- Screening decision helpers ---

    /** Simulates the screening decision: block if known scammer, monitor otherwise. */
    private fun shouldBlock(phoneNumber: String): Boolean {
        val info = manager.checkNumber(phoneNumber)
        return info.isKnownScammer
    }

    private fun shouldMonitor(phoneNumber: String): Boolean {
        val info = manager.checkNumber(phoneNumber)
        return !info.isKnownScammer
    }

    // --- Tests ---

    @Test
    fun `known scammer number is blocked`() {
        runBlocking { manager.reportScamNumber("5551234567", ScamType.IRS_SCAM) }
        assertTrue(shouldBlock("5551234567"))
    }

    @Test
    fun `unknown number is not blocked`() {
        assertFalse(shouldBlock("5559998888"))
    }

    @Test
    fun `unknown number is marked for monitoring`() {
        assertTrue(shouldMonitor("5559998888"))
    }

    @Test
    fun `whitelisted known scammer is not blocked`() {
        runBlocking { manager.reportScamNumber("5551112222", ScamType.BANK_FRAUD) }
        runBlocking { manager.addToWhitelist("5551112222") }
        assertFalse(shouldBlock("5551112222"))
    }

    @Test
    fun `suspicious pattern number is not blocked but monitored`() {
        // Repeated-digit numbers are suspicious but not blocked (block requires explicit report)
        assertFalse(shouldBlock("1111111111"))
        assertTrue(shouldMonitor("1111111111"))
    }

    @Test
    fun `markCallForMonitoring records number`() {
        manager.markCallForMonitoring("5550001111", isSuspicious = false)
        assertTrue(manager.isCallBeingMonitored("5550001111"))
    }

    @Test
    fun `stopMonitoringCall clears monitoring state`() {
        manager.markCallForMonitoring("5550002222", isSuspicious = true)
        manager.stopMonitoringCall("5550002222")
        assertFalse(manager.isCallBeingMonitored("5550002222"))
    }
}
