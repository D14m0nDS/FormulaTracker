package com.formulatracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.formulatracker.reminders.REMINDER_SESSION_TYPES
import com.formulatracker.reminders.ReminderPreferences
import com.formulatracker.reminders.ReminderReceiver
import com.formulatracker.reminders.SessionReminderScheduler

@Composable
fun RemindersScreen(
    preferences: ReminderPreferences,
    onPreferences: (ReminderPreferences) -> Unit,
) {
    val context = LocalContext.current
    val scheduler = remember { SessionReminderScheduler(context.applicationContext) }
    var exactAlarmsAllowed by remember { mutableStateOf(scheduler.exactAlarmsAllowed()) }
    var notificationsAllowed by remember {
        mutableStateOf(!ReminderReceiver.missingNotificationPermission(context))
    }
    var armTest by remember { mutableStateOf(false) }
    var testScheduled by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = granted
        if (granted && armTest) {
            scheduler.scheduleTest()
            testScheduled = true
        }
        armTest = false
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsAllowed = scheduler.exactAlarmsAllowed()
                notificationsAllowed = !ReminderReceiver.missingNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "These follow the published start time. They do not confirm that a session has started.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!notificationsAllowed) {
            Text("Notifications are off, so reminders cannot appear.")
        }
        if (!exactAlarmsAllowed) {
            Text("Exact alarms are off, so reminders may arrive late.")
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                },
            ) {
                Text("Allow exact alarms")
            }
        }
        Text("Sessions", style = MaterialTheme.typography.titleMedium)
        REMINDER_SESSION_TYPES.forEach { sessionType ->
            ToggleRow(
                label = sessionType.label,
                checked = sessionType.type in preferences.enabledTypes,
                onChecked = { checked ->
                    val enabled = if (checked) {
                        preferences.enabledTypes + sessionType.type
                    } else {
                        preferences.enabledTypes - sessionType.type
                    }
                    onPreferences(preferences.copy(enabledTypes = enabled))
                },
            )
        }
        Text("Race week", style = MaterialTheme.typography.titleMedium)
        Text(
            "Monday at 9:00 in this phone's local time, in the week that contains the race.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ToggleRow(
            label = "Monday at 9:00",
            checked = preferences.raceWeek,
            onChecked = { onPreferences(preferences.copy(raceWeek = it)) },
        )
        Text("When", style = MaterialTheme.typography.titleMedium)
        ToggleRow(
            label = "60 minutes before",
            checked = preferences.beforeStart,
            onChecked = { onPreferences(preferences.copy(beforeStart = it)) },
        )
        ToggleRow(
            label = "At the scheduled start",
            checked = preferences.atStart,
            onChecked = { onPreferences(preferences.copy(atStart = it)) },
        )
        Button(
            onClick = {
                if (ReminderReceiver.missingNotificationPermission(context)) {
                    armTest = true
                    if (Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    scheduler.scheduleTest()
                    testScheduled = true
                }
            },
        ) {
            Text("Try a reminder")
        }
        if (testScheduled) {
            Text("A reminder is scheduled for about a minute from now.")
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
