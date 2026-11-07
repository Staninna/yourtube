package com.staninna.yourtube

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.staninna.yourtube.web.WebViewStartup

/**
 * A trampoline in its own `:restart` process (ProcessPhoenix style). It makes sure the main process
 * is gone, starts [MainActivity] in a fresh one, and exits. Only `WebViewStartup.restartProcess`
 * starts it.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        if (mainPid > 0) Process.killProcess(mainPid)
        val main = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.getStringExtra(WebViewStartup.EXTRA_URL)?.let { main.putExtra(WebViewStartup.EXTRA_URL, it) }
        startActivity(main)
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val EXTRA_MAIN_PID = "com.staninna.yourtube.MAIN_PID"
    }
}
