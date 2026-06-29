package io.vocaguard

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.vocaguard.ui.PermissionsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PermissionsManagerTest {

    private lateinit var manager: PermissionsManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        manager = PermissionsManager(context)
    }

    @Test
    fun `checkAllPermissions returns map with all expected keys`() {
        val permissions = manager.checkAllPermissions()

        val expectedKeys = setOf(
            "Phone State",
            "Call Log",
            "Answer Calls",
            "Record Audio",
            "Notifications",
            "Contacts",
            "Call Screening",
            "Draw Overlay",
            "Notification Access"
        )
        assertTrue(
            "Missing keys: ${expectedKeys - permissions.keys}",
            permissions.keys.containsAll(expectedKeys)
        )
    }

    @Test
    fun `checkAllPermissions returns exactly 10 entries`() {
        val permissions = manager.checkAllPermissions()
        assertTrue("Expected 9 permission entries, got ${permissions.size}", permissions.size == 9)
    }

    @Test
    fun `all runtime permissions are denied in clean Robolectric environment`() {
        val permissions = manager.checkAllPermissions()
        // Runtime permissions (Phone State, Call Log, Answer Calls, Record Audio, Notifications)
        // are not granted by default in Robolectric.
        assertFalse("Phone State should not be granted", permissions["Phone State"] == true)
        assertFalse("Record Audio should not be granted", permissions["Record Audio"] == true)
    }

    @Test
    fun `REQUIRED_PERMISSIONS contains the core runtime permissions`() {
        val required = PermissionsManager.REQUIRED_PERMISSIONS
        assertTrue(required.contains(android.Manifest.permission.READ_PHONE_STATE))
        assertTrue(required.contains(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(required.contains(android.Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(required.contains(android.Manifest.permission.READ_CALL_LOG))
        assertTrue(required.contains(android.Manifest.permission.ANSWER_PHONE_CALLS))
    }

    @Test
    fun `checkAllPermissions does not throw`() {
        // Verify no exception escapes checkAllPermissions even in a stripped test context.
        try {
            manager.checkAllPermissions()
        } catch (e: Exception) {
            throw AssertionError("checkAllPermissions threw an unexpected exception", e)
        }
    }
}
