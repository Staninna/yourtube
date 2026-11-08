package com.staninna.yourtube.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.data.Settings
import com.staninna.yourtube.notify.NewVideoNotifier

/**
 * Settings > Feed's New video notifications: the switch, and while Android blocks the app's
 * notifications, a row that opens their page in Android's settings. Turning the switch on asks for
 * the permission first (Android 13 and later); a refusal leaves it off.
 */
@Composable
fun newVideoRows(settings: Settings, vm: MainViewModel): List<SettingRow> {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(NewVideoNotifier.allowed(context)) }
    // The user may change it in Android's settings and come back.
    LifecycleResumeEffect(Unit) {
        allowed = NewVideoNotifier.allowed(context)
        onPauseOrDispose {}
    }
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = NewVideoNotifier.allowed(context)
        refused = !(granted && allowed)
        if (!refused) vm.setNewVideoNotifications(true)
    }
    val onChange = { on: Boolean ->
        when {
            !on -> vm.setNewVideoNotifications(false)
            allowed -> vm.setNewVideoNotifications(true)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            // Granted, but the app's notifications are switched off in Android's settings.
            else -> refused = true
        }
    }
    val blocked = !allowed && (refused || settings.newVideoNotifications)
    val rows = mutableListOf<SettingRow>(
        { shapes -> SwitchRow("New video notifications", SettingsModel.newVideoSummary(blocked), SettingsModel.newVideos(settings, allowed), onChange, shapes) },
    )
    if (blocked) {
        rows += { shapes ->
            ActionRow("Allow notifications", "Opens YourTube's notification settings in Android", Icons.Outlined.NotificationsOff, shapes) {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
    }
    return rows
}
