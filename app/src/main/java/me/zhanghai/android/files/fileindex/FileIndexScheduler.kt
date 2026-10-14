/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Keeps the system jobs for automatic indexing in line with the settings: one that repeats every
 * few hours, and one that waits for the charger (or dock). Uses the platform's JobScheduler only,
 * so nothing here depends on Google services.
 */
object FileIndexScheduler {
    const val PERIODIC_JOB_ID = 7101
    const val CHARGING_JOB_ID = 7102

    /** After a charging run the next one waits at least this long. */
    private const val CHARGING_MIN_GAP_MILLIS = 6L * 60 * 60 * 1000

    private fun scheduler(): JobScheduler? = application.getSystemService(JobScheduler::class.java)

    private fun component() = ComponentName(application, FileIndexJobService::class.java)

    /** Makes the scheduled jobs match the settings. Call from a background thread. */
    fun update() {
        val scheduler = scheduler() ?: return
        val enabled = Settings.FILE_INDEX_ENABLED.valueCompat
        val hours = if (enabled) {
            Settings.FILE_INDEX_AUTO_INTERVAL.valueCompat.toIntOrNull() ?: 0
        } else {
            0
        }
        val pending = scheduler.getPendingJob(PERIODIC_JOB_ID)
        if (hours <= 0) {
            if (pending != null) {
                scheduler.cancel(PERIODIC_JOB_ID)
                IndexLog.log("auto: periodic job cancelled")
            }
        } else {
            val interval = hours * 60L * 60 * 1000
            // Rescheduling restarts the period, so only do it when something changed.
            if (pending == null || pending.intervalMillis != interval) {
                val info = JobInfo.Builder(PERIODIC_JOB_ID, component())
                    .setPeriodic(interval)
                    .setPersisted(true)
                    .build()
                IndexLog.log("auto: periodic job every $hours h, result=${scheduler.schedule(info)}")
            }
        }
        if (enabled && Settings.FILE_INDEX_AUTO_CHARGING.valueCompat) {
            if (scheduler.getPendingJob(CHARGING_JOB_ID) == null) {
                scheduleCharging()
            }
        } else if (scheduler.getPendingJob(CHARGING_JOB_ID) != null) {
            scheduler.cancel(CHARGING_JOB_ID)
            IndexLog.log("auto: charging job cancelled")
        }
    }

    /** Queues the next run for when the device is charging (a dock normally charges too). */
    fun scheduleCharging() {
        val scheduler = scheduler() ?: return
        if (!Settings.FILE_INDEX_ENABLED.valueCompat ||
            !Settings.FILE_INDEX_AUTO_CHARGING.valueCompat) {
            return
        }
        val info = JobInfo.Builder(CHARGING_JOB_ID, component())
            .setRequiresCharging(true)
            .setMinimumLatency(CHARGING_MIN_GAP_MILLIS)
            .setPersisted(true)
            .build()
        IndexLog.log("auto: charging job queued, result=${scheduler.schedule(info)}")
    }
}
