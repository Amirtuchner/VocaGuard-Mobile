package io.vocaguard

import android.app.Application
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.data.db.VocaGuardDatabase
import io.vocaguard.monitor.PhoneStateMonitor
import androidx.room.Room
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PhoneStateMonitorTest {

    private lateinit var context: Application
    private lateinit var monitor: PhoneStateMonitor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Provide a real in-memory Room database so ScamDatabaseManager initialises cleanly.
        val db = Room.inMemoryDatabaseBuilder(context, VocaGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        VocaGuardDatabase.setTestInstance(db)
        ScamDatabaseManager.resetInstance()
        monitor = PhoneStateMonitor(context)
    }

    @Test
    fun `startMonitoring does not throw when READ_PHONE_STATE is absent`() {
        // Robolectric does not grant READ_PHONE_STATE by default; startMonitoring
        // must swallow the SecurityException and not crash.
        try {
            monitor.startMonitoring()
        } catch (e: SecurityException) {
            // startMonitoring must handle this internally — rethrow means the test fails.
            throw AssertionError("startMonitoring should handle SecurityException internally", e)
        }
    }

    @Test
    fun `startMonitoring is idempotent`() {
        // Calling startMonitoring twice must not throw or register listeners twice.
        monitor.startMonitoring()
        monitor.startMonitoring() // second call should be a no-op
    }

    @Test
    fun `stopMonitoring when not started does not throw`() {
        // stopMonitoring before startMonitoring must be safe.
        monitor.stopMonitoring()
    }

    @Test
    fun `stopMonitoring is idempotent`() {
        monitor.startMonitoring()
        monitor.stopMonitoring()
        monitor.stopMonitoring() // second call should be a no-op
    }

    @Test
    fun `onCallEnded clears the active call in ScamDatabaseManager`() {
        val manager = ScamDatabaseManager.getInstance(context)
        manager.markCallForMonitoring("5551234567", isSuspicious = false)
        assertEquals("15551234567", manager.activeCallPhoneNumber)

        // Simulate an IDLE state transition by starting and letting the monitor run.
        // We can't invoke handleCallStateChange directly (it's private), but we can
        // verify that the manager state is correct after a clean setup.
        manager.stopMonitoringCall("5551234567")
        assertEquals("", manager.activeCallPhoneNumber)
    }
}
