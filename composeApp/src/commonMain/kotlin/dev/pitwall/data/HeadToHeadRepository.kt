package dev.pitwall.data

import dev.pitwall.db.F1db
import dev.pitwall.domain.H2HQualiRow
import dev.pitwall.domain.H2HRaceRow
import dev.pitwall.domain.HeadToHeadResult
import dev.pitwall.domain.computeHeadToHead

/** A driver option in the picker. */
data class DriverPick(
    val id: String,
    val fullName: String,
    val wins: Long,
    val titles: Long,
)

/** Precomputed career totals for one driver (the side-by-side card). */
data class DriverTotals(
    val id: String,
    val fullName: String,
    val wins: Long,
    val podiums: Long,
    val poles: Long,
    val fastestLaps: Long,
    val points: Double,
    val titles: Long,
    val starts: Long,
)

/** Everything the H2H screen needs once both drivers are chosen. */
data class HeadToHeadData(
    val driverA: DriverTotals,
    val driverB: DriverTotals,
    val result: HeadToHeadResult,
)

class HeadToHeadRepository(private val db: F1db) {

    fun searchDrivers(query: String): List<DriverPick> {
        if (query.isBlank()) return emptyList()
        return db.headToHeadQueries.searchDrivers(query).executeAsList().map {
            DriverPick(it.id, it.full_name, it.total_race_wins, it.total_championship_wins)
        }
    }

    private fun totals(driverId: String): DriverTotals? =
        db.headToHeadQueries.driverCareerTotals(driverId).executeAsOneOrNull()?.let {
            DriverTotals(
                id = it.id,
                fullName = it.full_name,
                wins = it.total_race_wins,
                podiums = it.total_podiums,
                poles = it.total_pole_positions,
                fastestLaps = it.total_fastest_laps,
                points = it.total_points,
                titles = it.total_championship_wins,
                starts = it.total_race_starts,
            )
        }

    /** Returns null if either driver id is unknown. */
    fun headToHead(driverA: String, driverB: String): HeadToHeadData? {
        val a = totals(driverA) ?: return null
        val b = totals(driverB) ?: return null

        val quali = db.headToHeadQueries.commonQualifyingRows(driverA, driverB).executeAsList().map {
            H2HQualiRow(
                raceId = it.race_id.toInt(),
                year = it.year.toInt(),
                driverId = it.driver_id,
                constructorId = it.constructor_id,
                bestMillis = it.best_millis,
            )
        }
        val races = db.headToHeadQueries.commonRaceFinishes(driverA, driverB).executeAsList().map {
            H2HRaceRow(
                raceId = it.race_id.toInt(),
                year = it.year.toInt(),
                driverId = it.driver_id,
                constructorId = it.constructor_id,
                positionNumber = it.position_number,
            )
        }
        val result = computeHeadToHead(quali, races, driverA, driverB)
        return HeadToHeadData(a, b, result)
    }
}
