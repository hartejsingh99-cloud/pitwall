package dev.pitwall

import dev.pitwall.data.F1Repository
import dev.pitwall.data.makeF1dbDriver
import dev.pitwall.db.F1db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end runtime check of the offline data path: the real bundled f1db SQLite is opened through
 * the actual desktop SQLDelight driver, queried, and ranked by the Driver-vs-Car engine. Proves the
 * whole stack (driver -> generated queries -> mapping -> engine) on real data, not just that it compiles.
 *
 * Note: we assert the methodology's STRUCTURAL truths, not a "Verstappen is #1" UX ordering. The spec's
 * last-common-segment + median deliberately compares teammates on their deepest SHARED qualifying segment,
 * which compresses gaps — a defensible but non-obvious ranking (e.g. Bottas can top 2024). That's a product
 * decision, not a stack-correctness property.
 */
class IntegrationTest {
    private fun bundledDbPath(): String {
        val candidates = listOf(
            "src/commonMain/composeResources/files/f1db.db",
            "composeApp/src/commonMain/composeResources/files/f1db.db",
        )
        val found = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("bundled f1db.db not found (cwd=${System.getProperty("user.dir")})")
        return found.absolutePath
    }

    private fun repo() = F1Repository(F1db(makeF1dbDriver(bundledDbPath())))

    @Test fun ranks2024_stackProducesSortedMirroredGaps() {
        val rows = repo().ratingsForSeason(2024L)
        assertTrue(rows.isNotEmpty(), "expected non-empty 2024 ratings")

        // The repository returns rows sorted best-first.
        val gaps = rows.map { it.first.oneLapRatingPct }
        assertEquals(gaps.sortedDescending(), gaps, "rows must be sorted by rating descending")

        fun gapOf(name: String) = rows.first { it.second.contains(name) }.first.oneLapRatingPct
        val ver = gapOf("Verstappen")
        val per = gapOf("Pérez")

        // Verstappen out-qualified his sole 2024 teammate Pérez, so his gap is positive and Pérez's negative.
        assertTrue(ver > 0, "Verstappen should be faster than his teammate, was $ver")
        assertTrue(per < 0, "Pérez should be slower than his teammate, was $per")

        // Season-long exclusive teammates produce per-race gaps that are exact negatives, so the medians
        // mirror exactly. This proves the pairing + symmetric-gap + median path end-to-end on real data.
        assertEquals(0.0, ver + per, 0.0001, "exclusive teammates' median gaps must mirror")
    }

    @Test fun seasonsSpanHistory() {
        val seasons = repo().seasons()
        assertTrue(seasons.contains(2024), "expected 2024 in seasons")
        assertTrue(seasons.size > 50, "f1db should expose 70+ seasons, got ${seasons.size}")
    }
}
