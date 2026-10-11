package com.formulatracker.schedule

import java.time.Instant

data class SeasonSchedule(
    val season: Int,
    val events: List<RaceWeekend>,
)

data class RaceWeekend(
    val round: Int,
    val name: String,
    val circuitName: String,
    val locality: String,
    val country: String,
    val sessions: List<ScheduledSession>,
)

data class ScheduledSession(
    val type: String,
    val name: String,
    val start: Instant,
    val timezone: String?,
    val cancelled: Boolean,
)
