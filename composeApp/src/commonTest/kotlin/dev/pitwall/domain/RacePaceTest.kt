package dev.pitwall.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RacePaceTest {
    private fun rating(id: String, pct: Double) =
        DriverCarRating(driverId = id, events = 10, headToHeadWins = 5, oneLapRatingPct = pct) to "$id name"

    private val ranked = listOf(
        rating("a", 0.30),   // already sorted best-first (the hero's qualifying order)
        rating("b", 0.10),
        rating("c", -0.20),
    )

    @Test fun mergeRacePace_attachesPaceWhereAvailable() {
        val rows = mergeRacePace(ranked, mapOf("a" to -0.50, "c" to 0.40))
        assertEquals(3, rows.size)
        assertEquals(-0.50, rows[0].racePacePct)
        assertNull(rows[1].racePacePct)          // 'b' had no pace entry -> null, not an error
        assertEquals(0.40, rows[2].racePacePct)
    }

    @Test fun mergeRacePace_preservesQualifyingOrder() {
        // pace must NOT reorder: it's a companion column, the sort key stays the one-lap rating.
        val rows = mergeRacePace(ranked, mapOf("c" to -9.0, "a" to 9.0))
        assertEquals(listOf("a", "b", "c"), rows.map { it.rating.driverId })
    }

    @Test fun mergeRacePace_emptyMap_allNull() {
        val rows = mergeRacePace(ranked, emptyMap())
        assertEquals(3, rows.size)
        rows.forEach { assertNull(it.racePacePct) }
    }
}
