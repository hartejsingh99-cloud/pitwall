package dev.pitwall.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeltaTimeTest {
    @Test fun parseChannel_handlesNullEmptyAndValues() {
        assertEquals(null, parseChannel(null))
        assertEquals(emptyList(), parseChannel(""))
        assertEquals(listOf(0.0, 10.5, 21.25), parseChannel("0,10.5,21.25"))
        // tolerant of surrounding whitespace
        assertEquals(listOf(1.0, 2.0), parseChannel(" 1 , 2 "))
    }

    @Test fun validated_acceptsAlignedChannels() {
        val cs = ChannelSet(
            distance = listOf(0.0, 25.0, 50.0),
            speed = listOf(300.0, 120.0, 280.0),
            gear = listOf(8, 3, 7),
            x = listOf(0.0, 10.0, 20.0),
            y = listOf(0.0, 5.0, 0.0),
        ).validated()
        assertEquals(3, cs.distance.size)
    }

    @Test fun validated_rejectsMismatchedLengths() {
        assertFailsWith<IllegalArgumentException> {
            ChannelSet(distance = listOf(0.0, 1.0), speed = listOf(300.0)).validated()
        }
    }

    @Test fun validated_rejectsHalfPresentXY() {
        assertFailsWith<IllegalArgumentException> {
            ChannelSet(distance = listOf(0.0, 1.0), x = listOf(0.0, 1.0)).validated()  // y missing
        }
    }

    @Test fun deltaTime_zeroAtStart_growsWhenSlower() {
        val dist = listOf(0.0, 100.0, 200.0, 300.0)
        val tA = listOf(0.0, 2.0, 4.0, 6.0)        // reference
        val tB = listOf(0.0, 2.1, 4.3, 6.6)        // progressively slower
        val d = deltaTime(LapTrace(dist, tA), LapTrace(dist, tB))
        assertEquals(0.0, d.first(), 1e-9)
        assertTrue(d.last() > 0.0)
        assertEquals(0.6, d.last(), 1e-9)
    }

    @Test fun deltaTime_requiresSameGrid() {
        assertFailsWith<IllegalArgumentException> {
            deltaTime(LapTrace(listOf(0.0, 1.0), listOf(0.0, 1.0)), LapTrace(listOf(0.0), listOf(0.0)))
        }
    }

    @Test fun cumulativeTimeFromSpeed_constantSpeed() {
        // 100 m at a constant 100 km/h -> 100 m / (100/3.6 m/s) = 3.6 s
        val t = cumulativeTimeFromSpeed(listOf(0.0, 100.0), listOf(100.0, 100.0))
        assertEquals(0.0, t.first(), 1e-9)
        assertEquals(3.6, t.last(), 1e-6)
    }

    @Test fun cumulativeTimeFromSpeed_isMonotonic() {
        val t = cumulativeTimeFromSpeed(listOf(0.0, 50.0, 100.0, 150.0), listOf(200.0, 100.0, 250.0, 300.0))
        assertTrue(t.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0.0, t.first(), 1e-9)
    }

    @Test fun scaleToCanvas_mapsBoundsIntoBox_andFlipsY() {
        val (px, py) = scaleToCanvas(listOf(0.0, 10.0), listOf(0.0, 5.0), w = 100.0, h = 50.0, pad = 0.0)
        assertEquals(0.0, px.first(), 1e-9)
        assertEquals(100.0, px.last(), 1e-9)
        // y is flipped: the max data-y (5.0) maps to the TOP of the box (pixel 0), min to bottom (50)
        assertEquals(50.0, py.first(), 1e-9)
        assertEquals(0.0, py.last(), 1e-9)
    }

    @Test fun resampleByDistance_interpolatesAndClamps() {
        val srcD = listOf(0.0, 100.0, 200.0)
        val srcV = listOf(0.0, 10.0, 30.0)
        val out = resampleByDistance(srcD, srcV, listOf(0.0, 50.0, 150.0, 200.0))
        assertEquals(0.0, out[0], 1e-9)    // exact start
        assertEquals(5.0, out[1], 1e-9)    // midpoint of 0..10
        assertEquals(20.0, out[2], 1e-9)   // midpoint of 10..30
        assertEquals(30.0, out[3], 1e-9)   // exact end
    }

    @Test fun resampleByDistance_clampsOutsideRange() {
        val out = resampleByDistance(listOf(10.0, 20.0), listOf(1.0, 2.0), listOf(0.0, 30.0))
        assertEquals(1.0, out.first(), 1e-9)   // below range -> first value
        assertEquals(2.0, out.last(), 1e-9)    // above range -> last value
    }

    @Test fun resampleByDistance_letsDeltaCompareMisalignedGrids() {
        // two laps sampled on DIFFERENT distance grids; resample comparison onto reference grid first.
        val refDist = listOf(0.0, 100.0, 200.0)
        val refTime = listOf(0.0, 2.0, 4.0)
        val cmpDist = listOf(0.0, 50.0, 150.0, 200.0)
        val cmpTime = listOf(0.0, 1.05, 3.15, 4.4)
        val cmpOnRef = resampleByDistance(cmpDist, cmpTime, refDist)
        val d = deltaTime(LapTrace(refDist, refTime), LapTrace(refDist, cmpOnRef))
        assertEquals(0.0, d.first(), 1e-9)
        assertTrue(d.last() > 0.0)
    }

    @Test fun scaleToCanvas_degenerateRange_usesMidline() {
        val (px, py) = scaleToCanvas(listOf(5.0, 5.0), listOf(7.0, 7.0), w = 100.0, h = 40.0, pad = 0.0)
        assertTrue(px.all { it == 50.0 })
        assertTrue(py.all { it == 20.0 })
    }
}
