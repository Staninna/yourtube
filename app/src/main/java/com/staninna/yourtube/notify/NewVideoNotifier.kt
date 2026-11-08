package com.staninna.yourtube.notify

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.staninna.yourtube.R
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.feed.NewVideos
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The new-video notifications: one per video (tagged with its id), newest first and at most
 * [NewVideos.CAP] per check, grouped under a summary that is the only one to alert. Each opens its
 * video in Watch through the app's own link handling.
 */
class NewVideoNotifier(private val context: Context) : NewVideoAlerts {

    override fun allowed(): Boolean = allowed(context)

    override suspend fun prepare(videos: List<FeedVideo>): () -> Unit {
        if (videos.isEmpty()) return {}
        val shown = videos.take(NewVideos.CAP)
        // In parallel and bounded: a still that does not come in time is left out, not waited for.
        val stills = coroutineScope { shown.map { async { withTimeoutOrNull(STILL_TIMEOUT_MS) { still(it) } } }.awaitAll() }
        return { post(shown, stills, left = videos.size - shown.size) }
    }

    // allowed() was checked before the run; a revoke since then loses these posts, nothing worse.
    @SuppressLint("MissingPermission")
    private fun post(shown: List<FeedVideo>, stills: List<Bitmap?>, left: Int) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        // Lines of the videos still in the shade from earlier checks, for the summary's count.
        val earlier = context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == VIDEO_ID && it.notification.group == GROUP && it.tag != null }
            .associate { it.tag to "${it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)}: ${it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)}" }
        shown.zip(stills).forEach { (video, still) -> manager.notify(video.videoId, VIDEO_ID, single(video, still)) }
        NewVideos.summary(earlier, shown, left)?.let { manager.notify(SUMMARY_ID, summary(it)) }
    }

    private fun single(video: FeedVideo, still: Bitmap?) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_new_video)
            .setContentTitle(video.author)
            .setContentText(video.title)
            .setWhen(video.publishedAt)
            .setShowWhen(true)
            .setLargeIcon(still)
            .setStyle(still?.let { NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?) })
            .setContentIntent(open(video))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()

    private fun summary(summary: NewVideos.Summary) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_new_video)
            .setContentTitle(summary.title)
            .setStyle(NotificationCompat.InboxStyle().also { style -> summary.lines.forEach(style::addLine); summary.more?.let(style::setSummaryText) })
            // A collapsed group opens the app, which starts on Feeds; each video opens itself.
            .setContentIntent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            })
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()

    /** The watch link with the package set, so the app's own link handling opens it in Watch. */
    private fun open(video: FeedVideo): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(YouTube.watchUrl(video.videoId))).setPackage(context.packageName)
        return PendingIntent.getActivity(context, video.videoId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The video's still, through the app's image loader and its cache; none when it does not load. */
    private suspend fun still(video: FeedVideo): Bitmap? {
        val request = ImageRequest.Builder(context).data(video.thumbnail).allowHardware(false).build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
    }

    companion object {
        const val CHANNEL_ID = "new_videos"
        private const val GROUP = "com.staninna.yourtube.NEW_VIDEOS"

        /** Fixed ids, told apart by tag (the video id): clear of the media notification's 1001. */
        private const val SUMMARY_ID = 1
        private const val VIDEO_ID = 2
        private const val STILL_TIMEOUT_MS = 4_000L

        /**
         * Whether a new-video notification would show: Android lets the app post (the permission, the
         * app's switch) and the "New videos" channel is not turned off.
         */
        fun allowed(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            return manager.getNotificationChannelCompat(CHANNEL_ID)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        /** Creates the "New videos" channel (a no-op once it exists), so it can be managed in Android's settings. */
        fun ensureChannel(context: Context) = NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("New videos")
                .setDescription("Uploads from channels you subscribed to")
                .build(),
        )
    }
}
