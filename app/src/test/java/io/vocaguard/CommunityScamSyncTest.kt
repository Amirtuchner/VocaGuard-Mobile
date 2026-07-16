package io.vocaguard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.data.CommunityScamSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [CommunityScamSync], covering cache-TTL logic, URL persistence,
 * and the cache-skip fast path.
 *
 * Network-hitting tests are excluded because the fallback URL may succeed on CI
 * (GitHub Actions has internet) and then ScamDatabaseManager/Room initialization
 * fails in Robolectric, making the tests environment-dependent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CommunityScamSyncTest {

    private lateinit var context: Context
    private lateinit var sync: CommunityScamSync

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CommunityScamSync.resetInstance()
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().clear().commit()
        sync = CommunityScamSync.getInstance(context)
    }

    // -------------------------------------------------------------------------
    // Cache-freshness logic
    // -------------------------------------------------------------------------

    @Test
    fun `isCacheFresh is false when never synced`() {
        assertFalse(sync.isCacheFresh)
    }

    @Test
    fun `isCacheFresh is false after lastSyncMs is set to a time 25 hours ago`() {
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ms", twentyFiveHoursAgo).commit()
        assertFalse(sync.isCacheFresh)
    }

    @Test
    fun `isCacheFresh is true when last sync was 1 hour ago`() {
        val oneHourAgo = System.currentTimeMillis() - 1L * 60 * 60 * 1000
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ms", oneHourAgo).commit()
        assertTrue(sync.isCacheFresh)
    }

    // -------------------------------------------------------------------------
    // syncUrl persistence
    // -------------------------------------------------------------------------

    @Test
    fun `syncUrl defaults to the GitHub raw URL`() {
        assertTrue(sync.syncUrl.startsWith("https://"))
        assertTrue(sync.syncUrl.contains("blocklist"))
    }

    @Test
    fun `syncUrl can be updated and is persisted in SharedPreferences`() {
        val custom = "https://example.com/scam-list.json"
        sync.syncUrl = custom
        assertEquals(custom, sync.syncUrl)
    }

    // -------------------------------------------------------------------------
    // sync() fast-path: cache fresh, no force
    // -------------------------------------------------------------------------

    @Test
    fun `sync returns 0 when cache is fresh and force is false`() = runBlocking {
        val oneHourAgo = System.currentTimeMillis() - 1L * 60 * 60 * 1000
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ms", oneHourAgo).commit()
        val result = sync.sync(force = false)
        assertEquals(0, result)
    }

    @Test
    fun `sync skips fetch when cache is fresh and force is false`() = runBlocking {
        val oneHourAgo = System.currentTimeMillis() - 1L * 60 * 60 * 1000
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ms", oneHourAgo).commit()
        // Even with an invalid URL, result is 0 because cache is fresh
        sync.syncUrl = "https://192.0.2.1:1/no-server.json"
        val result = sync.sync(force = false)
        assertEquals(0, result)
    }

    // -------------------------------------------------------------------------
    // lastSyncMs
    // -------------------------------------------------------------------------

    @Test
    fun `lastSyncMs is 0 before any sync`() {
        assertEquals(0L, sync.lastSyncMs)
    }
}
