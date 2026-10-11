package com.formulatracker.drivers

data class DriverDirectory(
    val season: Int,
    val drivers: List<DriverPhoto>,
)

data class DriverPhoto(
    val code: String,
    val name: String,
    val photoUrl: String?,
)
