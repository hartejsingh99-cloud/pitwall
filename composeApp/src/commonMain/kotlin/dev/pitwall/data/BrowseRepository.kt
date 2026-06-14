package dev.pitwall.data

import dev.pitwall.db.F1db
import dev.pitwall.domain.AvailableSessions
import dev.pitwall.domain.RaceListItem

// ---- Display models consumed by the Browse ViewModels/Screens --------------
// Kept here (data layer) so the UI never touches generated row classes directly.

data class RaceResultRow(
    val positionText: String,
    val driverName: String,
    val constructorName: String,
    val gridPosition: Long?,
    val points: Double,
    val time: String?,
    val gap: String?,
    val laps: Long?,
    val reasonRetired: String?,
    val fastestLap: Boolean,
    val polePosition: Boolean,
)

data class QualifyingResultRow(
    val positionText: String,
    val driverName: String,
    val q1Millis: Long?,
    val q2Millis: Long?,
    val q3Millis: Long?,
    val bestMillis: Long?,
    val gap: String?,
)

data class SprintRaceResultRow(
    val positionText: String,
    val driverName: String,
    val constructorName: String,
    val points: Double,
    val gap: String?,
    val reasonRetired: String?,
)

data class SprintQualifyingResultRow(
    val positionText: String,
    val driverName: String,
    val q1Millis: Long?,
    val q2Millis: Long?,
    val q3Millis: Long?,
    val bestMillis: Long?,
)

data class StandingRow(
    val positionText: String,
    val name: String,
    val points: Double,
    val championshipWon: Boolean,
)

/** Everything the RaceResult screen needs in one shot: which tabs + their rows. */
data class RaceSessions(
    val available: AvailableSessions,
    val qualifying: List<QualifyingResultRow>,
    val sprintQualifying: List<SprintQualifyingResultRow>,
    val sprint: List<SprintRaceResultRow>,
    val race: List<RaceResultRow>,
)

class BrowseRepository(private val db: F1db) {
    private val q get() = db.browseQueries

    fun seasons(): List<Int> =
        q.browseSeasons().executeAsList().map { it.toInt() }

    fun racesInSeason(year: Long): List<RaceListItem> =
        q.racesInSeason(year).executeAsList().map {
            RaceListItem(
                raceId = it.race_id.toInt(),
                round = it.round.toInt(),
                grandPrixName = it.gp_name,
                circuitName = it.circuit_name,
                placeName = it.place_name,
                date = it.date,
                resultCount = it.result_count.toInt(),
                sprintQualifyingFormat = it.sprint_qualifying_format,
            )
        }

    /** Loads only the sessions that actually have rows for [raceId] (canonical tab order in [AvailableSessions]). */
    fun raceSessions(raceId: Long): RaceSessions {
        val hasQuali = q.countQualifying(raceId).executeAsOne() > 0L
        val hasSprintQuali = q.countSprintQualifying(raceId).executeAsOne() > 0L
        val hasSprint = q.countSprint(raceId).executeAsOne() > 0L
        val hasRace = q.countRace(raceId).executeAsOne() > 0L

        return RaceSessions(
            available = AvailableSessions(hasQuali, hasSprintQuali, hasSprint, hasRace),
            qualifying = if (hasQuali) q.qualifyingResult(raceId).executeAsList().map {
                QualifyingResultRow(
                    positionText = it.position_text,
                    driverName = it.driver_name,
                    q1Millis = it.q1_millis,
                    q2Millis = it.q2_millis,
                    q3Millis = it.q3_millis,
                    bestMillis = it.best_millis,
                    gap = it.gap,
                )
            } else emptyList(),
            sprintQualifying = if (hasSprintQuali) q.sprintQualifyingResult(raceId).executeAsList().map {
                SprintQualifyingResultRow(
                    positionText = it.position_text,
                    driverName = it.driver_name,
                    q1Millis = it.q1_millis,
                    q2Millis = it.q2_millis,
                    q3Millis = it.q3_millis,
                    bestMillis = it.best_millis,
                )
            } else emptyList(),
            sprint = if (hasSprint) q.sprintRaceResult(raceId).executeAsList().map {
                SprintRaceResultRow(
                    positionText = it.position_text,
                    driverName = it.driver_name,
                    constructorName = it.constructor_name,
                    points = it.points,
                    gap = it.gap,
                    reasonRetired = it.reason_retired,
                )
            } else emptyList(),
            race = if (hasRace) q.raceResult(raceId).executeAsList().map {
                RaceResultRow(
                    positionText = it.position_text,
                    driverName = it.driver_name,
                    constructorName = it.constructor_name,
                    gridPosition = it.grid_position_number,
                    points = it.points,
                    time = it.time,
                    gap = it.gap,
                    laps = it.laps,
                    reasonRetired = it.reason_retired,
                    fastestLap = it.fastest_lap == 1L,
                    polePosition = it.pole_position == 1L,
                )
            } else emptyList(),
        )
    }

    fun driverStandings(year: Long): List<StandingRow> =
        q.seasonDriverStanding(year).executeAsList().map {
            StandingRow(
                positionText = it.position_text,
                name = it.driver_name,
                points = it.points,
                championshipWon = it.championship_won == 1L,
            )
        }

    fun constructorStandings(year: Long): List<StandingRow> =
        q.seasonConstructorStanding(year).executeAsList().map {
            StandingRow(
                positionText = it.position_text,
                name = it.constructor_name,
                points = it.points,
                championshipWon = it.championship_won == 1L,
            )
        }
}
