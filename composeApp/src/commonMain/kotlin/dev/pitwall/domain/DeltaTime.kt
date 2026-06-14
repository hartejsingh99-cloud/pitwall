package dev.pitwall.domain

/**
 * Delta-time of [comparison] relative to [reference] at each shared sample (comparison − reference).
 * Both traces must be on the same distance grid (the bake decimates by distance, so two laps from the
 * same session share it). Positive = comparison is behind/slower. Spec §5 "delta-time trace".
 */
fun deltaTime(reference: LapTrace, comparison: LapTrace): List<Double> {
    require(reference.distance.size == comparison.distance.size) { "traces must share a distance grid" }
    return reference.cumulativeTimeSec.indices.map { i ->
        comparison.cumulativeTimeSec[i] - reference.cumulativeTimeSec[i]
    }
}

/**
 * Derive cumulative time (seconds) along a lap from the distance axis and a speed channel (km/h).
 * The bake stores distance + speed but not per-sample time, so we integrate: over each segment,
 * dt ≈ Δdistance / average_speed. Converts km/h → m/s via the 3.6 factor. A zero-speed segment
 * contributes no time (avoids division by zero on standing-start samples).
 */
fun cumulativeTimeFromSpeed(distance: List<Double>, speedKmh: List<Double>): List<Double> {
    require(distance.size == speedKmh.size) { "distance and speed must be the same length" }
    val out = DoubleArray(distance.size)
    for (i in 1 until distance.size) {
        val dd = distance[i] - distance[i - 1]
        val avg = (speedKmh[i] + speedKmh[i - 1]) / 2.0
        out[i] = out[i - 1] + if (avg > 0.0) dd * 3.6 / avg else 0.0
    }
    return out.toList()
}

/**
 * Linearly interpolate a series [srcValues] defined on the ascending axis [srcDist] onto [targetDist].
 * Targets outside the source range clamp to the endpoints. This lets delta-time compare two laps whose
 * decimated distance grids differ slightly (real telemetry rarely lands on identical samples) by first
 * projecting the comparison lap's cumulative-time trace onto the reference lap's grid.
 */
fun resampleByDistance(
    srcDist: List<Double>,
    srcValues: List<Double>,
    targetDist: List<Double>,
): List<Double> {
    require(srcDist.size == srcValues.size) { "srcDist and srcValues must be the same length" }
    require(srcDist.isNotEmpty()) { "empty source" }
    if (srcDist.size == 1) return targetDist.map { srcValues[0] }
    return targetDist.map { d ->
        when {
            d <= srcDist.first() -> srcValues.first()
            d >= srcDist.last() -> srcValues.last()
            else -> {
                val i = srcDist.indexOfLast { it <= d }
                val x0 = srcDist[i]; val x1 = srcDist[i + 1]
                val y0 = srcValues[i]; val y1 = srcValues[i + 1]
                if (x1 == x0) y0 else y0 + (y1 - y0) * (d - x0) / (x1 - x0)
            }
        }
    }
}

/**
 * Normalize parallel (x, y) data into a [w]×[h] pixel box with [pad] inset, returning (pixelX, pixelY).
 * Y is FLIPPED so larger data-y draws upward (screen y grows downward). A degenerate (zero-range) axis
 * collapses to the box midline. Pure scaling math behind every Canvas chart (telemetry/track-dominance).
 */
fun scaleToCanvas(
    xs: List<Double>,
    ys: List<Double>,
    w: Double,
    h: Double,
    pad: Double = 0.0,
): Pair<List<Double>, List<Double>> {
    require(xs.size == ys.size) { "xs and ys must be the same length" }
    val innerW = w - 2 * pad
    val innerH = h - 2 * pad
    val minX = xs.minOrNull() ?: 0.0; val maxX = xs.maxOrNull() ?: 0.0
    val minY = ys.minOrNull() ?: 0.0; val maxY = ys.maxOrNull() ?: 0.0
    val rangeX = maxX - minX; val rangeY = maxY - minY

    val px = xs.map { if (rangeX == 0.0) w / 2.0 else pad + (it - minX) / rangeX * innerW }
    val py = ys.map {
        if (rangeY == 0.0) h / 2.0
        else pad + (maxY - it) / rangeY * innerH   // flip: max data-y -> top (pixel = pad)
    }
    return px to py
}
