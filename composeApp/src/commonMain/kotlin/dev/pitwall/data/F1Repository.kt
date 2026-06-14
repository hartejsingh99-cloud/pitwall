package dev.pitwall.data

import dev.pitwall.db.F1db
import dev.pitwall.db.QualifyingForSeason
import dev.pitwall.domain.DriverCarRating
import dev.pitwall.domain.QualiRow
import dev.pitwall.domain.computeDriverVsCar

fun QualifyingForSeason.toDomain() =
    QualiRow(race_id.toInt(), driver_id, constructor_id, time_millis, q1_millis, q2_millis, q3_millis)

class F1Repository(private val db: F1db) {
    fun seasons(): List<Int> =
        db.f1dbQueries.selectSeasons().executeAsList().map { it.toInt() }

    /** Drivers ranked by teammate-normalized one-lap rating for [year], paired with a display name. */
    fun ratingsForSeason(year: Long): List<Pair<DriverCarRating, String>> {
        val rows = db.f1dbQueries.qualifyingForSeason(year).executeAsList()
        val names = rows.associate { it.driver_id to "${it.driver_name} (${it.abbreviation})" }
        val ratings = computeDriverVsCar(rows.map { it.toDomain() })
        return ratings.filter { it.events > 0 }
            .sortedByDescending { it.oneLapRatingPct }
            .map { it to (names[it.driverId] ?: it.driverId) }
    }
}
