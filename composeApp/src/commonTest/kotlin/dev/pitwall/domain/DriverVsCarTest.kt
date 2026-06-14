package dev.pitwall.domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriverVsCarTest {
    // helper: a qualifying row
    private fun q(race: Int, drv: String, con: String, q1: Long? = null, q2: Long? = null, q3: Long? = null, t: Long? = null) =
        QualiRow(race, drv, con, t, q1, q2, q3)

    @Test fun symmetricGap_isOrderInvariantAndPercent() {
        // 90.000s vs 90.900s -> ~+0.995% for the slower driver
        val g = symmetricGapPct(90_000, 90_900)
        assertEquals(-0.995, g, 0.01)            // driver i is FASTER -> negative
        assertEquals(0.995, symmetricGapPct(90_900, 90_000), 0.01) // reversed flips sign
    }

    @Test fun lastCommonSegment_prefersQ3thenQ2thenQ1() {
        // both have Q2; only i has Q3 -> compare on Q2
        val pair = lastCommonSegment(
            i = q(1, "i", "x", q1 = 80_000, q2 = 79_000, q3 = 78_000),
            j = q(1, "j", "x", q1 = 80_500, q2 = 79_400)
        )
        assertEquals(79_000L to 79_400L, pair)
    }

    @Test fun lastCommonSegment_fallsBackToCombinedTime() {
        val pair = lastCommonSegment(q(1, "i", "x", t = 91_000), q(1, "j", "x", t = 91_500))
        assertEquals(91_000L to 91_500L, pair)
    }

    @Test fun rating_usesMedianAndCountsHeadToHead() {
        // i beats teammate j in races 1,2 by ~0.5%, loses race 3 by 1% -> median favors i, H2H 2-1
        val rows = listOf(
            q(1, "i", "x", t = 90_000), q(1, "j", "x", t = 90_450),
            q(2, "i", "x", t = 88_000), q(2, "j", "x", t = 88_440),
            q(3, "i", "x", t = 92_000), q(3, "j", "x", t = 91_080),
        )
        val ratings = computeDriverVsCar(rows)
        val i = ratings.first { it.driverId == "i" }
        assertEquals(3, i.events)
        assertEquals(2, i.headToHeadWins)            // won 2 of 3
        assertTrue(i.oneLapRatingPct > 0)            // median gap favors i (faster)
    }

    @Test fun ignoresEventsWithNoTeammate() {
        val rows = listOf(q(1, "solo", "z", t = 90_000)) // only one car entry that race
        assertTrue(computeDriverVsCar(rows).all { it.events == 0 })
    }
}
