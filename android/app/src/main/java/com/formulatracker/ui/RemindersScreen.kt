package com.formulatracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.formulatracker.reminders.LEAD_STEP_MINUTES
import com.formulatracker.reminders.MAX_LEAD_MINUTES
import com.formulatracker.reminders.MIN_LEAD_MINUTES
import com.formulatracker.reminders.REMINDER_SESSION_TYPES
import com.formulatracker.reminders.steppedLeadMinutes
import com.formulatracker.reminders.usableLeadMinutes
import com.formulatracker.reminders.ReminderPreferences
import com.formulatracker.reminders.ReminderReceiver
import com.formulatracker.reminders.SessionReminderScheduler
import com.formulatracker.reminders.showTestSessionFinish

@Composable
fun RemindersScreen(
    preferences: ReminderPreferences,
    onPreferences: (ReminderPreferences) -> Unit,
) {
    val context = LocalContext.current
    val scheduler = remember { SessionReminderScheduler(context.applicationContext) }
    var exactAlarmsAllowed by remember { mutableStateOf(scheduler.exactAlarmsAllowed()) }
    var notificationsAllowed by remember {
        mutableStateOf(!ReminderReceiver.notificationsTurnedOff(context))
    }
    var armTest by remember { mutableStateOf(false) }
    var armFinishTest by remember { mutableStateOf(false) }
    var testScheduled by remember { mutableStateOf(false) }
    var finishShown by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val blocked = ReminderReceiver.notificationsTurnedOff(context)
        notificationsAllowed = !blocked
        if (granted && !blocked) {
            if (armTest) {
                scheduler.scheduleTest()
                testScheduled = true
            }
            if (armFinishTest) {
                showTestSessionFinish(context.applicationContext)
                finishShown = true
            }
        }
        armTest = false
        armFinishTest = false
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsAllowed = scheduler.exactAlarmsAllowed()
                notificationsAllowed = !ReminderReceiver.notificationsTurnedOff(context)
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "These follow the published start time. They do not confirm that a session has started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!notificationsAllowed) {
            NoticeBanner("Notifications are off, so reminders cannot appear.", tone = NoticeTone.Error)
            TextButton(onClick = { openNotificationSettings(context) }) {
                Text("Allow notifications")
            }
        }
        if (!exactAlarmsAllowed) {
            NoticeBanner("Exact alarms are off, so reminders may arrive late.", tone = NoticeTone.Caution)
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
        SettingsGroup(title = "Sessions") {
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
        }
        SettingsGroup(title = "Race week") {
            Text(
                "Monday at 9:00 in this phone's local time, in the week that contains the race.",
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                label = "Monday at 9:00",
                checked = preferences.raceWeek,
                onChecked = { onPreferences(preferences.copy(raceWeek = it)) },
            )
        }
        SettingsGroup(title = "When") {
            ToggleRow(
                label = "Before the start",
                checked = preferences.beforeStart,
                onChecked = { onPreferences(preferences.copy(beforeStart = it)) },
            )
            ToggleRow(
                label = "At the scheduled start",
                checked = preferences.atStart,
                onChecked = { onPreferences(preferences.copy(atStart = it)) },
            )
            LeadMinutesRow(
                minutes = usableLeadMinutes(preferences.leadMinutes),
                onMinutes = { onPreferences(preferences.copy(leadMinutes = it)) },
            )
            Button(
                onClick = {
                    when {
                        ReminderReceiver.missingNotificationPermission(context) -> {
                            armTest = true
                            if (Build.VERSION.SDK_INT >= 33) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        ReminderReceiver.notificationsTurnedOff(context) -> {
                            openNotificationSettings(context)
                        }
                        else -> {
                            scheduler.scheduleTest()
                            testScheduled = true
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            ) {
                Text("Try a reminder")
            }
            if (testScheduled) {
                Text(
                    "A reminder is scheduled for about a minute from now.",
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SettingsGroup(title = "Session finished") {
            Text(
                "One notice after the timing feed says the session has finished. It is separate from the scheduled reminders.",
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                label = "Session finished",
                checked = preferences.sessionFinished,
                onChecked = { onPreferences(preferences.copy(sessionFinished = it)) },
            )
            Text(
                "This posts one sample. It does not mean a session has finished.",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    when {
                        ReminderReceiver.missingNotificationPermission(context) -> {
                            armFinishTest = true
                            if (Build.VERSION.SDK_INT >= 33) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        ReminderReceiver.notificationsTurnedOff(context) -> {
                            openNotificationSettings(context)
                        }
                        else -> {
                            showTestSessionFinish(context.applicationContext)
                            finishShown = true
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            ) {
                Text("Try a finished notice")
            }
            if (finishShown) {
                Text(
                    "A session-finished notice was shown.",
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = trackerCardColors(),
            border = trackerCardBorder(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                content = content,
            )
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent)
}

@Composable
private fun LeadMinutesRow(minutes: Int, onMinutes: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = { onMinutes(steppedLeadMinutes(minutes, -LEAD_STEP_MINUTES)) },
            enabled = minutes > MIN_LEAD_MINUTES,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text("−")
        }
        Text(
            "$minutes minutes before",
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            onClick = { onMinutes(steppedLeadMinutes(minutes, LEAD_STEP_MINUTES)) },
            enabled = minutes < MAX_LEAD_MINUTES,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text("+")
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
