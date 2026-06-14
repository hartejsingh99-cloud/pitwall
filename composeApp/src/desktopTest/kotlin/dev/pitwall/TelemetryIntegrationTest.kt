package dev.pitwall

import dev.pitwall.data.TelemetryRepository
import dev.pitwall.data.makeTelemetryDriver
import dev.pitwall.telemetrydb.TelemetryDb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the telemetry data path: the bundled telemetry.db is opened through the real
 * desktop SQLDelight driver, queried, and decoded into ChannelSets + delta-time. Runs against whatever
 * telemetry.db is bundled (the synthetic seed from tools/bake/make_sample_db.py in dev). Proves the
 * whole tier (2nd DB driver -> generated queries -> channel decode -> delta engine) on a real file.
 */
class TelemetryIntegrationTest {
    private fun bundledDbPath(): String {
        val candidates = listOf(
            "src/commonMain/composeResources/files/telemetry.db",
            "composeApp/src/commonMain/composeResources/files/telemetry.db",
        )
        return candidates.map(::File).firstOrNull { it.exists() }?.absolutePath
            ?: error("bundled telemetry.db not found (cwd=${System.getProperty("user.dir")}); " +
                "run: python3 tools/bake/make_sample_db.py --out composeApp/src/commonMain/composeResources/files/telemetry.db")
    }

    private fun repo() = TelemetryRepository(TelemetryDb(makeTelemetryDriver(bundledDbPath())))

    @Test fun sessionsAndChannelsLoad() {
        val r = repo()
        val sessions = r.sessions()
        assertTrue(sessions.isNotEmpty(), "expected baked sessions")
        val race = sessions.first { it.type == "R" }
        val drivers = r.drivers(race.id)
        assertTrue(drivers.size >= 2, "race should have multiple drivers")
        val laps = r.laps(race.id)
        assertTrue(laps.isNotEmpty(), "race should have laps")

        val firstDriver = drivers.first()
        val firstLap = laps.first { it.driverId == firstDriver.id }.lap
        val ch = r.channel(race.id, firstDriver.id, firstLap)
        assertNotNull(ch, "channel should decode")
        assertTrue(ch.distance.isNotEmpty(), "distance axis present")
        assertNotNull(ch.speed)
        assertEquals(ch.distance.size, ch.speed!!.size, "speed aligns to distance (validated)")
    }

    @Test fun deltaBetweenDriversStartsAtZero() {
        val r = repo()
        val race = r.sessions().first { it.type == "R" }
        val ds = r.drivers(race.id)
        val d = r.deltaBetween(race.id, ds[0].id, 1, ds[1].id, 1)
        assertNotNull(d, "delta should compute for two drivers with speed channels")
        assertTrue(d.isNotEmpty())
        assertEquals(0.0, d.first(), 1e-6, "delta starts at zero")
    }

    @Test fun heroRacePaceMirrorsWithinTeam() {
        val r = repo()
        val pace = r.heroRacePace(2024)
        assertTrue(pace.isNotEmpty(), "2024 should have race-pace rows")
        // Verstappen & Pérez are teammates in the sample, so their symmetric % mirror (sum ~ 0).
        val ver = pace["max-verstappen"]
        val per = pace["sergio-perez"]
        assertNotNull(ver); assertNotNull(per)
        assertEquals(0.0, ver + per, 0.001, "teammates' symmetric pace % must mirror")
    }
}
