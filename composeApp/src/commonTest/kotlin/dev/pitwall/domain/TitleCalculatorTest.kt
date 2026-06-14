package dev.pitwall.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TitleCalculatorTest {

    // ---- pointsSystemFor: era points tables --------------------------------

    @Test fun pointsSystem_2026_isCurrentEra_noFastestLapPoint() {
        val s = pointsSystemFor(2026)
        assertEquals(25, s.gpMax)
        assertEquals(8, s.sprintMax)
        assertEquals(0, s.fastestLapPoint)   // FL point removed for 2025+
    }

    @Test fun pointsSystem_2025_dropsFastestLapPoint() {
        assertEquals(0, pointsSystemFor(2025).fastestLapPoint)
        assertEquals(8, pointsSystemFor(2025).sprintMax)
    }

    @Test fun pointsSystem_2024_hasFastestLapPoint_sprint8() {
        val s = pointsSystemFor(2024)
        assertEquals(25, s.gpMax)
        assertEquals(8, s.sprintMax)
        assertEquals(1, s.fastestLapPoint)
    }

    @Test fun pointsSystem_2022_sprintMax8_fl1() {
        assertEquals(8, pointsSystemFor(2022).sprintMax)
        assertEquals(1, pointsSystemFor(2022).fastestLapPoint)
    }

    @Test fun pointsSystem_2021_sprintMax3_fl1() {
        val s = pointsSystemFor(2021)
        assertEquals(25, s.gpMax)
        assertEquals(3, s.sprintMax)        // first sprint era awarded only 3/2/1
        assertEquals(1, s.fastestLapPoint)
    }

    @Test fun pointsSystem_2019_hasFastestLapPoint_noSprint() {
        val s = pointsSystemFor(2019)
        assertEquals(0, s.sprintMax)        // no sprints before 2021
        assertEquals(1, s.fastestLapPoint)  // FL point introduced 2019
    }

    @Test fun pointsSystem_2018_noFastestLapPoint_noSprint() {
        val s = pointsSystemFor(2018)
        assertEquals(25, s.gpMax)
        assertEquals(0, s.sprintMax)
        assertEquals(0, s.fastestLapPoint)  // FL point not yet introduced (2010-2018)
    }

    @Test fun pointsSystem_2010_currentScale_noFlNoSprint() {
        val s = pointsSystemFor(2010)
        assertEquals(25, s.gpMax)
        assertEquals(0, s.sprintMax)
        assertEquals(0, s.fastestLapPoint)
    }

    // ---- maxRemaining: the headline 2026 numbers ---------------------------

    @Test fun maxRemaining_driver_2026_16gp_3sprint_is424() {
        val sys = pointsSystemFor(2026)
        // driver per-GP 25 (+0 FL) = 25; per-sprint 8. 16*25 + 3*8 = 424.
        assertEquals(424, maxRemaining(16, 3, sys, isConstructor = false))
    }

    @Test fun maxRemaining_constructor_2026_16gp_3sprint_is733() {
        val sys = pointsSystemFor(2026)
        // constructor per-GP 25+18=43; per-sprint 8+7=15. 16*43 + 3*15 = 733.
        assertEquals(733, maxRemaining(16, 3, sys, isConstructor = true))
    }

    @Test fun maxRemaining_driver_2024_addsFastestLapPoint() {
        val sys = pointsSystemFor(2024)
        // 2024 driver per-GP 25+1=26, per-sprint 8. 16*26 + 3*8 = 440.
        assertEquals(16 * 26 + 3 * 8, maxRemaining(16, 3, sys, isConstructor = false))
    }

    @Test fun maxRemaining_driver_2021_sprintMax3() {
        val sys = pointsSystemFor(2021)
        // 2021 driver per-GP 25+1=26, per-sprint 3. e.g. 10 GP + 2 sprints = 266.
        assertEquals(10 * 26 + 2 * 3, maxRemaining(10, 2, sys, isConstructor = false))
    }

    @Test fun maxRemaining_constructor_2021_sprintMax6() {
        val sys = pointsSystemFor(2021)
        // 2021 constructor per-GP 26+18=44 (FL goes to one of the two cars), per-sprint 3+2=5.
        assertEquals(10 * 44 + 2 * 5, maxRemaining(10, 2, sys, isConstructor = true))
    }

    @Test fun maxRemaining_noSprintEra_ignoresSprintArg() {
        val sys = pointsSystemFor(2015) // gpMax 25, sprintMax 0, FL 0
        // even if a stray sprint count is passed, sprintMax 0 contributes nothing.
        assertEquals(16 * 25, maxRemaining(16, 3, sys, isConstructor = false))
    }

    @Test fun maxRemaining_zeroRemaining_isZero() {
        assertEquals(0, maxRemaining(0, 0, pointsSystemFor(2026), isConstructor = false))
    }

    // ---- titleAliveSimple: alive iff can reach the current leader ----------

    @Test fun aliveSimple_2026_monacoPicture() {
        // Antonelli 156 leader; max +424 remaining. Everyone within 424 of 156 is alive.
        val pts = mapOf(
            "antonelli" to 156.0, "hamilton" to 90.0, "russell" to 88.0,
            "leclerc" to 75.0, "piastri" to 58.0, "norris" to 58.0,
        )
        val status = titleAliveSimple(pts, remaining = 424)
        // leader trivially alive; all rivals are within 424 of 156 -> all alive at this stage.
        assertTrue(status.values.all { it == TitleStatus.ALIVE })
    }

    @Test fun aliveSimple_eliminatesWhenCannotReachLeader() {
        // leader 200, trailer 100, only 50 left -> 100+50 = 150 < 200 -> ELIMINATED.
        val pts = mapOf("lead" to 200.0, "trail" to 100.0)
        val status = titleAliveSimple(pts, remaining = 50)
        assertEquals(TitleStatus.ALIVE, status["lead"])
        assertEquals(TitleStatus.ELIMINATED, status["trail"])
    }

    @Test fun aliveSimple_exactTieCountsAsAlive() {
        // 100 + 100 == 200 -> reachable (>=), alive.
        val pts = mapOf("lead" to 200.0, "trail" to 100.0)
        assertEquals(TitleStatus.ALIVE, titleAliveSimple(pts, remaining = 100)["trail"])
    }

    // ---- titleAliveStrict: alive iff can beat best OTHER rival -------------

    @Test fun aliveStrict_leaderEliminatedIfRivalCanOvertakeAndLeaderCannotKeepUp() {
        // Compare against best rival != self. The leader 156 vs best rival 90:
        // leader 156 + 0 future-but-self-doesn't-need... strict uses self's own ceiling vs rival's current.
        // trailer 88 needs to beat best rival (156): 88+424=512 >= 156 -> alive.
        val pts = mapOf("a" to 156.0, "b" to 90.0, "c" to 88.0)
        val status = titleAliveStrict(pts, remaining = 424)
        assertTrue(status.values.all { it == TitleStatus.ALIVE })
    }

    @Test fun aliveStrict_differsFromSimpleForTheLeader() {
        // Simple: leader is compared to max(all incl self) -> always alive.
        // Strict: leader compared to best rival. lead 200, rival 100, remaining 50.
        //   leader 200+50=250 >= 100 -> alive; rival 100+50=150 >= 200? no -> eliminated.
        val pts = mapOf("lead" to 200.0, "rivalA" to 100.0, "rivalB" to 90.0)
        val simple = titleAliveSimple(pts, 50)
        val strict = titleAliveStrict(pts, 50)
        assertEquals(TitleStatus.ALIVE, strict["lead"])
        assertEquals(TitleStatus.ELIMINATED, strict["rivalA"])
        // both agree the trailers are out
        assertEquals(simple["rivalA"], strict["rivalA"])
    }

    @Test fun aliveStrict_twoCloseLeadersBothAlive() {
        // lead 200, rival 190, remaining 50: rival 190+50=240>=200 alive; lead 200+50=250>=190 alive.
        val pts = mapOf("lead" to 200.0, "rival" to 190.0)
        val status = titleAliveStrict(pts, 50)
        assertTrue(status.values.all { it == TitleStatus.ALIVE })
    }

    @Test fun aliveStrict_singleEntrantIsAlive() {
        // No rivals -> nobody can be caught -> alive.
        assertEquals(TitleStatus.ALIVE, titleAliveStrict(mapOf("solo" to 10.0), 100)["solo"])
    }

    // ---- clinchScenario: leader mathematically champion -------------------

    @Test fun clinch_whenLeadExceedsRemaining() {
        // lead 200, P2 100 -> lead margin 100 > remaining 50 -> clinched.
        // Keys are DB slugs; the message must interpolate the DISPLAY NAME, not the slug.
        val pts = mapOf("andrea-kimi-antonelli" to 200.0, "lewis-hamilton" to 100.0)
        val names = mapOf("andrea-kimi-antonelli" to "Andrea Kimi Antonelli", "lewis-hamilton" to "Lewis Hamilton")
        val msg = clinchScenario(pts, remaining = 50, nameById = names)
        assertNotNull(msg)
        assertTrue(msg.contains("clinch", ignoreCase = true))
        // The leader's display name appears; the raw slug does NOT.
        assertTrue(msg.contains("Andrea Kimi Antonelli"))
        assertFalse(msg.contains("andrea-kimi-antonelli"))
    }

    @Test fun clinch_fallsBackToIdWhenNameMissing() {
        // No display name supplied for the leader -> fall back to the id rather than crash.
        val msg = clinchScenario(mapOf("lead" to 200.0, "p2" to 100.0), remaining = 50, nameById = emptyMap())
        assertNotNull(msg)
        assertTrue(msg.contains("lead"))
    }

    @Test fun clinch_notYetWhenLeadEqualsRemaining() {
        // margin 50 == remaining 50 -> NOT strictly greater -> not clinched (P2 could still tie).
        val names = mapOf("lead" to "Leader", "p2" to "Runner Up")
        assertNull(clinchScenario(mapOf("lead" to 150.0, "p2" to 100.0), remaining = 50, nameById = names))
    }

    @Test fun clinch_notWhenTitleStillOpen_2026Monaco() {
        // Antonelli leads Hamilton by 66 with +424 available -> wide open, no clinch.
        val pts = mapOf("antonelli" to 156.0, "hamilton" to 90.0)
        val names = mapOf("antonelli" to "Andrea Kimi Antonelli", "hamilton" to "Lewis Hamilton")
        assertNull(clinchScenario(pts, remaining = 424, nameById = names))
    }

    @Test fun clinch_handlesEmptyOrSingle() {
        assertNull(clinchScenario(emptyMap(), 100, nameById = emptyMap()))
        // single entrant with rounds left: no P2 to compare; treat as not-yet-clinched (season not over).
        assertNull(clinchScenario(mapOf("solo" to 10.0), 100, nameById = mapOf("solo" to "Solo")))
    }
}
