package io.vocaguard

import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.data.ScamType
import io.vocaguard.data.db.VocaGuardDatabase
import io.vocaguard.receiver.BlockCallerReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BlockCallerReceiverTest {

    private lateinit var context: Application
    private lateinit var manager: ScamDatabaseManager
    private lateinit var receiver: BlockCallerReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, VocaGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        VocaGuardDatabase.setTestInstance(db)
        ScamDatabaseManager.resetInstance()
        manager = ScamDatabaseManager.getInstance(context)
        manager.resetForTesting()
        receiver = BlockCallerReceiver()
    }

    @Test
    fun `onReceive ignores intent with wrong action`() {
        val intent = Intent("io.vocaguard.WRONG_ACTION").apply {
            putExtra(BlockCallerReceiver.EXTRA_PHONE_NUMBER, "5551234567")
            putExtra(BlockCallerReceiver.EXTRA_SCAM_TYPE, ScamType.IRS_SCAM.name)
        }
        // Must not throw; wrong action is silently ignored.
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive ignores intent with missing phone number`() {
        val intent = Intent(BlockCallerReceiver.ACTION_BLOCK_CALLER)
        // No EXTRA_PHONE_NUMBER — must not throw.
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive reports the number with correct scam type`() {
        val intent = Intent(BlockCallerReceiver.ACTION_BLOCK_CALLER).apply {
            putExtra(BlockCallerReceiver.EXTRA_PHONE_NUMBER, "5559876543")
            putExtra(BlockCallerReceiver.EXTRA_SCAM_TYPE, ScamType.BANK_FRAUD.name)
        }
        receiver.onReceive(context, intent)

        // Give the coroutine time to complete (goAsync is used internally).
        Thread.sleep(200)

        val result = manager.checkNumber("5559876543")
        assertTrue(result.isKnownScammer)
        assertEquals(ScamType.BANK_FRAUD, result.scamType)
    }

    @Test
    fun `onReceive falls back to UNKNOWN when scam type is invalid`() {
        val intent = Intent(BlockCallerReceiver.ACTION_BLOCK_CALLER).apply {
            putExtra(BlockCallerReceiver.EXTRA_PHONE_NUMBER, "5550001111")
            putExtra(BlockCallerReceiver.EXTRA_SCAM_TYPE, "NOT_A_REAL_TYPE")
        }
        receiver.onReceive(context, intent)
        Thread.sleep(200)

        val result = manager.checkNumber("5550001111")
        assertTrue(result.isKnownScammer)
        assertEquals(ScamType.UNKNOWN, result.scamType)
    }

    @Test
    fun `onReceive falls back to UNKNOWN when scam type extra is absent`() {
        val intent = Intent(BlockCallerReceiver.ACTION_BLOCK_CALLER).apply {
            putExtra(BlockCallerReceiver.EXTRA_PHONE_NUMBER, "5550002222")
            // No EXTRA_SCAM_TYPE
        }
        receiver.onReceive(context, intent)
        Thread.sleep(200)

        val result = manager.checkNumber("5550002222")
        assertTrue(result.isKnownScammer)
        assertEquals(ScamType.UNKNOWN, result.scamType)
    }
}
