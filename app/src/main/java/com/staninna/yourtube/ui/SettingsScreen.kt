package com.staninna.yourtube.ui

import android.content.pm.PackageInfo
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import com.staninna.yourtube.BuildConfig
import com.staninna.yourtube.Diagnostics
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.ui.components.SubpageTopBar
import com.staninna.yourtube.ui.theme.dynamicColorAvailable
import kotlinx.coroutines.CancellationException

/**
 * Settings, pushed over the shell. Sections follow the plan's order (Playback, Content, Feed,
 * Appearance, Library, Privacy, About); a section appears once it has a real setting, so today that
 * is Content, Appearance and About. Back, including the predictive back gesture, shrinks the screen
 * toward the swiped edge before it leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val diagnostics by Diagnostics.counts.collectAsStateWithLifecycle()
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
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubpageTopBar("Settings", onBack, scrollBehavior) },
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
                "Content",
                listOf { shapes ->
                    SwitchRow(
                        "Hide Shorts",
                        "Removes Shorts shelves and links from YouTube pages",
                        SwitchState(settings.hideShorts, enabled = true),
                        vm::setHideShorts,
                        shapes,
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
            Section("About", aboutRows(webView, SettingsModel.diagnostics(diagnostics)))
        }
    }
}

private typealias SettingRow = @Composable (ListItemShapes) -> Unit

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

/** A heading over rows that read as one rounded card, Android 16 style. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Section(title: String, rows: List<SettingRow>) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
    }
}

/** A setting that turns on and off; the whole row toggles, and TalkBack hears a switch. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchRow(title: String, summary: String, state: SwitchState, onChange: (Boolean) -> Unit, shapes: ListItemShapes) {
    SegmentedListItem(
        shapes = shapes,
        enabled = state.enabled,
        modifier = Modifier
            .clip(shapes.shape)
            .toggleable(value = state.checked, enabled = state.enabled, role = Role.Switch, onValueChange = onChange),
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = state.checked, onCheckedChange = null, enabled = state.enabled) },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InfoRow(label: String, value: String, shapes: ListItemShapes) {
    SegmentedListItem(
        shapes = shapes,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        supportingContent = { Text(value) },
    ) { Text(label) }
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
