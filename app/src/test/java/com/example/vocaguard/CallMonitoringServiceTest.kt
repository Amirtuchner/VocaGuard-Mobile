package com.example.vocaguard

import android.app.Application
import android.content.Intent
import com.example.vocaguard.service.CallMonitoringService
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallMonitoringServiceTest {

    @Test
    fun `service can be created`() {
        val controller = Robolectric.buildService(CallMonitoringService::class.java).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test
    fun `START_MONITORING intent sets isMonitoring without crashing`() {
        val intent = Intent(CallMonitoringService.ACTION_START_MONITORING)
        val controller = Robolectric.buildService(CallMonitoringService::class.java, intent)
            .create()
            .startCommand(0, 1)
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test
    fun `STOP_MONITORING intent handled without crashing`() {
        val startIntent = Intent(CallMonitoringService.ACTION_START_MONITORING)
        val controller = Robolectric.buildService(CallMonitoringService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        val stopIntent = Intent(CallMonitoringService.ACTION_STOP_MONITORING)
        controller.withIntent(stopIntent).startCommand(0, 2)
        controller.destroy()
    }

    @Test
    fun `onBind returns null (not a bound service)`() {
        val controller = Robolectric.buildService(CallMonitoringService::class.java).create()
        val binder = controller.get().onBind(null)
        assert(binder == null)
        controller.destroy()
    }

    @Test
    fun `unknown intent action does not crash`() {
        val intent = Intent("com.example.vocaguard.UNKNOWN_ACTION")
        val controller = Robolectric.buildService(CallMonitoringService::class.java, intent)
            .create()
            .startCommand(0, 1)
        assertNotNull(controller.get())
        controller.destroy()
    }
}
