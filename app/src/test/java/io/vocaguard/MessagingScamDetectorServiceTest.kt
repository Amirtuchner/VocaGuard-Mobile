package io.vocaguard

import android.app.Notification
import android.os.Bundle
import io.vocaguard.service.MessagingScamDetectorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessagingScamDetectorServiceTest {

    // Helper: build a Notification with arbitrary extras
    private fun notificationWithExtras(block: Bundle.() -> Unit): Notification {
        val extras = Bundle().apply(block)
        return Notification.Builder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            "test"
        ).build().also {
            it.extras.putAll(extras)
        }
    }

    // --- MessagingStyle (EXTRA_MESSAGES) ---

    @Test
    fun `extracts only the last message from MessagingStyle`() {
        // Only the newest (last) message should be analysed to prevent keyword accumulation
        // across conversation history from inflating the scam score (false positives).
        val msg1 = Bundle().apply { putCharSequence("text", "Hello there") }
        val msg2 = Bundle().apply { putCharSequence("text", "Send me gift cards") }
        val notification = notificationWithExtras {
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(msg1, msg2))
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertEquals("Send me gift cards", result)
    }

    @Test
    fun `skips MessagingStyle when all messages have blank text`() {
        val msg = Bundle().apply { putCharSequence("text", "   ") }
        val notification = notificationWithExtras {
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(msg))
            putCharSequence(Notification.EXTRA_BIG_TEXT, "Fallback big text")
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertEquals("Fallback big text", result)
    }

    // --- BigTextStyle fallback ---

    @Test
    fun `extracts BigText when no MessagingStyle messages`() {
        val notification = notificationWithExtras {
            putCharSequence(Notification.EXTRA_BIG_TEXT, "This is a long scam message body")
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertEquals("This is a long scam message body", result)
    }

    // --- Title + body fallback ---

    @Test
    fun `extracts title and body when no MessagingStyle or BigText`() {
        val notification = notificationWithExtras {
            putCharSequence(Notification.EXTRA_TITLE, "John")
            putCharSequence(Notification.EXTRA_TEXT, "You won a prize!")
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertEquals("John You won a prize!", result)
    }

    @Test
    fun `extracts body only when title is absent`() {
        val notification = notificationWithExtras {
            putCharSequence(Notification.EXTRA_TEXT, "Click this link now")
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertEquals("Click this link now", result)
    }

    // --- Empty / null cases ---

    @Test
    fun `returns null when all extras are blank`() {
        val notification = notificationWithExtras {
            putCharSequence(Notification.EXTRA_TITLE, "  ")
            putCharSequence(Notification.EXTRA_TEXT, "  ")
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertNull(result)
    }

    @Test
    fun `returns null when notification has no relevant extras`() {
        val notification = notificationWithExtras { /* empty */ }

        val result = MessagingScamDetectorService.extractText(notification)
        assertNull(result)
    }

    // --- Priority order ---

    @Test
    fun `prefers MessagingStyle over BigText`() {
        val msg = Bundle().apply { putCharSequence("text", "Messaging text") }
        val notification = notificationWithExtras {
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(msg))
            putCharSequence(Notification.EXTRA_BIG_TEXT, "Big text should be ignored")
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertEquals("Messaging text", result)
    }

    @Test
    fun `prefers BigText over title+body`() {
        val notification = notificationWithExtras {
            putCharSequence(Notification.EXTRA_BIG_TEXT, "Big text wins")
            putCharSequence(Notification.EXTRA_TITLE, "Title")
            putCharSequence(Notification.EXTRA_TEXT, "Body")
        }

        val result = MessagingScamDetectorService.extractText(notification)
        assertEquals("Big text wins", result)
    }
}
