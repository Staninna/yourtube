package com.staninna.yourtube.web

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeLevelTest {

    @Test fun aSwipeMovesTheLevelByItsShareOfThePlayer() {
        assertEquals(0.75, SwipeLevel.of(0.5, 0.25), 1e-9)
        assertEquals(0.25, SwipeLevel.of(0.5, -0.25), 1e-9)
    }

    @Test fun theLevelStaysBetweenNoneAndFull() {
        assertEquals(1.0, SwipeLevel.of(0.9, 0.5), 0.0)
        assertEquals(0.0, SwipeLevel.of(0.1, -2.0), 0.0)
    }

    @Test fun theLabelSaysWhatAndHowMuch() {
        assertEquals("Volume 40%", SwipeLevel.label(Swipe.Control.VOLUME, 0.4))
        assertEquals("Brightness 100%", SwipeLevel.label(Swipe.Control.BRIGHTNESS, 1.2))
    }
}
