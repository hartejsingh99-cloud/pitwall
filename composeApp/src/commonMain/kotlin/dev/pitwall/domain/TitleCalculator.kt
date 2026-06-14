package dev.pitwall.domain

/**
 * Feature C — Title-Permutation Calculator (pure engine).
 *
 * "Can driver/constructor X still mathematically win the title?" Projection is scoped to 2010+
 * (the current 25-point era); the caller gates pre-2010 seasons to a historical-only view.
 *
 * All functions here are pure and unit-tested. No DB, no platform APIs, no floating-point points
 * tables stored as arrays (IntArray has reference equality, which bites data-class equals); we store
 * the few maxima we need as plain Ints.
 */

enum class TitleStatus { ALIVE, ELIMINATED }

/**
 * The per-era maxima we need to compute a remaining-points ceiling.
 *
 * @param gpMax        winner's GP points (25 across the whole 2010+ era).
 * @param gpSecond     runner-up's GP points (18 across the whole 2010+ era) — the second-best a single
 *                     constructor's other car can score in the same GP.
 * @param sprintMax    sprint winner's points (8 for 2022+, 3 for 2021, 0 = no sprints).
 * @param sprintSecond sprint runner-up's points (7 for 2022+, 2 for 2021, 0 = no sprints).
 * @param fastestLapPoint the bonus point for fastest lap (1 for 2019-2024, 0 otherwise; removed for 2025+).
 */
data class PointsSystem(
    val gpMax: Int,
    val gpSecond: Int,
    val sprintMax: Int,
    val sprintSecond: Int,
    val fastestLapPoint: Int,
)

/**
 * Era points table for [year]. Valid for 2010+ (current 25-point scale); callers must gate <2010
 * to a historical view before calling this — passing an earlier year throws.
 *
 *  - 2025+      : GP 25/18, sprint 8/7, NO fastest-lap point.
 *  - 2022-2024  : GP 25/18, sprint 8/7, fastest-lap point = 1.
 *  - 2021       : GP 25/18, sprint 3/2 (first, smaller sprint scale), fastest-lap point = 1.
 *  - 2019-2020  : GP 25/18, no sprints, fastest-lap point = 1.
 *  - 2010-2018  : GP 25/18, no sprints, no fastest-lap point.
 */
fun pointsSystemFor(year: Int): PointsSystem {
    require(year >= 2010) { "Title projection only defined for 2010+ (got $year); gate earlier seasons to historical." }
    val fl = if (year in 2019..2024) 1 else 0   // FL point: 2019-2024 only
    val sprintMax: Int
    val sprintSecond: Int
    when {
        year >= 2022 -> { sprintMax = 8; sprintSecond = 7 }
        year == 2021 -> { sprintMax = 3; sprintSecond = 2 }
        else -> { sprintMax = 0; sprintSecond = 0 }   // 2010-2020: no sprints
    }
    return PointsSystem(gpMax = 25, gpSecond = 18, sprintMax = sprintMax, sprintSecond = sprintSecond, fastestLapPoint = fl)
}

/**
 * Maximum points still scorable across [remainingGps] GPs and [remainingSprints] sprints under [sys].
 *
 *  - Driver:      per GP = gpMax (+ fastest-lap point if the era awards one); per sprint = sprintMax.
 *  - Constructor: both cars score, so per GP = gpMax + gpSecond (+ the single fastest-lap point);
 *                 per sprint = sprintMax + sprintSecond.
 *
 * 2026 examples: driver 16*25 + 3*8 = 424; constructor 16*43 + 3*15 = 733.
 */
fun maxRemaining(remainingGps: Int, remainingSprints: Int, sys: PointsSystem, isConstructor: Boolean): Int {
    val perGp = if (isConstructor) sys.gpMax + sys.gpSecond + sys.fastestLapPoint
                else sys.gpMax + sys.fastestLapPoint
    val perSprint = if (isConstructor) sys.sprintMax + sys.sprintSecond
                    else sys.sprintMax
    return remainingGps * perGp + remainingSprints * perSprint
}

/**
 * "Simple" alive test: a contender is ALIVE iff their own ceiling can reach the CURRENT leader's
 * current points — i.e. points[d] + remaining >= max(points). The leader is trivially alive.
 * This is the figure most broadcasts quote ("X needs to make up N points"); it ignores the fact
 * that the leader also scores in the remaining rounds.
 */
fun titleAliveSimple(points: Map<String, Double>, remaining: Int): Map<String, TitleStatus> {
    if (points.isEmpty()) return emptyMap()
    val leaderPoints = points.values.max()
    return points.mapValues { (_, p) ->
        if (p + remaining >= leaderPoints) TitleStatus.ALIVE else TitleStatus.ELIMINATED
    }
}

/**
 * "Strict" alive test: a contender is ALIVE iff their own ceiling can reach the best CURRENT total
 * among RIVALS (everyone except themselves) — points[d] + remaining >= max over r != d of points[r].
 * For the leader this compares against P2 (so a leader far enough ahead can be the only one alive);
 * a sole entrant has no rivals and is alive by default.
 */
fun titleAliveStrict(points: Map<String, Double>, remaining: Int): Map<String, TitleStatus> {
    if (points.isEmpty()) return emptyMap()
    return points.mapValues { (id, p) ->
        val bestRival = points.filterKeys { it != id }.values.maxOrNull()
        if (bestRival == null || p + remaining >= bestRival) TitleStatus.ALIVE else TitleStatus.ELIMINATED
    }
}

/**
 * If the current leader has mathematically clinched the title, a human-readable message; else null.
 * Clinched iff the leader's margin over P2 is strictly greater than the points still available
 * (a margin exactly equal to remaining leaves P2 a tie scenario, so it is NOT a clinch).
 * Returns null when there is no second contender or no points are at stake.
 */
fun clinchScenario(points: Map<String, Double>, remaining: Int): String? {
    if (points.size < 2) return null
    val sorted = points.entries.sortedByDescending { it.value }
    val leader = sorted[0]
    val second = sorted[1]
    val margin = leader.value - second.value
    if (margin <= remaining) return null
    return "${leader.key} has clinched the title — lead of ${formatPoints(margin)} exceeds the ${remaining} points still available."
}

/** Points as a compact string: drop a trailing ".0", keep one decimal otherwise. No String.format. */
internal fun formatPoints(v: Double): String {
    val rounded = kotlin.math.round(v * 10.0) / 10.0
    val whole = rounded.toLong()
    return if (rounded == whole.toDouble()) whole.toString()
    else {
        val tenths = (kotlin.math.round(kotlin.math.abs(rounded - whole) * 10.0)).toLong()
        "$whole.$tenths"
    }
}
