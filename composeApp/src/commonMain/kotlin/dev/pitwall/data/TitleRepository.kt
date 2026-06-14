package dev.pitwall.data

import dev.pitwall.db.F1db
import dev.pitwall.domain.PointsSystem
import dev.pitwall.domain.TitleStatus
import dev.pitwall.domain.clinchScenario
import dev.pitwall.domain.maxRemaining
import dev.pitwall.domain.pointsSystemFor
import dev.pitwall.domain.titleAliveSimple
import dev.pitwall.domain.titleAliveStrict

/** One standings row enriched with title-projection fields (projection fields null in historical mode). */
data class TitleRow(
    val positionDisplayOrder: Int,
    val positionText: String,           // verbatim status (1, 2, ... or DNF/NC/etc. for season rows)
    val entityId: String,               // driver_id or constructor_id
    val name: String,
    val points: Double,
    // projection-only fields (null when historical):
    val status: TitleStatus? = null,
    val maxReachable: Double? = null,   // current points + all remaining
    val gapToLeader: Double? = null,    // leader points - this row's points (0 for the leader)
)

/** What the calculator can say about a season. */
sealed interface TitleState {
    /** Pre-2010: standings shown, but no permutation projection. */
    data class HistoricalOnly(
        val year: Int,
        val rows: List<TitleRow>,
        val dataStamp: String,           // dynamic, e.g. "data through 2008 R18 (Brazil)"
    ) : TitleState

    /** 2010+: full projection. */
    data class Projection(
        val year: Int,
        val isConstructor: Boolean,
        val strict: Boolean,
        val rows: List<TitleRow>,
        val remainingGps: Int,
        val remainingSprints: Int,
        val maxAvailable: Int,            // headline ceiling: +Z available
        val clinchMessage: String?,      // non-null when the leader has clinched
        val dataStamp: String,           // dynamic, e.g. "data through 2026 R6 (Monaco)"
    ) : TitleState

    /** No standings rows for this season in the bundle. */
    data class Empty(val year: Int) : TitleState
}

private const val PROJECTION_FROM_YEAR = 2010

class TitleRepository(private val db: F1db) {

    fun seasons(): List<Int> =
        db.f1dbQueries.selectSeasons().executeAsList().map { it.toInt() }

    /**
     * Build the title picture for [year]. For [isConstructor] true, uses the constructors' championship;
     * otherwise drivers'. [strict] selects the can-beat-best-rival test over the simpler can-reach-leader test.
     * Years < 2010 always return [TitleState.HistoricalOnly] regardless of the toggle flags.
     */
    fun titleState(year: Int, isConstructor: Boolean, strict: Boolean): TitleState {
        val raw = if (isConstructor) {
            db.standingsQueries.currentConstructorStandings(year.toLong()).executeAsList()
                .map { StandingRow(it.position_display_order.toInt(), it.position_text, it.constructor_id, it.name, it.points) }
        } else {
            db.standingsQueries.currentDriverStandings(year.toLong()).executeAsList()
                .map { StandingRow(it.position_display_order.toInt(), it.position_text, it.driver_id, it.full_name, it.points) }
        }

        if (raw.isEmpty()) return TitleState.Empty(year)

        // Dynamic freshness stamp from the deepest run round (NOT a hardcoded "R6 (Monaco)" literal,
        // which would be wrong for any season other than 2026). For 2026 this resolves to R6 (Monaco).
        val latest = db.standingsQueries.latestRunRoundLabel(year.toLong()).executeAsOneOrNull()
        val dataStamp = if (latest != null) {
            "data through $year R${latest.round} (${latest.gp_name})"
        } else {
            "data through $year"
        }

        if (year < PROJECTION_FROM_YEAR) {
            return TitleState.HistoricalOnly(
                year = year,
                rows = raw.map {
                    TitleRow(it.order, it.text, it.id, it.name, it.points)
                },
                dataStamp = dataStamp,
            )
        }

        val rounds = db.standingsQueries.remainingRounds(year.toLong()).executeAsOne()
        // SQLDelight types a scalar subselect in the SELECT list as nullable (a subquery could
        // return no rows), so remaining_gps/remaining_sprints may generate as Long? even though
        // COUNT(*) is never NULL. The ?: 0L keeps this compiling whether the column is Long or Long?.
        val remainingGps = (rounds.remaining_gps ?: 0L).toInt()
        val remainingSprints = (rounds.remaining_sprints ?: 0L).toInt()
        val sys: PointsSystem = pointsSystemFor(year)
        val maxAvailable = maxRemaining(remainingGps, remainingSprints, sys, isConstructor)

        val pointsById = raw.associate { it.id to it.points }
        val nameById = raw.associate { it.id to it.name }
        val statuses = if (strict) titleAliveStrict(pointsById, maxAvailable)
                       else titleAliveSimple(pointsById, maxAvailable)
        val leaderPoints = raw.maxOf { it.points }
        val clinch = clinchScenario(pointsById, maxAvailable, nameById)

        val rows = raw.map {
            TitleRow(
                positionDisplayOrder = it.order,
                positionText = it.text,
                entityId = it.id,
                name = it.name,
                points = it.points,
                status = statuses[it.id],
                maxReachable = it.points + maxAvailable,
                gapToLeader = leaderPoints - it.points,
            )
        }

        return TitleState.Projection(
            year = year,
            isConstructor = isConstructor,
            strict = strict,
            rows = rows,
            remainingGps = remainingGps,
            remainingSprints = remainingSprints,
            maxAvailable = maxAvailable,
            clinchMessage = clinch,
            dataStamp = dataStamp,
        )
    }

    /** Internal flat shape unifying driver and constructor standing rows. */
    private data class StandingRow(
        val order: Int,
        val text: String,
        val id: String,
        val name: String,
        val points: Double,
    )
}
