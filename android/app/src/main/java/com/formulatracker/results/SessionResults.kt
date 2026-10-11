package com.formulatracker.results

data class QualifyingResults(
    val sessionType: String,
    val sessionName: String,
    val segmentCodes: List<String>,
    val rows: List<QualifyingRow>,
)

data class QualifyingRow(
    val positionText: String,
    val code: String,
    val team: String,
    val status: String?,
    val segments: List<SegmentTime>,
)

data class SegmentTime(
    val code: String,
    val time: String?,
)

data class PracticeResults(
    val sessionType: String,
    val sessionName: String,
    val rows: List<PracticeRow>,
)

data class PracticeRow(
    val positionText: String,
    val code: String,
    val team: String,
    val time: String?,
    val laps: Int?,
)

data class RaceResults(
    val sessionType: String,
    val sessionName: String,
    val rows: List<RaceRow>,
)

data class RaceRow(
    val positionText: String,
    val code: String,
    val team: String,
    val status: String?,
    val classified: Boolean,
    val time: String?,
    val points: Double?,
    val grid: Int?,
)

sealed interface SessionResults {
    data class Qualifying(val results: QualifyingResults) : SessionResults
    data class Practice(val results: PracticeResults) : SessionResults
    data class Race(val results: RaceResults) : SessionResults
}

class ResultsNotFound : Exception("No results yet")
