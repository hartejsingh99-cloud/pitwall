package dev.pitwall.data

import dev.pitwall.db.F1db
import dev.pitwall.domain.EraFilter
import dev.pitwall.domain.OnThisDayEntry
import dev.pitwall.domain.RecordMetric
import dev.pitwall.domain.RecordRow
import dev.pitwall.domain.RecordScope
import dev.pitwall.domain.RecordSource
import dev.pitwall.domain.formatRecordValue
import dev.pitwall.domain.recordSource

/**
 * Maps SQLDelight rows for Feature D into domain [RecordRow] / [OnThisDayEntry]. The mapper-overload
 * form of each generated query (q.<label>(args) { cols -> ... }) is used throughout so we depend on
 * column ORDER, not on generated data-class property names, and so aggregate nullability (SUM/COUNT)
 * is absorbed at the lambda boundary.
 *
 * Every leaderboard query selects exactly (id TEXT, full_name TEXT, value <number>). Count metrics
 * yield Long (or Long? for SUM); points yields Double. [formatRecordValue] renders per metric.
 */
class RecordsRepository(private val db: F1db) {

    private val q get() = db.recordsQueries

    /** Top [limit] rows for [metric] in [scope], honoring an optional [era] window when supported. */
    fun leaderboard(
        metric: RecordMetric,
        scope: RecordScope,
        era: EraFilter?,
        limit: Long = DEFAULT_LIMIT,
    ): List<RecordRow> = when (recordSource(metric, scope, era)) {
        RecordSource.PRECOMPUTED -> precomputed(metric, scope, limit)
        RecordSource.COMPUTED -> computed(metric, era!!, limit)
    }

    private fun precomputed(metric: RecordMetric, scope: RecordScope, limit: Long): List<RecordRow> =
        if (scope == RecordScope.DRIVERS) precomputedDriver(metric, limit)
        else precomputedConstructor(metric, limit)

    private fun precomputedDriver(metric: RecordMetric, limit: Long): List<RecordRow> = when (metric) {
        RecordMetric.WINS ->
            q.topDriversByWins(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.POLES ->
            q.topDriversByPoles(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.PODIUMS ->
            q.topDriversByPodiums(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.FASTEST_LAPS ->
            q.topDriversByFastestLaps(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.CHAMPIONSHIPS ->
            q.topDriversByTitles(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.POINTS ->
            q.topDriversByPoints(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.STARTS ->
            q.topDriversByStarts(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.GRAND_SLAMS ->
            q.topDriversByGrandSlams(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.ONE_TWOS -> emptyList() // not a driver metric
    }

    private fun precomputedConstructor(metric: RecordMetric, limit: Long): List<RecordRow> = when (metric) {
        RecordMetric.WINS ->
            q.topConstructorsByWins(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.CHAMPIONSHIPS ->
            q.topConstructorsByTitles(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.POLES ->
            q.topConstructorsByPoles(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.PODIUMS ->
            q.topConstructorsByPodiums(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.ONE_TWOS ->
            q.topConstructorsByOneTwos(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.POINTS ->
            q.topConstructorsByPoints(limit) { _, name, v -> row(metric, name, v) }.executeAsList()
        RecordMetric.FASTEST_LAPS, RecordMetric.STARTS, RecordMetric.GRAND_SLAMS ->
            emptyList() // not constructor metrics in this lane
    }

    private fun computed(metric: RecordMetric, era: EraFilter, limit: Long): List<RecordRow> {
        val from = era.yearFrom?.toLong()
        val to = era.yearTo?.toLong()
        return when (metric) {
            RecordMetric.WINS ->
                q.winsFiltered(from, to, limit) { _, name, v -> row(metric, name, v) }.executeAsList()
            RecordMetric.POLES ->
                q.polesFiltered(from, to, limit) { _, name, v -> row(metric, name, v) }.executeAsList()
            RecordMetric.PODIUMS ->
                q.podiumsFiltered(from, to, limit) { _, name, v -> row(metric, name, v) }.executeAsList()
            RecordMetric.FASTEST_LAPS ->
                q.fastestLapsFiltered(from, to, limit) { _, name, v -> row(metric, name, v) }.executeAsList()
            else -> emptyList() // recordSource() guarantees we never reach here for other metrics
        }
    }

    /** Build a row from an integer count (possibly nullable from SUM) by widening to Double. */
    private fun row(metric: RecordMetric, name: String, value: Long?): RecordRow =
        RecordRow(name, formatRecordValue(metric, (value ?: 0L).toDouble()))

    /** Build a row from a REAL points column. */
    private fun row(metric: RecordMetric, name: String, value: Double): RecordRow =
        RecordRow(name, formatRecordValue(metric, value))

    /** Races run on calendar [mmdd] ("MM-DD"), newest season first, with the winner + constructor. */
    fun onThisDay(mmdd: String): List<OnThisDayEntry> =
        q.onThisDay(mmdd) { year, gpName, circuitName, placeName, winnerName, constructorName ->
            OnThisDayEntry(
                year = year.toInt(),
                grandPrix = gpName,
                circuit = circuitName,
                place = placeName,
                winner = winnerName,
                constructor = constructorName,
            )
        }.executeAsList()

    companion object {
        const val DEFAULT_LIMIT: Long = 25L
    }
}
