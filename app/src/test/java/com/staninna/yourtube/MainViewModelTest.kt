package com.staninna.yourtube

import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.page.ChannelResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun item(type: ItemType) = StarredItem(id = "x", type = type, title = "x", url = "u")

    private fun TestScope.viewModel(): Pair<MainViewModel, Channel<String>> {
        val store = Store(tmp.root, backgroundScope)
        val vm = MainViewModel(store, ChannelResolver(store, backgroundScope))
        val opened = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.openRequests.collect { opened.send(it) } }
        return vm to opened
    }

    @Test fun coldLaunchOpensFeedsWhenThereAreSubs() =
        assertEquals(Tab.FEEDS, MainViewModel.startTab(Library(items = listOf(item(ItemType.VIDEO), item(ItemType.CHANNEL)))))

    @Test fun coldLaunchOpensLibraryWithoutSubs() {
        assertEquals(Tab.LIBRARY, MainViewModel.startTab(Library()))
        assertEquals(Tab.LIBRARY, MainViewModel.startTab(Library(items = listOf(item(ItemType.VIDEO)))))
    }

    @Test fun watchTabWithNothingOpenedLoadsHome() = runTest {
        val (vm, opened) = viewModel()
        vm.selectTab(Tab.WATCH)
        assertEquals(Tab.WATCH, vm.tab.value)
        assertEquals(YouTube.HOME, opened.tryReceive().getOrNull())

        vm.selectTab(Tab.HISTORY)
        vm.selectTab(Tab.WATCH)
        assertTrue("home loads once", opened.tryReceive().isFailure)
    }

    @Test fun watchTabAfterOpeningSomethingKeepsIt() = runTest {
        val (vm, opened) = viewModel()
        vm.open("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        vm.selectTab(Tab.LIBRARY)
        vm.selectTab(Tab.WATCH)
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", opened.tryReceive().getOrNull())
        assertTrue(opened.tryReceive().isFailure)
    }
}
