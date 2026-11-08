package com.staninna.yourtube

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.notify.NewVideoSchedule
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Settings > Feed's New video notifications switch, with the permission granted, and its schedule. */
class NotifyFlowsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }

    private fun setContent() {
        val vm = MainViewModel(
            store,
            ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            FeedFetcher { _, _ -> ChannelFetch.Ok(emptyList(), FeedSource.RSS) },
            SegmentSource { _, _ -> emptyList() },
            testHub(),
        )
        lateinit var host: WebViewHost
        compose.runOnUiThread { host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {}) }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
        compose.runOnUiThread { vm.selectTab(Tab.WATCH) }
    }

    private fun theSwitch() =
        compose.onNode(hasText("New video notifications") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))

    @Test fun theSwitchStartsOffAndTurnsTheCheckOnAndOff() {
        setContent()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        theSwitch().performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { store.settings.value.newVideoNotifications }
        assertTrue("turning it on starts the seen state over", store.notify.value.since > 0 && store.notify.value.seen.isEmpty())
        theSwitch().assertIsOn().performClick()
        compose.waitUntil(5_000) { !store.settings.value.newVideoNotifications }
    }

    @Test fun theScheduleIsOneHourlyJobThatTurningOffCancels() {
        val context = compose.activity.applicationContext
        val work = WorkManager.getInstance(context)
        NewVideoSchedule.apply(context, true)
        NewVideoSchedule.apply(context, true)
        val infos = work.getWorkInfosForUniqueWork(NewVideoSchedule.WORK_NAME).get()
        assertEquals("one job however often the app starts", 1, infos.count { !it.state.isFinished })
        assertEquals(60 * 60 * 1000L, infos.single { !it.state.isFinished }.periodicityInfo?.repeatIntervalMillis)

        NewVideoSchedule.apply(context, false)
        val after = work.getWorkInfosForUniqueWork(NewVideoSchedule.WORK_NAME).get()
        assertTrue(after.all { it.state == WorkInfo.State.CANCELLED })
    }
}
