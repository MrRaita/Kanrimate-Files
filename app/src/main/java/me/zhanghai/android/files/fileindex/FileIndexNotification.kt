/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.notificationManager
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate

val fileIndexNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "file_index",
            R.string.notification_channel_file_index_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_file_index_description,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_PROGRESS,
        priority = NotificationCompat.PRIORITY_LOW
    )

/** Shows indexing progress. Never ongoing: the scan runs in a plain thread that may be killed. */
object FileIndexNotification {
    // Disappears by itself if the process is killed before we can cancel it.
    private const val TIMEOUT_MILLIS = 60_000L
    private const val MIN_UPDATE_INTERVAL_MILLIS = 700L

    private var lastUpdateMillis = 0L

    /**
     * @param estimatedTotal how many entries the previous scan found, or 0 if unknown (then the
     *   bar is indeterminate and only the running count is shown)
     */
    fun showProgress(indexed: Int, estimatedTotal: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdateMillis < MIN_UPDATE_INTERVAL_MILLIS) {
            return
        }
        lastUpdateMillis = now
        val builder = fileIndexNotificationTemplate.createBuilder(application)
            .setContentTitle(application.getString(R.string.file_index_notification_title))
            .setContentText(
                application.getString(R.string.file_index_notification_progress_format, indexed)
            )
            .setOngoing(false)
            .setTimeoutAfter(TIMEOUT_MILLIS)
        if (estimatedTotal > 0) {
            builder.setProgress(estimatedTotal, indexed.coerceAtMost(estimatedTotal), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        notify(builder)
    }

    /** The second phase of an FTS4 scan: building the word table from the finished index. */
    fun showPreparing(done: Int, total: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdateMillis < MIN_UPDATE_INTERVAL_MILLIS) {
            return
        }
        lastUpdateMillis = now
        val builder = fileIndexNotificationTemplate.createBuilder(application)
            .setContentTitle(application.getString(R.string.file_index_notification_fts_title))
            .setContentText(
                application.getString(R.string.file_index_notification_fts_format, done)
            )
            .setOngoing(false)
            .setTimeoutAfter(TIMEOUT_MILLIS)
        if (total > 0) {
            builder.setProgress(total, done.coerceAtMost(total), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        notify(builder)
    }

    fun showDone(indexed: Int, failedRoots: Int = 0) {
        var text = application.getString(R.string.file_index_notification_done_format, indexed)
        if (failedRoots > 0) {
            text += "\n" + application.getString(
                R.string.file_index_notification_failed_format, failedRoots
            )
        }
        val builder = fileIndexNotificationTemplate.createBuilder(application)
            .setContentTitle(application.getString(R.string.file_index_notification_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000L)
            .setProgress(0, 0, false)
        notify(builder)
    }

    fun cancel() {
        try {
            notificationManager.cancel(NotificationIds.FILE_INDEX)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun notify(builder: NotificationCompat.Builder) {
        try {
            notificationManager.notify(NotificationIds.FILE_INDEX, builder.build())
        } catch (e: SecurityException) {
            // No notification permission; indexing still works without it.
            e.printStackTrace()
        }
    }
}
