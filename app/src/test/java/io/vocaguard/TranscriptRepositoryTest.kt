package io.vocaguard

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.data.CallTranscript
import io.vocaguard.data.TranscriptRepository
import io.vocaguard.data.db.VocaGuardDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class TranscriptRepositoryTest {

    private lateinit var db: VocaGuardDatabase
    private lateinit var repository: TranscriptRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, VocaGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TranscriptRepository.forTesting(db.transcriptDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `loadAll returns empty list when no transcripts saved`() = runBlocking {
        assertTrue(repository.loadAll().isEmpty())
    }

    @Test
    fun `save and loadAll returns saved transcript`() = runBlocking {
        val transcript = CallTranscript(
            id = 1L,
            timestamp = 1000L,
            text = "This is a test call",
            phoneNumber = "5551234567"
        )
        repository.save(transcript)

        val loaded = repository.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("This is a test call", loaded[0].text)
        assertEquals("5551234567", loaded[0].phoneNumber)
    }

    @Test
    fun `transcripts are returned newest first`() = runBlocking {
        repository.save(CallTranscript(id = 1L, timestamp = 1000L, text = "first"))
        repository.save(CallTranscript(id = 2L, timestamp = 2000L, text = "second"))
        repository.save(CallTranscript(id = 3L, timestamp = 3000L, text = "third"))

        val loaded = repository.loadAll()
        assertEquals("third", loaded[0].text)
        assertEquals("second", loaded[1].text)
        assertEquals("first", loaded[2].text)
    }

    @Test
    fun `save persists scam types`() = runBlocking {
        val transcript = CallTranscript(
            id = 1L,
            text = "test",
            detectedScamTypes = listOf("IRS_SCAM", "INVESTMENT_SCAM")
        )
        repository.save(transcript)

        val loaded = repository.loadAll()[0]
        assertEquals(listOf("IRS_SCAM", "INVESTMENT_SCAM"), loaded.detectedScamTypes)
    }

    @Test
    fun `delete removes only the specified transcript`() = runBlocking {
        repository.save(CallTranscript(id = 1L, text = "keep this"))
        repository.save(CallTranscript(id = 2L, text = "delete this"))

        repository.delete(2L)

        val loaded = repository.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("keep this", loaded[0].text)
    }

    @Test
    fun `clearAll removes all transcripts`() = runBlocking {
        repository.save(CallTranscript(id = 1L, text = "one"))
        repository.save(CallTranscript(id = 2L, text = "two"))

        repository.clearAll()

        assertTrue(repository.loadAll().isEmpty())
    }

    @Test
    fun `save keeps only the 500 most recent transcripts`() = runBlocking {
        for (i in 1..505) {
            repository.save(CallTranscript(id = i.toLong(), timestamp = i.toLong() * 1000, text = "transcript $i"))
        }

        val loaded = repository.loadAll()
        assertEquals(500, loaded.size)
        assertEquals("transcript 505", loaded[0].text)
        assertEquals("transcript 6", loaded[499].text)
    }

    @Test
    fun `save with empty scam types list persists correctly`() = runBlocking {
        repository.save(CallTranscript(id = 1L, text = "clean call", detectedScamTypes = emptyList()))
        val loaded = repository.loadAll()[0]
        assertTrue(loaded.detectedScamTypes.isEmpty())
    }

    @Test
    fun `markAsFalsePositive sets isFalsePositive flag`() = runBlocking {
        repository.save(CallTranscript(
            id = 1L, text = "scam call", detectedScamTypes = listOf("IRS_SCAM")
        ))
        repository.markAsFalsePositive(1L)

        val loaded = repository.loadAll()[0]
        assertTrue("isFalsePositive should be true after marking", loaded.isFalsePositive)
    }

    @Test
    fun `countScamsSince excludes false-positive entries`() = runBlocking {
        val since = 0L
        repository.save(CallTranscript(
            id = 1L, timestamp = 1000L, text = "real scam", detectedScamTypes = listOf("IRS_SCAM")
        ))
        repository.save(CallTranscript(
            id = 2L, timestamp = 2000L, text = "false positive", detectedScamTypes = listOf("ROBOCALL")
        ))
        repository.markAsFalsePositive(2L)

        val count = repository.countScamsSince(since)
        assertEquals("Only non-false-positive scam calls should be counted", 1, count)
    }

    @Test
    fun `countScamsSince excludes clean calls`() = runBlocking {
        val since = 0L
        repository.save(CallTranscript(id = 1L, text = "clean", detectedScamTypes = emptyList()))
        repository.save(CallTranscript(
            id = 2L, text = "scam", detectedScamTypes = listOf("BANK_FRAUD")
        ))

        val count = repository.countScamsSince(since)
        assertEquals(1, count)
    }
}
