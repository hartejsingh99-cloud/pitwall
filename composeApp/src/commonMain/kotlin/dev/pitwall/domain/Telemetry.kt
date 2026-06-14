package dev.pitwall.domain

/**
 * Parallel-array telemetry for one driver-lap — the on-device counterpart to the bake's
 * `telemetry_channel` row. Each channel is decoded from a comma-delimited string by [parseChannel];
 * a null channel means the bake didn't have it for this lap. [validated] is the corrupt-blob guard.
 */
data class ChannelSet(
    val distance: List<Double>,
    val speed: List<Double>? = null,
    val throttle: List<Double>? = null,
    val brake: List<Double>? = null,
    val gear: List<Int>? = null,
    val drs: List<Int>? = null,
    val x: List<Double>? = null,
    val y: List<Double>? = null,
) {
    fun validated(): ChannelSet {
        val n = distance.size
        require(n > 0) { "empty distance axis" }
        listOf(speed, throttle, brake).forEach {
            require(it == null || it.size == n) { "channel length != distance ($n)" }
        }
        require(gear == null || gear.size == n) { "gear length != distance" }
        require(drs == null || drs.size == n) { "drs length != distance" }
        // x and y are an inseparable pair (track-position overlay needs both or neither)
        require((x == null) == (y == null)) { "x and y must both be present or both absent" }
        require(x == null || x.size == n) { "x length != distance" }
        require(y == null || y.size == n) { "y length != distance" }
        return this
    }
}

/** Cumulative time (seconds) vs distance for one lap, on a shared distance grid. */
data class LapTrace(val distance: List<Double>, val cumulativeTimeSec: List<Double>)

/**
 * Decode a packed channel ("0,10.5,21.25") into doubles. `null` -> null (absent channel);
 * "" -> empty list; tolerant of surrounding whitespace. Mirrors the bake's pack_channels().
 */
fun parseChannel(s: String?): List<Double>? {
    if (s == null) return null
    if (s.isEmpty()) return emptyList()
    return s.split(',').map { it.trim().toDouble() }
}

/** Convenience: parse a channel known to be integer-valued (gear, drs). */
fun parseIntChannel(s: String?): List<Int>? = parseChannel(s)?.map { it.toInt() }
