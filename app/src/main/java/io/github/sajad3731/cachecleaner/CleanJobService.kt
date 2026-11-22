package io.github.sajad3731.cachecleaner

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Scheduled clean. It runs only through Shizuku: the Accessibility engine needs the screen, which
 * a background job must not take over. Each run lands in the history, skipped ones too.
 */
class CleanJobService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = thread(name = "scheduled-clean") {
            Prefs(this).addHistory(runClean(this))
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return false
    }

    companion object {
        private const val JOB_ID = 1
        private const val BINDER_WAIT_MS = 5_000L

        /** Matches the job to the saved schedule. Call after any schedule change. */
        fun apply(context: Context) {
            val prefs = Prefs(context)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // The job runs within the flex window at the end of each period, so the first run
            // comes a period after turning the schedule on, not straight away.
            val (period, flex) = when (prefs.schedule) {
                Schedule.OFF -> {
                    scheduler.cancel(JOB_ID)
                    return
                }
                Schedule.DAILY -> TimeUnit.DAYS.toMillis(1) to TimeUnit.HOURS.toMillis(2)
                Schedule.WEEKLY -> TimeUnit.DAYS.toMillis(7) to TimeUnit.HOURS.toMillis(12)
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CleanJobService::class.java))
                .setPeriodic(period, flex)
                .setRequiresCharging(prefs.chargingOnly)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        /** Force stop cancels an app's jobs; put the job back when the app next starts. */
        fun ensureScheduled(context: Context) {
            if (Prefs(context).schedule == Schedule.OFF) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) == null) apply(context)
        }

        fun runClean(context: Context): HistoryEntry {
            val prefs = Prefs(context)
            fun entry(freed: Long, apps: Int, outcome: Outcome) = HistoryEntry(
                time = System.currentTimeMillis(),
                freedBytes = freed,
                apps = apps,
                engine = Engine.SHIZUKU,
                scheduled = true,
                outcome = outcome,
            )
            if (!ShizukuEngine.awaitBinder(BINDER_WAIT_MS) || !ShizukuEngine.isReady(context)) {
                return entry(-1, 0, Outcome.SKIPPED_NO_SHIZUKU)
            }
            val scanner = CacheScanner(context)
            val canMeasure = scanner.hasUsageAccess()
            val before = if (canMeasure) scanner.scan() else null
            if (!CleanPlan.canCleanSilently(context, prefs, before)) {
                return entry(-1, 0, Outcome.SKIPPED_EXCLUSIONS)
            }
            val apps = CleanPlan.cleanSilently(context, prefs, before) ?: return entry(-1, 0, Outcome.FAILED)
            val freed = if (before != null) {
                (before.totalBytes - scanner.scan().totalBytes).coerceAtLeast(0L)
            } else {
                -1L
            }
            return entry(freed, apps, Outcome.DONE)
        }
    }
}
