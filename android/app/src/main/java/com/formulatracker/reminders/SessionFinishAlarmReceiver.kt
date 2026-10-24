package com.formulatracker.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SessionFinishAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A receiver has about ten seconds. A slower answer waits for the next check.
                withTimeoutOrNull(CHECK_BUDGET_MILLIS) { checkSessionFinish(appContext) }
            } finally {
                rearmSessionFinishChecks(appContext)
                pending.finish()
            }
        }
    }

    private companion object {
        const val CHECK_BUDGET_MILLIS = 8_000L
    }
}
