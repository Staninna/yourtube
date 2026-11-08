package com.staninna.yourtube.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.staninna.yourtube.TubeApp
import com.staninna.yourtube.feed.YouTubeFeedFetcher
import java.util.concurrent.TimeUnit

/**
 * The hourly new-video check. RSS only: no channel pages, no WebView, no identity. With the switch
 * off or the notifications blocked it ends without a request (see [NewVideoCheck]).
 */
class NewVideoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // WorkManager starts on demand from TubeApp (Configuration.Provider), after the store exists.
        val app = applicationContext as TubeApp
        NewVideoCheck(app.store, YouTubeFeedFetcher()::rss, NewVideoNotifier(app)).run()
        app.store.awaitWrites()
        return Result.success()
    }
}

/** Keeps the periodic check in step with the setting. WorkManager keeps it across reboots. */
object NewVideoSchedule {
    const val WORK_NAME = "new-videos"

    fun apply(context: Context, on: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!on) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        // The channel exists from the start, so it can be managed in Android's settings before a post.
        NewVideoNotifier.ensureChannel(context)
        val request = PeriodicWorkRequestBuilder<NewVideoWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE: a changed request (say, the interval) applies, and the next run keeps its time.
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
