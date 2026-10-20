package com.rfsat.pocketspectro

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.rfsat.pocketspectro.core.JobService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The background service posts an ongoing notification listing the running jobs, and Stop all stops them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobServiceTest {
    @Test fun notificationListsJobsAndStopAllStops() {
        val app = RuntimeEnvironment.getApplication() as SpectroApp
        val engine = app.engine
        engine.tools.lightDoseLogging(true)
        engine.tools.vegRecording(true)
        val controller = Robolectric.buildService(JobService::class.java).create().startCommand(0, 1)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = shadowOf(nm).allNotifications.single()
        val title = n.extras.getCharSequence("android.title").toString()
        println("notification: $title / ${n.extras.getCharSequence("android.text")}")
        assertEquals("PocketSpectro: 2 running", title)
        assertTrue(n.extras.getCharSequence("android.text").toString().contains("Light dose"))
        assertTrue(JobService.running)
        // "Stop all" from the notification.
        controller.withIntent(Intent(app, JobService::class.java).setAction(JobService.ACTION_STOP_ALL)).startCommand(0, 2)
        assertTrue(!engine.tools.lightDose.value.logging && !engine.tools.vegetation.value.recording)
        controller.destroy()
        assertTrue(!JobService.running)
    }
}
