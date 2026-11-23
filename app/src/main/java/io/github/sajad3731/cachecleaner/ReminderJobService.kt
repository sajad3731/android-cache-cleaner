package io.github.sajad3731.cachecleaner

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cleaning reminder. A periodic job measures the cache Clean all would free and, above a
 * threshold, posts a notification. Cleaning starts only when the user taps it: the Accessibility
 * engine works through Settings on screen, which a background job must never take over.
 */
class ReminderJobService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = thread(name = "cache-reminder") {
            try {
                check(this)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return false
    }

    companion object {
        private const val JOB_ID = 1
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "reminder"

        /** Below this much cache to clean, the reminder stays quiet. */
        const val MIN_REMIND_BYTES = 50L shl 20

        /** Matches the job to the saved schedule. Call after any schedule change. */
        fun apply(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // The job runs within the flex window at the end of each period, so the first
            // reminder comes a period after turning it on, not straight away.
            val (period, flex) = when (Prefs(context).schedule) {
                Schedule.OFF -> {
                    scheduler.cancel(JOB_ID)
                    return
                }
                Schedule.DAILY -> TimeUnit.DAYS.toMillis(1) to TimeUnit.HOURS.toMillis(2)
                Schedule.WEEKLY -> TimeUnit.DAYS.toMillis(7) to TimeUnit.HOURS.toMillis(12)
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ReminderJobService::class.java))
                .setPeriodic(period, flex)
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

        /** Measures what Clean all would free and posts the reminder when it is worth it. */
        fun check(context: Context) {
            val scanner = CacheScanner(context)
            if (!scanner.hasUsageAccess() || !canNotify(context)) return
            val bytes = CleanPlan.targets(context, Prefs(context), scanner.scan(withIcons = false)).sumOf { it.cacheBytes }
            // The job was stopped, or the reminder turned off, while the scan ran: post nothing.
            if (Thread.currentThread().isInterrupted || Prefs(context).schedule == Schedule.OFF) return
            if (bytes >= MIN_REMIND_BYTES) notify(context, bytes) else cancel(context)
        }

        fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        // Callers check canNotify first.
        @SuppressLint("MissingPermission")
        private fun notify(context: Context, bytes: Long) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.reminder_channel_description) }
            )
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_START_CLEAN, true)
            val pending = PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val size = Formatter.formatShortFileSize(context, bytes)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.reminder_title, size))
                .setContentText(context.getString(R.string.reminder_text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
