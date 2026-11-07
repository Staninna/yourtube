package com.staninna.yourtube.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.RestartActivity
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Process-level WebView data handling. Every process starts with an empty WebView data directory,
 * which is what makes each launch a fresh identity; old profiles live inside the old directories.
 */
object WebViewStartup {
    private const val TAG = "YT-IDENTITY"
    private const val DATA_PREFIX = "app_webview"

    /** `cache/WebView` without a suffix, `cache/webview_<suffix>` with one (measured on WebView 133). */
    private const val CACHE_PREFIX = "webview"

    /** The Activity loads this URL when the process was restarted for a new identity. */
    const val EXTRA_URL = "com.staninna.yourtube.URL"

    /**
     * Gives this process a fresh WebView data directory. Call first in `Application.onCreate`, before
     * anything loads WebView.
     *
     * With `STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX` the process gets a new random suffix and the
     * directories of earlier processes are deleted in the background (nothing uses them). Without it
     * the one default directory is deleted right here, which is safe because WebView is not loaded yet.
     */
    fun prepare(context: Context) {
        val dataDir = File(context.applicationInfo.dataDir)
        val cacheDir = context.cacheDir
        val suffixFeature = WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX
        if (WebViewFeature.isStartupFeatureSupported(context, suffixFeature)) {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
            ProcessGlobalConfig.apply(ProcessGlobalConfig().setDataDirectorySuffix(context, suffix))
            Log.i(TAG, "WebView data suffix $suffix")
            thread(name = "webview-data-cleanup", isDaemon = true) {
                deleteChildren(dataDir, DATA_PREFIX, keep = "${DATA_PREFIX}_$suffix")
                deleteChildren(cacheDir, CACHE_PREFIX, keep = "${CACHE_PREFIX}_$suffix")
            }
        } else {
            deleteChildren(dataDir, DATA_PREFIX, keep = null)
            deleteChildren(cacheDir, CACHE_PREFIX, keep = null)
        }
    }

    /**
     * Restarts the app process into [url]. Used where WebView has no way to give a new identity
     * inside a running process (`IdentityStrategy.RestartProcess`). The `:restart` trampoline starts
     * the new process after this one is gone.
     */
    fun restartProcess(activity: Activity, url: String?) {
        val trampoline = Intent(activity, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RestartActivity.EXTRA_MAIN_PID, Process.myPid())
        if (url != null) trampoline.putExtra(EXTRA_URL, url)
        activity.startActivity(trampoline)
        activity.finish()
        Runtime.getRuntime().exit(0)
    }

    private fun deleteChildren(dir: File, prefix: String, keep: String?) {
        dir.listFiles()?.filter { it.name.startsWith(prefix, ignoreCase = true) && it.name != keep }?.forEach {
            if (!it.deleteRecursively()) Log.w(TAG, "could not fully delete ${it.name}")
        }
    }
}
