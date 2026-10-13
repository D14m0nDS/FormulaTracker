package com.formulatracker.reminders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGateTest {
    @Test
    fun eitherAMissingPermissionOrADisabledSwitchBlocksReminders() {
        assertTrue(notificationsBlockReminders(postNotificationsMissing = true, appNotificationsEnabled = true))
        assertTrue(notificationsBlockReminders(postNotificationsMissing = false, appNotificationsEnabled = false))
        assertTrue(notificationsBlockReminders(postNotificationsMissing = true, appNotificationsEnabled = false))
        assertFalse(notificationsBlockReminders(postNotificationsMissing = false, appNotificationsEnabled = true))
    }
}
