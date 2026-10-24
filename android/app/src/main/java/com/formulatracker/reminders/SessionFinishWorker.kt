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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
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
    // Android may hold this job back for hours, so exact alarms ask on their own schedule.
    // This run is only the fallback for when those alarms do not arrive.
    override suspend fun doWork(): Result {
        checkSessionFinish(applicationContext)
        rearmSessionFinishChecks(applicationContext)
        return Result.success()
    }
}

// The worker and the alarm receiver share one process. The lock keeps them from both
// reading the saved keys before either writes, which would notify twice.
private val finishNoticeLock = Mutex()

internal suspend fun checkSessionFinish(context: Context) {
    // Leave the saved keys alone while this is off, so turning it back on
    // can still notify a session that is inside the five-hour window.
    if (!ReminderSettings(context).read().sessionFinished) {
        return
    }
    if (ReminderReceiver.notificationsTurnedOff(context)) {
        return
    }
    val year = Year.now().value
    if (year < FIRST_SESSION_FINISH_YEAR) {
        return
    }
    // The listed end is not the finish. A down backend or a blocked feed waits for the next check.
    val watch = try {
        HttpScheduleSource().fetchSessionFinish(year)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return
    }
    // A notice that was shown must also be saved, even if the caller gives up now.
    withContext(NonCancellable) {
        finishNoticeLock.withLock {
            val store = SessionFinishStore(context)
            val plan = planSessionFinishNotices(watch, store.read())
            plan.notices.forEach { notice ->
                showScheduledNotification(context, notice.title, notice.text, notice.notificationId)
            }
            if (plan.notices.isNotEmpty()) {
                store.write(plan.notified)
            }
        }
    }
}

// Plans the next alarm for every session still waiting, from the saved calendar.
// Runs after every check, successful or not, so a failed request does not end the chain.
internal fun rearmSessionFinishChecks(context: Context, now: Instant = Instant.now()) {
    try {
        val scheduler = SessionReminderScheduler(context)
        val schedule = ReminderScheduleStore(context).read()
        val preferences = ReminderSettings(context).read()
        if (schedule == null || ReminderReceiver.notificationsTurnedOff(context)) {
            scheduler.clearFinishChecks()
            return
        }
        scheduler.replaceFinishChecks(schedule, preferences, now)
    } catch (_: Exception) {
        // The next calendar refresh or app start plans these again.
    }
}

private const val SESSION_FINISH_WORK = "session-finish"
