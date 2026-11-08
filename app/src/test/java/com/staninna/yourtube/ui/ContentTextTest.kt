package com.staninna.yourtube.ui

import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.SponsorBlockSettings
import com.staninna.yourtube.UiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentTextTest {

    @Test fun skipNoticesNameTheCategoryAndOfferUndo() {
        assertEquals(Notice("Skipped sponsor", "Undo"), Notices.of(UiEvent.SegmentSkipped("v", SegmentCategory.SPONSOR, 1.0)))
        assertEquals("Skipped self-promotion", ContentText.skipped(SegmentCategory.SELF_PROMO))
        assertEquals("Skipped non-music section", ContentText.skipped(SegmentCategory.MUSIC_OFFTOPIC))
    }

    @Test fun theSegmentsSummarySaysWhatHappens() {
        val defaults = SponsorBlockSettings()
        assertEquals("Skips sponsor and self-promotion. Marks 6 categories.", ContentText.segments(defaults))
        val muteIntro = defaults.with(SegmentCategory.INTRO, SegmentAction.MUTE).with(SegmentCategory.SELF_PROMO, SegmentAction.OFF)
        assertEquals("Skips sponsor. Mutes intro. Marks 5 categories.", ContentText.segments(muteIntro))
        val allOff = SegmentCategory.entries.fold(defaults) { s, c -> s.with(c, SegmentAction.OFF) }
        assertEquals("Every category is off", ContentText.segments(allOff))
        val allShown = SegmentCategory.entries.fold(defaults) { s, c -> s.with(c, SegmentAction.SHOW) }
        assertEquals("Marks every category.", ContentText.segments(allShown))
    }

    @Test fun theServerSummaryIsItsHost() {
        assertEquals("sponsor.ajay.app (default)", ContentText.server(SponsorBlockSettings()))
        assertEquals("sb.example.org", ContentText.server(SponsorBlockSettings(server = "https://sb.example.org/mirror")))
    }

    @Test fun everyLayerAndCategoryHasText() {
        AdLayer.entries.forEach { assertTrue(ContentText.layer(it).summary.isNotBlank()) }
        SegmentCategory.entries.forEach { assertTrue(ContentText.category(it).title.isNotBlank()) }
        assertEquals(listOf("Skip", "Mute", "Show", "Off"), ContentText.actions)
    }
}
