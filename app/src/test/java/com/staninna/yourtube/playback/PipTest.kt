package com.staninna.yourtube.playback

import com.staninna.yourtube.data.PipMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D4's picture-in-picture entry rows, and the window's shape from the video's place on screen. */
class PipTest {

    private fun auto(
        mode: PipMode = PipMode.AUTO,
        support: PipSupport = PipSupport.AVAILABLE,
        playing: Boolean = true,
        shown: Boolean = true,
        fullscreen: Boolean = false,
    ) = PipPolicy.autoEnter(mode, support, playing, shown, fullscreen)

    // --- entering ---

    @Test fun leavingWhileWatchShowsThePlayingVideoEntersPip() = assertTrue(auto())

    @Test fun aPausedVideoLeavesWithTheNotificationInstead() {
        assertFalse(auto(playing = false))
        assertFalse(auto(playing = false, fullscreen = true))
    }

    @Test fun aVideoUnderANativeTabOrSettingsPlaysOnAsSound() {
        assertFalse(auto(shown = false))
        // YouTube's fullscreen lies over everything, so it always shows the video.
        assertTrue(auto(shown = false, fullscreen = true))
    }

    @Test fun onlyFromFullscreenNeedsFullscreen() {
        assertFalse(auto(mode = PipMode.FULLSCREEN))
        assertTrue(auto(mode = PipMode.FULLSCREEN, fullscreen = true))
    }

    @Test fun manualNeverEntersOnLeaving() {
        assertFalse(auto(mode = PipMode.MANUAL))
        assertFalse(auto(mode = PipMode.MANUAL, fullscreen = true))
    }

    @Test fun withoutSupportLeavingIsStage7sBackgroundAudio() {
        for (support in listOf(PipSupport.NO_FEATURE, PipSupport.TURNED_OFF)) {
            for (mode in PipMode.entries) assertFalse("$support $mode", auto(mode = mode, support = support, fullscreen = true))
            assertFalse(PipPolicy.offersEntry(PipMode.MANUAL, support, playing = true))
        }
    }

    @Test fun watchsMenuOffersPipOnlyInManualWhilePlaying() {
        assertTrue(PipPolicy.offersEntry(PipMode.MANUAL, PipSupport.AVAILABLE, playing = true))
        assertFalse(PipPolicy.offersEntry(PipMode.MANUAL, PipSupport.AVAILABLE, playing = false))
        assertFalse(PipPolicy.offersEntry(PipMode.AUTO, PipSupport.AVAILABLE, playing = true))
        assertFalse(PipPolicy.offersEntry(PipMode.FULLSCREEN, PipSupport.AVAILABLE, playing = true))
    }

    // --- aspect ratio ---

    @Test fun aspectRatioIsTheVideosWithinAndroidsLimits() {
        assertEquals(1920 to 1080, PipShape.aspect(1920, 1080))
        assertEquals(1080 to 1920, PipShape.aspect(1080, 1920))
        assertEquals(239 to 100, PipShape.aspect(239, 100))
        assertEquals(100 to 239, PipShape.aspect(100, 239))
        // Wider or taller than 2.39:1 is clamped to it.
        assertEquals(239 to 100, PipShape.aspect(2560, 1000))
        assertEquals(100 to 239, PipShape.aspect(500, 2000))
    }

    // --- CSS px to window px ---

    @Test fun aCssBoxScalesFromTheWebViewsOrigin() {
        // A 412 x 232 CSS px player at the top of a 1080 px WebView 279 px down the window, its viewport 412 CSS px wide.
        assertEquals(PxRect(0, 279, 1080, 887), PipShape.toWindow(0.0, 0.0, 412.0, 232.0, 1080 / 412.0, originX = 0, originY = 279))
        assertEquals(PxRect(110, 330, 210, 380), PipShape.toWindow(5.0, 0.5, 55.0, 25.5, 2.0, originX = 100, originY = 329))
    }

    // --- the source rect ---

    private val window = PxRect(0, 0, 1080, 2400)

    @Test fun aVideoFillingItsBoxGrowsFromTheWholeBox() {
        val box = PxRect(0, 300, 960, 840)
        assertEquals(PipShape(1920, 1080, box), PipShape.of(1920, 1080, box, window))
    }

    @Test fun theSourceRectIsThePictureNotItsLetterbox() {
        val box = PxRect(0, 300, 1080, 908)
        // A tall video stands in the middle of a wide box: pillarboxed.
        assertEquals(PxRect(369, 300, 711, 908), PipShape.of(1080, 1920, box, window)!!.sourceRect)
        // A very wide one lies across it: letterboxed.
        assertEquals(PxRect(0, 378, 1080, 829), PipShape.of(2390, 1000, box, window)!!.sourceRect)
    }

    @Test fun aFourByThreeVideoGetsAFourByThreeWindowFromItsPillarboxedPicture() {
        // A 640 x 480 stream in the 16:9 player at the top of the app.
        val shape = PipShape.of(640, 480, PxRect(0, 279, 1080, 886), window)!!
        assertEquals(640 to 480, shape.aspectWidth to shape.aspectHeight)
        assertEquals(PxRect(135, 279, 944, 886), shape.sourceRect)
    }

    @Test fun aClampedRatioCropsTheSourceRectToMatch() {
        val box = PxRect(0, 0, 1080, 300)
        val shape = PipShape.of(4000, 1000, box, window)!!
        assertEquals(239 to 100, shape.aspectWidth to shape.aspectHeight)
        // 4:1 picture 1080 x 270 in the box, cropped to 2.39:1 around its middle: 645 x 270.
        assertEquals(PxRect(217, 15, 862, 285), shape.sourceRect)
    }

    @Test fun aPictureScrolledPartlyAwayHasNoSourceRect() {
        val shape = PipShape.of(1920, 1080, PxRect(0, -200, 1080, 408), window)!!
        assertEquals(1920 to 1080, shape.aspectWidth to shape.aspectHeight)
        assertNull(shape.sourceRect)
    }

    @Test fun aVideoWithoutASizeHasNoShape() {
        assertNull(PipShape.of(0, 0, PxRect(0, 0, 100, 100), window))
    }
}
