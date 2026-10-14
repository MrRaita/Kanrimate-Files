/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.app.job.JobParameters
import android.app.job.JobService
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/** Runs the scheduled index refreshes (see [FileIndexScheduler]). */
class FileIndexJobService : JobService() {
    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (!Settings.FILE_INDEX_ENABLED.valueCompat) {
            IndexLog.log("auto: job ${params.jobId} ignored, the index is off")
            return false
        }
        stopped = false
        IndexLog.log("auto: job ${params.jobId} started")
        Thread({
            try {
                // Quiet: no popups for something the user did not just ask for.
                FileIndex.startScan(force = false, quiet = true)
                Thread.sleep(1000)
                val deadline = System.currentTimeMillis() + MAX_RUN_MILLIS
                while (FileIndex.isScanning && !stopped && System.currentTimeMillis() < deadline) {
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                // The job is over anyway.
            } finally {
                if (params.jobId == FileIndexScheduler.CHARGING_JOB_ID) {
                    FileIndexScheduler.scheduleCharging()
                }
                if (!stopped) {
                    jobFinished(params, false)
                }
                IndexLog.log("auto: job ${params.jobId} finished (stopped=$stopped)")
            }
        }, "FileIndex.Job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // The system wants the job to end (for example the charger was removed).
        stopped = true
        FileIndex.cancelScan()
        IndexLog.log("auto: job ${params.jobId} stopped by the system")
        return false
    }

    private companion object {
        const val MAX_RUN_MILLIS = 2L * 60 * 60 * 1000
    }
}
