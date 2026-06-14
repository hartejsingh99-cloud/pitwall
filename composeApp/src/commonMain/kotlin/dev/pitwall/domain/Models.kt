package dev.pitwall.domain

data class QualiRow(
    val raceId: Int, val driverId: String, val constructorId: String,
    val timeMillis: Long?, val q1Millis: Long?, val q2Millis: Long?, val q3Millis: Long?,
)

data class DriverCarRating(
    val driverId: String,
    val events: Int,
    val headToHeadWins: Int,
    val oneLapRatingPct: Double,   // median of (-gap%); positive = faster than teammate
)
