package dev.pitwall.domain

// ============================================================================
// Feature B — pure career + teammate head-to-head engine.
//
// Reuses symmetricGapPct from DriverVsCar.kt for the one-lap gap. Career mode joins
// the two drivers' rows by raceId and counts who was faster / finished ahead.
// Teammate mode keeps only the races where both shared a constructor, groups by
// (year, constructorId), and reports the median per-race gap with positive = A faster.
// ============================================================================

/** One qualifying row for one driver in one race; bestMillis is the COALESCE'd lap time (FACT 2). */
data class H2HQualiRow(
    val raceId: Int,
    val year: Int,
    val driverId: String,
    val constructorId: String,
    val bestMillis: Long?,
)

/** One race finish for one driver; positionNumber is NULL unless the driver was classified. */
data class H2HRaceRow(
    val raceId: Int,
    val year: Int,
    val driverId: String,
    val constructorId: String,
    val positionNumber: Long?,
)

data class CareerH2H(
    val commonQualiSessions: Int,
    val qualiWinsA: Int,
    val qualiWinsB: Int,
    val commonRaces: Int,
    val raceWinsA: Int,
    val raceWinsB: Int,
)

data class TeammateStint(
    val year: Int,
    val constructorId: String,
    val sessions: Int,
    val aAhead: Int,
    val medianGapPctA: Double,   // positive = A faster than B over the stint
    /**
     * Constructor display name for the stint label. The pure engine only knows the slug
     * [constructorId], so it defaults the name to the slug; the repo (which has the DB)
     * fills in the real name via [withConstructorNames].
     */
    val constructorName: String = constructorId,
)

/** Replace each stint's [TeammateStint.constructorName] using a slug -> display-name map. */
fun HeadToHeadResult.withConstructorNames(names: Map<String, String>): HeadToHeadResult =
    copy(
        teammateStints = teammateStints.map { st ->
            st.copy(constructorName = names[st.constructorId] ?: st.constructorId)
        },
    )

data class HeadToHeadResult(
    val career: CareerH2H,
    val teammateStints: List<TeammateStint>,
    val directGapComputable: Boolean,
)

/**
 * Signed one-lap gap from A's perspective: positive when A's lap [aBest] is faster than B's [bBest].
 * Negates [symmetricGapPct] (which is negative when its first arg is faster) so higher = better for A.
 * Returns null unless both drivers set a valid (>0) time.
 */
internal fun gapPctAFaster(aBest: Long?, bBest: Long?): Double? {
    if (aBest == null || aBest <= 0 || bBest == null || bBest <= 0) return null
    return -symmetricGapPct(aBest, bBest)
}

fun computeHeadToHead(
    qualiRows: List<H2HQualiRow>,
    raceRows: List<H2HRaceRow>,
    a: String,
    b: String,
): HeadToHeadResult {
    // ---- Career qualifying: pair the two drivers' rows per race, compare best laps. ----
    var commonQualiSessions = 0
    var qualiWinsA = 0
    var qualiWinsB = 0
    qualiRows.groupBy { it.raceId }.forEach { (_, rows) ->
        val ra = rows.firstOrNull { it.driverId == a } ?: return@forEach
        val rb = rows.firstOrNull { it.driverId == b } ?: return@forEach
        val gap = gapPctAFaster(ra.bestMillis, rb.bestMillis) ?: return@forEach
        commonQualiSessions++
        if (gap > 0) qualiWinsA++ else if (gap < 0) qualiWinsB++
    }

    // ---- Career races: only where both classified (positionNumber != null). ----
    var commonRaces = 0
    var raceWinsA = 0
    var raceWinsB = 0
    raceRows.groupBy { it.raceId }.forEach { (_, rows) ->
        val ra = rows.firstOrNull { it.driverId == a } ?: return@forEach
        val rb = rows.firstOrNull { it.driverId == b } ?: return@forEach
        val pa = ra.positionNumber ?: return@forEach
        val pb = rb.positionNumber ?: return@forEach
        commonRaces++
        if (pa < pb) raceWinsA++ else if (pb < pa) raceWinsB++
    }

    // ---- Teammate stints: keep only races where both shared a constructor that race. ----
    data class PairedRace(val year: Int, val constructorId: String, val gapA: Double)
    val paired = qualiRows.groupBy { it.raceId }.mapNotNull { (_, rows) ->
        val ra = rows.firstOrNull { it.driverId == a } ?: return@mapNotNull null
        val rb = rows.firstOrNull { it.driverId == b } ?: return@mapNotNull null
        if (ra.constructorId != rb.constructorId) return@mapNotNull null   // not same car that race
        val gap = gapPctAFaster(ra.bestMillis, rb.bestMillis) ?: return@mapNotNull null
        PairedRace(ra.year, ra.constructorId, gap)
    }

    val stints = paired
        .groupBy { it.year to it.constructorId }
        .map { (key, group) ->
            val gaps = group.map { it.gapA }
            TeammateStint(
                year = key.first,
                constructorId = key.second,
                sessions = group.size,
                aAhead = group.count { it.gapA > 0 },
                medianGapPctA = median(gaps),
            )
        }
        .sortedWith(compareBy({ it.year }, { it.constructorId }))

    return HeadToHeadResult(
        career = CareerH2H(commonQualiSessions, qualiWinsA, qualiWinsB, commonRaces, raceWinsA, raceWinsB),
        teammateStints = stints,
        directGapComputable = stints.isNotEmpty(),
    )
}

/** Small private median (DriverVsCar.kt's is private; replicated per lane rules). */
private fun median(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}
