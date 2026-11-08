package com.staninna.yourtube.ui

import android.content.pm.PackageInfo
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import com.staninna.yourtube.BuildConfig
import com.staninna.yourtube.Diagnostics
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.data.FeedPageSize
import com.staninna.yourtube.data.PipMode
import com.staninna.yourtube.playback.PipSupport
import com.staninna.yourtube.ui.components.SubpageTopBar
import com.staninna.yourtube.ui.theme.dynamicColorAvailable
import kotlinx.coroutines.CancellationException

/**
 * Settings, pushed over the shell, in the plan's order: Playback, Content (see `ContentSettings`),
 * Feed, Appearance, Library, Privacy and About. Back, including the predictive back gesture, shrinks
 * the screen toward the swiped edge before it leaves. Manage tags opens over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbars: Snackbars, backup: BackupActions, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val library by vm.library.state.collectAsStateWithLifecycle()
    val diagnostics by Diagnostics.counts.collectAsStateWithLifecycle()
    val pipSupport by vm.pip.support.collectAsStateWithLifecycle()
    var manageTags by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val webView = remember { WebViewCompat.getCurrentWebViewPackage(context) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var backProgress by remember { mutableFloatStateOf(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler { events ->
        try {
            events.collect {
                backProgress = it.progress
                backEdge = it.swipeEdge
            }
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    Scaffold(
        modifier = Modifier
            .predictiveBack(backProgress, backEdge)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            // Manage tags covers Settings; TalkBack must not reach the rows under it.
            .then(if (manageTags) Modifier.clearAndSetSemantics {} else Modifier),
        topBar = { SubpageTopBar("Settings", onBack, scrollBehavior) },
        // Settings covers the shell's snackbar, so undo and import notices show here while it is open.
        snackbarHost = { SnackbarStack(snackbars) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Section(
                "Playback",
                listOf { shapes ->
                    ChoiceRow(
                        "Picture-in-picture",
                        SettingsModel.pipSummary(settings.pip, pipSupport),
                        options = SettingsModel.pipOptions,
                        selectedIndex = settings.pip.ordinal,
                        onSelect = { vm.pip.setMode(PipMode.entries[it]) },
                        shapes = shapes,
                        enabled = pipSupport == PipSupport.AVAILABLE,
                    )
                },
            )
            ContentSettings(settings, vm)
            Section(
                "Feed",
                listOf { shapes ->
                    ChoiceRow(
                        "Videos per page",
                        "Feeds shows this many at first, and Load more adds as many again",
                        options = FeedPageSize.entries.map { it.count.toString() },
                        selectedIndex = settings.feedPageSize.ordinal,
                        onSelect = { vm.feeds.setPageSize(FeedPageSize.entries[it]) },
                        shapes = shapes,
                    )
                },
            )
            Section(
                "Appearance",
                listOf { shapes ->
                    SwitchRow(
                        "Dynamic color",
                        if (dynamicColorAvailable) "Use colors from your wallpaper" else "Needs Android 12 or later",
                        SettingsModel.dynamicColor(settings, dynamicColorAvailable),
                        vm::setDynamicColor,
                        shapes,
                    )
                },
            )
            Section(
                "Library",
                listOf(
                    { shapes ->
                        ActionRow(
                            "Manage tags",
                            SettingsModel.tagCount(library.tags.size),
                            Icons.AutoMirrored.Outlined.Label,
                            shapes,
                            onClick = { manageTags = true },
                        )
                    },
                    { shapes ->
                        ActionRow(
                            "Export library",
                            "Save your library, tags, history and settings to a file",
                            Icons.Outlined.FileUpload,
                            shapes,
                            onClick = backup.export,
                        )
                    },
                    { shapes ->
                        ActionRow(
                            "Import library",
                            "Merge an export into this library. Nothing here is removed.",
                            Icons.Outlined.FileDownload,
                            shapes,
                            onClick = backup.import,
                        )
                    },
                ),
            )
            Section(
                "Privacy",
                listOf { shapes ->
                    SwitchRow(
                        "Show new-identity button",
                        "A mask next to Home in Watch. It shows YouTube a new viewer; your library and history stay.",
                        SwitchState(settings.showNewIdentity, enabled = true),
                        vm::setShowNewIdentity,
                        shapes,
                    )
                },
            )
            Section("About", aboutRows(webView, SettingsModel.diagnostics(diagnostics)))
        }
    }

    AnimatedVisibility(
        visible = manageTags,
        enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 3 } + fadeIn(),
        exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 3 } + fadeOut(),
    ) {
        ManageTagsScreen(vm.library, onBack = { manageTags = false })
    }
}

private fun aboutRows(webView: PackageInfo?, events: List<Pair<String, Int>>): List<SettingRow> {
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " debug" else ""
    val provider = webView?.let { "${it.versionName} · ${it.packageName}" } ?: "Unknown"
    val diagnostics: List<SettingRow> = if (events.isEmpty()) {
        listOf { shapes -> InfoRow("Diagnostics", "Nothing unusual since the app started", shapes) }
    } else {
        events.map { (event, count) -> { shapes -> InfoRow(event, if (count == 1) "Once" else "$count times", shapes) } }
    }
    return listOf<SettingRow>(
        { shapes -> InfoRow("Version", version, shapes) },
        { shapes -> InfoRow("WebView", provider, shapes) },
    ) + diagnostics
}

/** The in-app predictive back look: scale down, slide toward the swiped edge, round the corners. */
private fun Modifier.predictiveBack(progress: Float, edge: Int): Modifier = graphicsLayer {
    val scale = lerp(1f, 0.9f, progress)
    scaleX = scale
    scaleY = scale
    translationX = (if (edge == BackEventCompat.EDGE_LEFT) 1 else -1) * lerp(0f, 8.dp.toPx(), progress)
    shape = RoundedCornerShape(lerp(0f, 32f, progress).dp)
    clip = progress > 0f
}
