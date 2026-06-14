package dev.pitwall.data

import dev.pitwall.domain.ChannelSet
import dev.pitwall.domain.LapTrace
import dev.pitwall.domain.cumulativeTimeFromSpeed
import dev.pitwall.domain.deltaTime
import dev.pitwall.domain.parseChannel
import dev.pitwall.domain.parseIntChannel
import dev.pitwall.domain.resampleByDistance
import dev.pitwall.telemetrydb.TelemetryDb

/** Display models for the telemetry UI (kept platform-free; no generated types leak out of this layer). */
data class TelemetrySession(
    val id: String, val year: Int, val round: Int, val type: String,
    val event: String, val circuit: String,
)

data class TelemetryDriver(val id: String, val label: String)

data class TelemetryLap(
    val driverId: String, val driverLabel: String, val lap: Int, val lapTimeMs: Long?,
    val compound: String?, val tyreLife: Long?, val stint: Long?, val isAccurate: Boolean,
)

data class TelemetryPace(
    val driverId: String, val driverLabel: String, val medianMs: Long?,
    val pctVsTeam: Double?, val degMsPerLap: Double?,
)

/**
 * Read tier over the bundled, read-only telemetry.db (second SQLDelight database). Decodes the bake's
 * comma-delimited channels into typed [ChannelSet]s and derives delta-time. All offline; no network.
 */
class TelemetryRepository(private val db: TelemetryDb) {
    private val q get() = db.telemetryQueries

    fun sessions(): List<TelemetrySession> = q.sessions().executeAsList().map {
        TelemetrySession(it.id, it.year.toInt(), it.round.toInt(), it.session_type, it.event_name, it.circuit_name)
    }

    fun drivers(sessionId: String): List<TelemetryDriver> =
        q.driversForSession(sessionId).executeAsList().map { TelemetryDriver(it.driver_id, it.driver_label) }

    fun laps(sessionId: String): List<TelemetryLap> = q.lapsForSession(sessionId).executeAsList().map {
        TelemetryLap(
            driverId = it.driver_id, driverLabel = it.driver_label, lap = it.lap_number.toInt(),
            lapTimeMs = it.lap_time_ms, compound = it.compound, tyreLife = it.tyre_life,
            stint = it.stint, isAccurate = it.is_accurate == 1L,
        )
    }

    /** Typed channels for one driver-lap, or null if the bake had no telemetry row for it. */
    fun channel(sessionId: String, driverId: String, lap: Int): ChannelSet? {
        val r = q.channelForLap(sessionId, driverId, lap.toLong()).executeAsOneOrNull() ?: return null
        val distance = parseChannel(r.distance) ?: return null
        return ChannelSet(
            distance = distance,
            speed = parseChannel(r.speed),
            throttle = parseChannel(r.throttle),
            brake = parseChannel(r.brake),
            gear = parseIntChannel(r.gear),
            drs = parseIntChannel(r.drs),
            x = parseChannel(r.x),
            y = parseChannel(r.y),
        ).validated()
    }

    /**
     * Delta-time of (cmpDriver,cmpLap) relative to (refDriver,refLap) on the reference's distance grid.
     * Both need a speed channel (delta is derived from speed since the bake stores no per-sample time).
     * The comparison trace is resampled onto the reference grid so slightly-misaligned decimations still
     * compare. Returns null if either lap or its speed channel is missing.
     */
    fun deltaBetween(
        sessionId: String,
        refDriver: String, refLap: Int,
        cmpDriver: String, cmpLap: Int,
    ): List<Double>? {
        val ref = channel(sessionId, refDriver, refLap) ?: return null
        val cmp = channel(sessionId, cmpDriver, cmpLap) ?: return null
        val refSpeed = ref.speed ?: return null
        val cmpSpeed = cmp.speed ?: return null
        val refTime = cumulativeTimeFromSpeed(ref.distance, refSpeed)
        val cmpTime = cumulativeTimeFromSpeed(cmp.distance, cmpSpeed)
        val cmpOnRef = resampleByDistance(cmp.distance, cmpTime, ref.distance)
        return deltaTime(LapTrace(ref.distance, refTime), LapTrace(ref.distance, cmpOnRef))
    }

    fun pace(sessionId: String): List<TelemetryPace> = q.paceForSession(sessionId).executeAsList().map {
        TelemetryPace(it.driver_id, it.driver_label, it.median_pace_ms, it.pace_pct_vs_team, it.deg_ms_per_lap)
    }

    /** Map driverId -> race-pace % vs teammate for [year]'s race sessions (feeds the hero's 2018+ companion). */
    fun heroRacePace(year: Int): Map<String, Double> =
        // the query's WHERE pace_pct_vs_team IS NOT NULL makes the column non-null in the generated row.
        q.heroRacePace(year.toLong()).executeAsList().associate { it.driver_id to it.pace_pct_vs_team }
}
