package com.staninna.yourtube

import android.app.Application
import android.webkit.WebView
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.TubeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end UI test for the app shell: it renders the real [TubeRoot] with a live [MainViewModel]
 * and WebView, then drives the bottom navigation. It never loads youtube.com, so it stays offline
 * and deterministic; the WebView is only there because the shell always keeps it attached.
 */
class TubeRootTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun cleanStore() = Store.resetForTest(app)

    private fun setContent() {
        val vm = MainViewModel(app)
        compose.setContent {
            val ctx = LocalContext.current
            val webView = remember { WebView(ctx) }
            TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, webView = webView) }
        }
    }

    @Test fun starredItemsAndHistoryShowOnTheirTabs() {
        Store.toggleStar(
            StarredItem(
                id = "UC1", type = ItemType.CHANNEL, title = "Example Channel",
                url = "https://m.youtube.com/channel/UC1", channelId = "UC1",
            ),
        )
        Store.recordWatch(HistoryEntry(videoId = "VID1", title = "A watched video", url = "u/VID1"))
        setContent()

        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Example Channel").assertIsDisplayed()

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("A watched video").assertIsDisplayed()
    }

    @Test fun emptyTabsShowTheirGuidance() {
        setContent()

        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Star channels and videos to see them here.").assertIsDisplayed()

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Videos you watch show up here.").assertIsDisplayed()
    }
}
