package dev.pitwall.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowseFormatTest {

    // ---- millisToLapTime --------------------------------------------------
    @Test fun lapTime_nullIsEmDash() {
        assertEquals("—", millisToLapTime(null))
    }

    @Test fun lapTime_subMinuteHasNoMinutesPart() {
        // 59.123s -> 59123ms -> "59.12" (centiseconds, rounded)
        assertEquals("59.12", millisToLapTime(59_123))
    }

    @Test fun lapTime_subMinuteRoundsCentiseconds() {
        // 9.876s -> 9876ms -> rounds to 9.88
        assertEquals("9.88", millisToLapTime(9_876))
    }

    @Test fun lapTime_minutePlusFormats() {
        // 1:18.518 -> 78518ms
        assertEquals("1:18.52", millisToLapTime(78_518))
    }

    @Test fun lapTime_padsSecondsAndCentisecondsAcrossMinute() {
        // 1:02.05 -> 62050ms -> "1:02.05" (both seconds and cs zero-padded)
        assertEquals("1:02.05", millisToLapTime(62_050))
    }

    @Test fun lapTime_zeroIsAllZeros() {
        assertEquals("0.00", millisToLapTime(0))
    }

    // ---- formatGap --------------------------------------------------------
    @Test fun formatGap_nullIsEmDash() {
        assertEquals("—", formatGap(null))
    }

    @Test fun formatGap_blankIsEmDash() {
        assertEquals("—", formatGap(""))
        assertEquals("—", formatGap("   "))
    }

    @Test fun formatGap_passesThroughTrimmed() {
        assertEquals("+2.974", formatGap("+2.974"))
        assertEquals("+1 lap", formatGap(" +1 lap "))
    }

    // ---- formatStandingPoints ---------------------------------------------
    @Test fun formatPoints_dropsWholeNumberTail() {
        assertEquals("25", formatStandingPoints(25.0))
        assertEquals("0", formatStandingPoints(0.0))
        assertEquals("423", formatStandingPoints(423.0))
    }

    @Test fun formatPoints_keepsRealFraction() {
        assertEquals("0.5", formatStandingPoints(0.5))
        assertEquals("12.5", formatStandingPoints(12.5))
        // 575.0 (a real constructors total) drops the tail.
        assertEquals("575", formatStandingPoints(575.0))
    }

    // ---- AvailableSessions tabs ------------------------------------------
    @Test fun availableSessions_canonicalOrderOnlyPresent() {
        val sprintWeekend = AvailableSessions(qualifying = true, sprintQualifying = true, sprint = true, race = true)
        assertEquals(
            listOf(SessionTab.QUALIFYING, SessionTab.SPRINT_QUALIFYING, SessionTab.SPRINT, SessionTab.RACE),
            sprintWeekend.tabs,
        )
        val normalWeekend = AvailableSessions(qualifying = true, sprintQualifying = false, sprint = false, race = true)
        assertEquals(listOf(SessionTab.QUALIFYING, SessionTab.RACE), normalWeekend.tabs)
        val upcoming = AvailableSessions(qualifying = false, sprintQualifying = false, sprint = false, race = false)
        assertTrue(upcoming.tabs.isEmpty())
    }

    // ---- RaceListItem derive fields --------------------------------------
    @Test fun raceListItem_isRunWhenResultsExist() {
        val run = RaceListItem(
            raceId = 1, round = 6, grandPrixName = "Monaco", circuitName = "Monaco",
            placeName = "Monte-Carlo", date = "2026-05-24", resultCount = 22, sprintQualifyingFormat = null,
        )
        assertTrue(run.isRun)
        assertFalse(run.isSprintWeekend)
    }

    @Test fun raceListItem_notRunWhenZeroResults() {
        val upcoming = RaceListItem(
            raceId = 2, round = 7, grandPrixName = "Spain", circuitName = "Barcelona",
            placeName = "Barcelona", date = "2026-06-14", resultCount = 0, sprintQualifyingFormat = null,
        )
        assertFalse(upcoming.isRun)
    }

    @Test fun raceListItem_isSprintWeekendWhenFormatPresent() {
        val sprint = RaceListItem(
            raceId = 3, round = 2, grandPrixName = "China", circuitName = "Shanghai",
            placeName = "Shanghai", date = "2026-03-15", resultCount = 22, sprintQualifyingFormat = "SPRINT_SHOOTOUT",
        )
        assertTrue(sprint.isSprintWeekend)
        assertTrue(sprint.isRun)
    }
}
