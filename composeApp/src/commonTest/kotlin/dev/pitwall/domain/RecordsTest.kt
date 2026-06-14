package dev.pitwall.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordsTest {

    // ---- value formatting: counts as ints, points up to 1 decimal -------------
    @Test fun formatsCountMetricsAsPlainIntegers() {
        assertEquals("105", formatRecordValue(RecordMetric.WINS, 105.0))
        assertEquals("0", formatRecordValue(RecordMetric.PODIUMS, 0.0))
        // a count arriving as a whole Double must not gain a decimal
        assertEquals("68", formatRecordValue(RecordMetric.FASTEST_LAPS, 68.0))
    }

    @Test fun formatsPointsWithUpToOneDecimal() {
        assertEquals("4863.5", formatRecordValue(RecordMetric.POINTS, 4863.5))
        // a whole number of points drops the trailing ".0"
        assertEquals("1000", formatRecordValue(RecordMetric.POINTS, 1000.0))
        // rounds to a single decimal place
        assertEquals("12.3", formatRecordValue(RecordMetric.POINTS, 12.34))
        assertEquals("12.4", formatRecordValue(RecordMetric.POINTS, 12.35))
    }

    @Test fun pointsFormatterIsMultiplatformSafeForLargeValues() {
        // large totals must not switch to scientific notation or lose the integer part
        assertEquals("15273.1", formatRecordValue(RecordMetric.POINTS, 15273.1))
    }

    // ---- query selection: precomputed vs era-sliced ---------------------------
    @Test fun noEraFilterPicksPrecomputedSource() {
        assertEquals(
            RecordSource.PRECOMPUTED,
            recordSource(RecordMetric.WINS, RecordScope.DRIVERS, era = null),
        )
        assertEquals(
            RecordSource.PRECOMPUTED,
            recordSource(RecordMetric.POINTS, RecordScope.CONSTRUCTORS, era = null),
        )
    }

    @Test fun eraFilterForcesComputedSourceForSupportedDriverMetrics() {
        val era = EraFilter(2010, 2013)
        assertEquals(RecordSource.COMPUTED, recordSource(RecordMetric.WINS, RecordScope.DRIVERS, era))
        assertEquals(RecordSource.COMPUTED, recordSource(RecordMetric.POLES, RecordScope.DRIVERS, era))
        assertEquals(RecordSource.COMPUTED, recordSource(RecordMetric.PODIUMS, RecordScope.DRIVERS, era))
        assertEquals(RecordSource.COMPUTED, recordSource(RecordMetric.FASTEST_LAPS, RecordScope.DRIVERS, era))
    }

    @Test fun eraFilterFallsBackToPrecomputedWhenNotComputable() {
        val era = EraFilter(2010, 2013)
        // constructors have no era-sliced query in this lane -> stays precomputed
        assertEquals(RecordSource.PRECOMPUTED, recordSource(RecordMetric.WINS, RecordScope.CONSTRUCTORS, era))
        // driver metrics with no computed counterpart (points/starts/titles/grand slams) stay precomputed
        assertEquals(RecordSource.PRECOMPUTED, recordSource(RecordMetric.POINTS, RecordScope.DRIVERS, era))
        assertEquals(RecordSource.PRECOMPUTED, recordSource(RecordMetric.STARTS, RecordScope.DRIVERS, era))
        assertEquals(RecordSource.PRECOMPUTED, recordSource(RecordMetric.CHAMPIONSHIPS, RecordScope.DRIVERS, era))
        assertEquals(RecordSource.PRECOMPUTED, recordSource(RecordMetric.GRAND_SLAMS, RecordScope.DRIVERS, era))
    }

    // ---- metric availability for the constructors scope -----------------------
    @Test fun constructorsExposeOnlyTheirSupportedMetrics() {
        val cm = RecordMetric.forScope(RecordScope.CONSTRUCTORS)
        assertTrue(RecordMetric.WINS in cm)
        assertTrue(RecordMetric.ONE_TWOS in cm)
        // starts / grand slams / fastest laps are driver-only in this lane
        assertTrue(RecordMetric.STARTS !in cm)
        assertTrue(RecordMetric.GRAND_SLAMS !in cm)
    }

    @Test fun oneTwosIsDriversScopeInvalidAndDriversListExcludesIt() {
        val dm = RecordMetric.forScope(RecordScope.DRIVERS)
        assertTrue(RecordMetric.ONE_TWOS !in dm)
        assertTrue(RecordMetric.STARTS in dm)
        assertTrue(RecordMetric.GRAND_SLAMS in dm)
    }

    // ---- rank shaping: stable passthrough preserving query order --------------
    @Test fun rankPassthroughKeepsQueryOrderAndAssigns1BasedRanks() {
        val rows = listOf(
            RecordRow("Lewis Hamilton", "105"),
            RecordRow("Michael Schumacher", "91"),
            RecordRow("Max Verstappen", "71"),
        )
        val ranked = rankRecords(rows)
        assertEquals(listOf(1, 2, 3), ranked.map { it.rank })
        assertEquals("Lewis Hamilton", ranked.first().row.name)
        assertEquals("Max Verstappen", ranked.last().row.name)
    }

    @Test fun rankPassthroughHandlesEmptyList() {
        assertTrue(rankRecords(emptyList()).isEmpty())
    }
}
