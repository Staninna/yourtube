package com.staninna.yourtube.web

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenOrientationTest {

    @Test fun theScreenTurnsToTheVideosShape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, FullscreenOrientation.of("[1920,1080]"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, FullscreenOrientation.of("[1080,1920]"))
    }

    @Test fun squareOrUnknownLeavesItAlone() {
        assertNull(FullscreenOrientation.of("[720,720]"))
        assertNull("no video yet", FullscreenOrientation.of("null"))
        assertNull(FullscreenOrientation.of(null))
        assertNull(FullscreenOrientation.of("\"[1920,1080]\""))
    }

    @Test fun theVideoShowsOnceTheScreenHasTurned() {
        val landscape = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val portrait = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        assertFalse(FullscreenOrientation.fits(landscape, 1080, 2400))
        assertTrue(FullscreenOrientation.fits(landscape, 2400, 1080))
        assertTrue(FullscreenOrientation.fits(portrait, 1080, 2400))
        assertFalse("not laid out yet", FullscreenOrientation.fits(portrait, 0, 0))
        assertTrue("no turn asked for", FullscreenOrientation.fits(null, 0, 0))
    }
}
