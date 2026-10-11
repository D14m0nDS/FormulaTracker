package com.formulatracker.reminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.formulatracker.favorites.FavoriteStore
import com.formulatracker.schedule.HttpScheduleSource
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

fun enqueueScheduleRefresh(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = PeriodicWorkRequestBuilder<ScheduleRefreshWorker>(6, TimeUnit.HOURS)
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        SCHEDULE_REFRESH_WORK,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

class ScheduleRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val fetched = try {
            HttpScheduleSource().fetch(Year.now().value)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A down backend should wait for the next window, not retry immediately.
            return Result.success()
        }
        val store = ReminderScheduleStore(applicationContext)
        val plan = planScheduleRefresh(
            previous = store.read(),
            fetched = fetched,
            now = Instant.now(),
            zone = ZoneId.systemDefault(),
            locale = Locale.getDefault(),
        )
        val schedule = plan.schedule ?: return Result.success()
        store.write(schedule)
        plan.changes.forEach { change ->
            showScheduledNotification(applicationContext, change.title, change.text, change.notificationId)
        }
        SessionReminderScheduler(applicationContext).replace(
            schedule,
            ReminderSettings(applicationContext).read(),
            FavoriteStore(applicationContext).read(),
        )
        return Result.success()
    }
}

private const val SCHEDULE_REFRESH_WORK = "schedule-refresh"
