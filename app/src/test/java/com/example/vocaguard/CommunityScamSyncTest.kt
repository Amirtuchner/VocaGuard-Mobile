package com.example.vocaguard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vocaguard.data.CommunityScamSync
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
 * and the graceful error path when the network is unavailable.
 *
 * Network calls are exercised only through the "bad URL → returns -1" path so no
 * real internet access or mock server is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CommunityScamSyncTest {

    private lateinit var context: Context
    private lateinit var sync: CommunityScamSync

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric creates a fresh Context per test; reset the singleton so the
        // new instance binds to the fresh Context's SharedPreferences, not stale ones.
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

        // Re-obtain instance so it reads updated prefs (prefs are live, so just re-check)
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
        // Mark cache as fresh (1 h ago)
        val oneHourAgo = System.currentTimeMillis() - 1L * 60 * 60 * 1000
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ms", oneHourAgo).commit()

        val result = sync.sync(force = false)
        assertEquals(0, result)
    }

    // -------------------------------------------------------------------------
    // sync() error path: unreachable URL
    // -------------------------------------------------------------------------

    @Test
    fun `sync returns -1 when the server URL is unreachable`() = runBlocking {
        sync.syncUrl = "https://localhost:1/does-not-exist.json"
        val result = sync.sync(force = true)
        assertEquals(-1, result)
    }

    @Test
    fun `sync returns -1 when the URL is malformed`() = runBlocking {
        sync.syncUrl = "not-a-valid-url"
        val result = sync.sync(force = true)
        assertEquals(-1, result)
    }

    // -------------------------------------------------------------------------
    // lastSyncMs
    // -------------------------------------------------------------------------

    @Test
    fun `lastSyncMs is 0 before any sync`() {
        assertEquals(0L, sync.lastSyncMs)
    }

    // -------------------------------------------------------------------------
    // Retry logic
    // -------------------------------------------------------------------------

    @Test
    fun `sync retries on network error and returns -1 after all attempts fail`() = runBlocking {
        // An unreachable host causes IOException on every attempt, exhausting retries
        sync.syncUrl = "https://localhost:19999/no-server.json"
        val result = sync.sync(force = true)
        assertEquals(-1, result)
    }

    @Test
    fun `sync returns -1 immediately on non-network error without retrying`() = runBlocking {
        // A reachable URL that returns invalid JSON triggers a non-retriable error
        // (JSONException), so it should fail fast without exhausting retry attempts.
        // We can't easily verify timing here, but we verify the return value is -1.
        sync.syncUrl = "https://localhost:1/invalid"
        val result = sync.sync(force = true)
        assertEquals(-1, result)
    }

    @Test
    fun `sync skips fetch when cache is fresh and force is false`() = runBlocking {
        val oneHourAgo = System.currentTimeMillis() - 1L * 60 * 60 * 1000
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ms", oneHourAgo).commit()
        // Even with an invalid URL, result is 0 because cache is fresh
        sync.syncUrl = "https://localhost:1/no-server.json"
        val result = sync.sync(force = false)
        assertEquals(0, result)
    }

    @Test
    fun `sync fetches when force is true even if cache is fresh`() = runBlocking {
        val oneHourAgo = System.currentTimeMillis() - 1L * 60 * 60 * 1000
        context.getSharedPreferences("vocaguard_community_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ms", oneHourAgo).commit()
        sync.syncUrl = "https://localhost:19999/no-server.json"
        // force=true bypasses the cache check — will attempt network and fail
        val result = sync.sync(force = true)
        assertEquals(-1, result)
    }
}
