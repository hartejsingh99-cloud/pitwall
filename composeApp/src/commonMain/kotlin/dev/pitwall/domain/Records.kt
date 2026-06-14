package dev.pitwall.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Records Book domain: the metric catalogue, the precomputed-vs-computed source switch, value
 * formatting, and rank shaping. Pure and multiplatform — no java.*, no String.format. The repository
 * maps SQLDelight rows into [RecordRow]; this file decides WHICH query to run and HOW to render it.
 */

/** Which entity a leaderboard ranks. */
enum class RecordScope { DRIVERS, CONSTRUCTORS }

/**
 * A rankable all-time metric. [isCount] marks integer-valued metrics (rendered without a decimal);
 * POINTS is the only fractional metric (rendered to at most one decimal). The remaining flags say
 * which scopes expose the metric and whether an era-sliced (computed) query exists for it.
 */
enum class RecordMetric(
    val label: String,
    val isCount: Boolean,
    private val drivers: Boolean,
    private val constructors: Boolean,
    /** True when this metric has an era-sliced computed query in the DRIVERS scope. */
    val eraComputableForDrivers: Boolean,
) {
    WINS("Wins", true, drivers = true, constructors = true, eraComputableForDrivers = true),
    POLES("Poles", true, drivers = true, constructors = true, eraComputableForDrivers = true),
    PODIUMS("Podiums", true, drivers = true, constructors = true, eraComputableForDrivers = true),
    FASTEST_LAPS("Fastest Laps", true, drivers = true, constructors = false, eraComputableForDrivers = true),
    CHAMPIONSHIPS("Championships", true, drivers = true, constructors = true, eraComputableForDrivers = false),
    POINTS("Points", false, drivers = true, constructors = true, eraComputableForDrivers = false),
    STARTS("Starts", true, drivers = true, constructors = false, eraComputableForDrivers = false),
    GRAND_SLAMS("Grand Slams", true, drivers = true, constructors = false, eraComputableForDrivers = false),
    ONE_TWOS("1-2 Finishes", true, drivers = false, constructors = true, eraComputableForDrivers = false);

    fun availableIn(scope: RecordScope): Boolean =
        if (scope == RecordScope.DRIVERS) drivers else constructors

    companion object {
        /** Metrics offered for [scope], in display order (the enum's declaration order). */
        fun forScope(scope: RecordScope): List<RecordMetric> = entries.filter { it.availableIn(scope) }
    }
}

/** Inclusive season window for the optional era filter. Either bound may be null (open end). */
data class EraFilter(val yearFrom: Int?, val yearTo: Int?) {
    /** An era with no bounds set is equivalent to no filter at all. */
    val isUnbounded: Boolean get() = yearFrom == null && yearTo == null
}

/** Where a leaderboard's numbers come from. */
enum class RecordSource { PRECOMPUTED, COMPUTED }

/**
 * Decide the data source. Use the cheap precomputed total columns unless the user set an era window
 * AND the (metric, scope) pair actually has an era-sliced query — otherwise fall back to precomputed
 * (so the UI shows all-time numbers rather than nothing).
 */
fun recordSource(metric: RecordMetric, scope: RecordScope, era: EraFilter?): RecordSource {
    val hasEra = era != null && !era.isUnbounded
    val computable = scope == RecordScope.DRIVERS && metric.eraComputableForDrivers
    return if (hasEra && computable) RecordSource.COMPUTED else RecordSource.PRECOMPUTED
}

/** One leaderboard line: a display name and an already-formatted value string. */
data class RecordRow(val name: String, val value: String)

/** A single On-This-Day result: who won which Grand Prix at which circuit, in [year]. */
data class OnThisDayEntry(
    val year: Int,
    val grandPrix: String,
    val circuit: String,
    val place: String,
    val winner: String,
    val constructor: String,
)

/** A [RecordRow] with its 1-based position in the ranked list. */
data class RankedRecord(val rank: Int, val row: RecordRow)

/**
 * Shape a query result into a ranked list. The queries already ORDER BY value DESC, so this is a
 * stable passthrough that only attaches 1-based ranks. Ties keep query order (no dense/standard
 * competition ranking — the value column itself disambiguates equal totals for the reader).
 */
fun rankRecords(rows: List<RecordRow>): List<RankedRecord> =
    rows.mapIndexed { i, row -> RankedRecord(i + 1, row) }

/**
 * Format a raw metric value for display. Counts render as plain integers; POINTS renders to at most
 * one decimal with a trailing ".0" trimmed. Multiplatform-safe (no String.format / no Double.toString
 * scientific-notation surprises): we scale, round, and assemble the digits by hand.
 */
fun formatRecordValue(metric: RecordMetric, value: Double): String =
    if (metric.isCount) value.roundToLong().toString() else formatPointsUpTo1dp(value)

/** Points to at most one decimal place, trailing ".0" trimmed. e.g. 1000.0 -> "1000", 12.34 -> "12.3". */
private fun formatPointsUpTo1dp(v: Double): String {
    val sign = if (v < 0) "-" else ""
    val scaled = (abs(v) * 10).roundToLong()   // tenths, rounded half-up
    val whole = scaled / 10
    val tenth = scaled % 10
    return if (tenth == 0L) "$sign$whole" else "$sign$whole.$tenth"
}
