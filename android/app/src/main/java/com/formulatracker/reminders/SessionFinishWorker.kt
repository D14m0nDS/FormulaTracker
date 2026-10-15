package com.formulatracker.reminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.formulatracker.schedule.HttpScheduleSource
import kotlinx.coroutines.CancellationException
import java.time.Year
import java.util.concurrent.TimeUnit

fun enqueueSessionFinishCheck(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = PeriodicWorkRequestBuilder<SessionFinishWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        SESSION_FINISH_WORK,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

class SessionFinishWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Leave the saved keys alone while this is off, so turning it back on
        // can still notify a session that is inside the five-hour window.
        if (!ReminderSettings(applicationContext).read().sessionFinished) {
            return Result.success()
        }
        if (ReminderReceiver.notificationsTurnedOff(applicationContext)) {
            return Result.success()
        }
        val year = Year.now().value
        if (year < FIRST_SESSION_FINISH_YEAR) {
            return Result.success()
        }
        // The listed end is not the finish. Ask again about every 15 minutes.
        // A down backend or a blocked feed waits for the next run.
        val watch = try {
            HttpScheduleSource().fetchSessionFinish(year)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return Result.success()
        }
        val store = SessionFinishStore(applicationContext)
        val plan = planSessionFinishNotices(watch, store.read())
        plan.notices.forEach { notice ->
            showScheduledNotification(
                applicationContext,
                notice.title,
                notice.text,
                notice.notificationId,
            )
        }
        if (plan.notices.isNotEmpty()) {
            store.write(plan.notified)
        }
        return Result.success()
    }
}

private const val SESSION_FINISH_WORK = "session-finish"
