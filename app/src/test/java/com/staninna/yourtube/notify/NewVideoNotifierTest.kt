package com.staninna.yourtube.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** When a new-video notification would show: the app's switch and the "New videos" channel's. */
@RunWith(RobolectricTestRunner::class)
class NewVideoNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun aMutedNewVideosChannelCountsAsBlocked() {
        NewVideoNotifier.ensureChannel(context)
        assertTrue(NewVideoNotifier.allowed(context))
        manager.createNotificationChannel(NotificationChannel(NewVideoNotifier.CHANNEL_ID, "New videos", NotificationManager.IMPORTANCE_NONE))
        assertFalse("checking hourly for a muted channel would be battery for nothing", NewVideoNotifier.allowed(context))
    }

    @Test fun theAppsNotificationsTurnedOffCountAsBlocked() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(NewVideoNotifier.allowed(context))
    }
}
