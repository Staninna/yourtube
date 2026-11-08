package com.staninna.yourtube

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.staninna.yourtube.data.Settings
import com.staninna.yourtube.playback.PipPolicy
import com.staninna.yourtube.playback.PipShape
import com.staninna.yourtube.playback.PipSupport
import com.staninna.yourtube.playback.PlaybackHub
import com.staninna.yourtube.playback.PxRect
import com.staninna.yourtube.web.VideoBox
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Picture-in-picture for [activity]. While the Activity is started it keeps the platform's params
 * current: the video's aspect ratio and on-screen rect, and from Android 12 whether leaving enters
 * picture-in-picture by itself ([PipPolicy.autoEnter]). Before Android 12 it enters from
 * `onUserLeaveHint` on the same rule. Manual mode enters from Watch's menu. Mode changes go to the
 * page (the video fills the window) and the hub (a close ends the session; the shell hides its chrome).
 */
internal class PictureInPicture(
    private val activity: ComponentActivity,
    private val model: PipModel,
    private val settings: StateFlow<Settings>,
    private val playing: StateFlow<Boolean>,
    private val host: WebViewHost,
    private val hub: PlaybackHub,
) {
    private data class Input(val support: PipSupport, val autoEnter: Boolean, val fullscreen: Boolean, val box: VideoBox? = null)

    private var input: Input? = null

    init {
        activity.addOnUserLeaveHintListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && input?.autoEnter == true) enter()
        }
        activity.addOnPictureInPictureModeChangedListener { info ->
            host.fillWithVideo(info.isInPictureInPictureMode)
            hub.setPip(info.isInPictureInPictureMode)
        }
        activity.lifecycleScope.launch { model.entries.collect { enter() } }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Every start: the user may have turned it off for the app meanwhile.
                model.setSupport(support())
                inputs().distinctUntilChanged().collect { next ->
                    input = next
                    // Without the feature the platform refuses params outright.
                    if (next.support != PipSupport.NO_FEATURE) withParams(next, activity::setPictureInPictureParams)
                }
            }
        }
    }

    private fun inputs() = combine(
        combine(settings, model.support, playing, model.watchShown, host.fullscreen) { settings, support, playing, shown, fullscreen ->
            Input(support, PipPolicy.autoEnter(settings.pip, support, playing, shown, fullscreen), fullscreen)
        },
        host.videoBox,
    ) { input, box -> input.copy(box = box) }

    private fun enter() {
        val input = input ?: return
        if (model.support.value != PipSupport.AVAILABLE || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        withParams(input) { activity.enterPictureInPictureMode(it) }
    }

    /**
     * Hands [use] the params for [input]. A device may narrow Android's aspect ratio limits and refuse
     * the video's shape; it then gets the params without one, and if those fail too, nothing changes.
     */
    private fun withParams(input: Input, use: (PictureInPictureParams) -> Unit) {
        for (shaped in listOf(true, false)) {
            try {
                use(params(input, shaped))
                return
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "picture-in-picture params refused (shaped=$shaped)", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "picture-in-picture params refused (shaped=$shaped)", e)
            }
        }
    }

    private fun params(input: Input, shaped: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val shape = if (shaped) shape(input) else null
        if (shape != null) {
            builder.setAspectRatio(Rational(shape.aspectWidth, shape.aspectHeight))
            shape.sourceRect?.let { builder.setSourceRectHint(Rect(it.left, it.top, it.right, it.bottom)) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(input.autoEnter).setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    /** The video's shape on screen now; in fullscreen Chromium's own view fills the window with it. */
    private fun shape(input: Input): PipShape? {
        val box = input.box ?: return null
        val decor = activity.window.decorView
        val window = PxRect(0, 0, decor.width, decor.height)
        val area = if (input.fullscreen) {
            window
        } else {
            val view = host.boundsInWindow()?.takeIf { box.viewportWidth > 0 } ?: return null
            PipShape.toWindow(box.left, box.top, box.right, box.bottom, view.width() / box.viewportWidth, view.left, view.top)
        }
        return PipShape.of(box.videoWidth, box.videoHeight, area, window)
    }

    private fun support(): PipSupport {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return PipSupport.NO_FEATURE
        val ops = activity.getSystemService(AppOpsManager::class.java)
        val op = AppOpsManager.OPSTR_PICTURE_IN_PICTURE
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(op, Process.myUid(), activity.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(op, Process.myUid(), activity.packageName)
        }
        val off = mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED
        return if (off) PipSupport.TURNED_OFF else PipSupport.AVAILABLE
    }

    private companion object {
        const val TAG = "YT-PIP"
    }
}
