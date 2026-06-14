package dev.pitwall.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadToHeadTest {
    private fun ql(race: Int, year: Int, drv: String, con: String, best: Long?) =
        H2HQualiRow(race, year, drv, con, best)

    private fun rr(race: Int, year: Int, drv: String, con: String, pos: Long?) =
        H2HRaceRow(race, year, drv, con, pos)

    // --- Career counts: qualifying wins counted only where both set a best, race wins
    //     only where both classified. ---
    @Test fun careerCountsQualifyingAndRaceWins() {
        val quali = listOf(
            ql(1, 2014, "a", "merc", 90_000), ql(1, 2014, "b", "merc", 90_400), // a faster
            ql(2, 2014, "a", "merc", 88_500), ql(2, 2014, "b", "merc", 88_100), // b faster
            ql(3, 2014, "a", "merc", 92_000), ql(3, 2014, "b", "merc", null),   // skip (b null)
        )
        val races = listOf(
            rr(1, 2014, "a", "merc", 1), rr(1, 2014, "b", "merc", 2),  // a ahead
            rr(2, 2014, "a", "merc", 5), rr(2, 2014, "b", "merc", 3),  // b ahead
            rr(3, 2014, "a", "merc", 2), rr(3, 2014, "b", "merc", null), // skip (b DNF)
        )
        val res = computeHeadToHead(quali, races, "a", "b")
        assertEquals(2, res.career.commonQualiSessions)  // race 3 skipped (b had no time)
        assertEquals(1, res.career.qualiWinsA)
        assertEquals(1, res.career.qualiWinsB)
        assertEquals(2, res.career.commonRaces)          // race 3 skipped (b not classified)
        assertEquals(1, res.career.raceWinsA)
        assertEquals(1, res.career.raceWinsB)
    }

    // --- Teammate-era grouping: two stints across two years, correct aAhead and mirrored median. ---
    @Test fun teammateGroupingIntoTwoStintsWithMirroredMedian() {
        // 2014: a faster both races -> aAhead 2, median(A) > 0, median(B) = -median(A)
        // 2015: a faster one, b faster one -> aAhead 1
        val quali = listOf(
            ql(1, 2014, "a", "merc", 90_000), ql(1, 2014, "b", "merc", 90_450),
            ql(2, 2014, "a", "merc", 88_000), ql(2, 2014, "b", "merc", 88_440),
            ql(3, 2015, "a", "merc", 91_000), ql(3, 2015, "b", "merc", 91_300), // a faster
            ql(4, 2015, "a", "merc", 89_500), ql(4, 2015, "b", "merc", 89_100), // b faster
        )
        val res = computeHeadToHead(quali, emptyList(), "a", "b")
        assertTrue(res.directGapComputable)
        assertEquals(2, res.teammateStints.size)

        val s2014 = res.teammateStints.first { it.year == 2014 }
        val s2015 = res.teammateStints.first { it.year == 2015 }
        assertEquals("merc", s2014.constructorId)
        // The pure engine has no DB, so constructorName defaults to the slug until the repo maps it.
        assertEquals("merc", s2014.constructorName)
        assertEquals(2, s2014.sessions)
        assertEquals(2, s2014.aAhead)
        assertTrue(s2014.medianGapPctA > 0)              // a faster -> positive for a

        assertEquals(2, s2015.sessions)
        assertEquals(1, s2015.aAhead)

        // Mirror check: B's stint median is the negation of A's for the same group.
        val resB = computeHeadToHead(quali, emptyList(), "b", "a")
        val s2014b = resB.teammateStints.first { it.year == 2014 }
        assertEquals(s2014.medianGapPctA, -s2014b.medianGapPctA, 1e-9)
    }

    // --- Constructor display names: repo-side mapping replaces slug, falls back to slug if absent. ---
    @Test fun withConstructorNamesMapsSlugToDisplayNameWithFallback() {
        val quali = listOf(
            ql(1, 2014, "a", "merc", 90_000), ql(1, 2014, "b", "merc", 90_450),
            ql(3, 2015, "a", "unknown_slug", 91_000), ql(3, 2015, "b", "unknown_slug", 91_300),
        )
        val res = computeHeadToHead(quali, emptyList(), "a", "b")
        val named = res.withConstructorNames(mapOf("merc" to "Mercedes"))

        val s2014 = named.teammateStints.first { it.year == 2014 }
        assertEquals("merc", s2014.constructorId)         // key/slug unchanged
        assertEquals("Mercedes", s2014.constructorName)   // display name applied

        val s2015 = named.teammateStints.first { it.year == 2015 }
        assertEquals("unknown_slug", s2015.constructorName) // no mapping -> falls back to slug
    }

    // --- Never teammates: empty stints, directGapComputable=false, but career totals still computed. ---
    @Test fun neverTeammatesYieldsNoStintsAndNoGap() {
        // Senna (1980s McLaren) vs Hamilton (2010s Mercedes) — common race ids, different cars.
        val quali = listOf(
            ql(1, 1988, "senna", "mclaren", 78_000), ql(1, 1988, "ham", "mercedes", 78_500),
            ql(2, 1989, "senna", "mclaren", 80_000), ql(2, 1989, "ham", "mercedes", 79_900),
        )
        val races = listOf(
            rr(1, 1988, "senna", "mclaren", 1), rr(1, 1988, "ham", "mercedes", 2),   // senna ahead
            rr(2, 1989, "senna", "mclaren", 4), rr(2, 1989, "ham", "mercedes", 3),   // ham ahead
        )
        val res = computeHeadToHead(quali, races, "senna", "ham")
        assertTrue(res.teammateStints.isEmpty())
        assertTrue(!res.directGapComputable)
        // Career qualifying counts still tally even cross-era (never same car).
        assertEquals(2, res.career.commonQualiSessions)
        assertEquals(1, res.career.qualiWinsA)           // senna faster race 1
        assertEquals(1, res.career.qualiWinsB)           // ham faster race 2
        // Career RACE counts also tally cross-era — both classified in both races.
        assertEquals(2, res.career.commonRaces)
        assertEquals(1, res.career.raceWinsA)            // senna ahead race 1
        assertEquals(1, res.career.raceWinsB)            // ham ahead race 2
    }
}
