package dev.pitwall.domain

/** Symmetric percent gap: 100*(ti-tj)/((ti+tj)/2). Negative when i is faster. */
fun symmetricGapPct(ti: Long, tj: Long): Double =
    100.0 * (ti - tj) / ((ti + tj) / 2.0)

/** Deepest segment Q3>Q2>Q1 where BOTH set a time; else both combined times; null if neither comparable. */
fun lastCommonSegment(i: QualiRow, j: QualiRow): Pair<Long, Long>? {
    listOf(QualiRow::q3Millis, QualiRow::q2Millis, QualiRow::q1Millis).forEach { seg ->
        val a = seg(i); val b = seg(j)
        if (a != null && a > 0 && b != null && b > 0) return a to b
    }
    val a = i.timeMillis; val b = j.timeMillis
    return if (a != null && a > 0 && b != null && b > 0) a to b else null
}

/** Teammate-normalized one-lap rating per driver across a set of qualifying rows. */
fun computeDriverVsCar(rows: List<QualiRow>): List<DriverCarRating> {
    val gaps = HashMap<String, MutableList<Double>>()   // driverId -> list of (-gap%) vs teammate
    val wins = HashMap<String, Int>()
    val seen = HashSet<String>()
    rows.forEach { seen += it.driverId }

    rows.groupBy { it.raceId to it.constructorId }.forEach { (_, carRows) ->
        // pair every driver with every same-car teammate that race (usually exactly one)
        for (a in carRows) for (b in carRows) {
            if (a.driverId == b.driverId) continue
            val seg = lastCommonSegment(a, b) ?: continue
            val gap = symmetricGapPct(seg.first, seg.second)   // <0 means a faster
            gaps.getOrPut(a.driverId) { mutableListOf() }.add(-gap) // store so higher = faster
            if (gap < 0) wins[a.driverId] = (wins[a.driverId] ?: 0) + 1
        }
    }
    return seen.map { id ->
        val g = gaps[id].orEmpty()
        DriverCarRating(
            driverId = id,
            events = g.size,
            headToHeadWins = wins[id] ?: 0,
            oneLapRatingPct = median(g),
        )
    }
}

private fun median(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}
