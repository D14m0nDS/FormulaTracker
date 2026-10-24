package com.formulatracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.formulatracker.ui.theme.Sizes
import com.formulatracker.ui.theme.Spacing
import com.formulatracker.ui.theme.tabular

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
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (!notificationsAllowed) {
            NoticeBanner(
                message = "Notifications are off, so reminders cannot appear.",
                tone = NoticeTone.Error,
                actionLabel = "Allow notifications",
                onAction = { openNotificationSettings(context) },
            )
        }
        if (!exactAlarmsAllowed) {
            NoticeBanner(
                message = "Exact alarms are off, so reminders may arrive late.",
                tone = NoticeTone.Caution,
                actionLabel = "Allow exact alarms",
                onAction = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                },
            )
        }
        Text(
            "These follow the published start time. They do not confirm that a session has started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup(title = "Sessions") {
            REMINDER_SESSION_TYPES.forEachIndexed { index, sessionType ->
                if (index > 0) RowDivider()
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
            Description("Monday at 9:00 in this phone's local time, in the week that contains the race.")
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
            LeadMinutesRow(
                minutes = usableLeadMinutes(preferences.leadMinutes),
                onMinutes = { onPreferences(preferences.copy(leadMinutes = it)) },
            )
            RowDivider()
            ToggleRow(
                label = "At the scheduled start",
                checked = preferences.atStart,
                onChecked = { onPreferences(preferences.copy(atStart = it)) },
            )
            RowDivider()
            TryAction(
                label = "Try a reminder",
                confirmation = "A reminder is scheduled for about a minute from now.",
                confirmed = testScheduled,
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
            )
        }
        SettingsGroup(title = "Session finished") {
            Description(
                "One notice after the timing feed says the session has finished. It is separate from the scheduled reminders.",
            )
            ToggleRow(
                label = "Session finished",
                checked = preferences.sessionFinished,
                onChecked = { onPreferences(preferences.copy(sessionFinished = it)) },
            )
            RowDivider()
            Description("This posts one sample. It does not mean a session has finished.")
            TryAction(
                label = "Try a finished notice",
                confirmation = "A session-finished notice was shown.",
                confirmed = finishShown,
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
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionLabel(title, modifier = Modifier.padding(horizontal = Spacing.xs))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = trackerCardColors(),
            border = trackerCardBorder(),
            content = content,
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun Description(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { onMinutes(steppedLeadMinutes(minutes, -LEAD_STEP_MINUTES)) },
            enabled = minutes > MIN_LEAD_MINUTES,
            modifier = Modifier.size(Sizes.touchTarget),
        ) {
            Icon(
                AppIcons.Minus,
                contentDescription = "Reduce by $LEAD_STEP_MINUTES minutes",
                modifier = Modifier.size(Sizes.icon),
            )
        }
        Text(
            "$minutes minutes before",
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.sm)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        FilledTonalIconButton(
            onClick = { onMinutes(steppedLeadMinutes(minutes, LEAD_STEP_MINUTES)) },
            enabled = minutes < MAX_LEAD_MINUTES,
            modifier = Modifier.size(Sizes.touchTarget),
        ) {
            Icon(
                AppIcons.Plus,
                contentDescription = "Increase by $LEAD_STEP_MINUTES minutes",
                modifier = Modifier.size(Sizes.icon),
            )
        }
    }
}

// The whole row toggles, so the label is as easy to hit as the switch itself.
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChecked, role = Role.Switch)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = if (checked) {
                {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                }
            } else {
                null
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                checkedIconColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

@Composable
private fun TryAction(label: String, confirmation: String, confirmed: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
            Icon(AppIcons.Reminders, contentDescription = null, modifier = Modifier.size(Sizes.iconSmall))
            Spacer(Modifier.width(Spacing.sm))
            Text(label)
        }
        AnimatedVisibility(
            visible = confirmed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            NoticeBanner(message = confirmation, tone = NoticeTone.Info)
        }
    }
}
